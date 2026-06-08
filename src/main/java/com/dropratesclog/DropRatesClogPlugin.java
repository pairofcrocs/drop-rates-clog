package com.dropratesclog;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.Skill;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
    name = "Clog Drop Rates",
    description = "Shows drop rates and acquisition methods when hovering over items in the collection log",
    tags = {"collection", "log", "drop", "rate", "rarity", "acquire", "shop"}
)
public class DropRatesClogPlugin extends Plugin
{
    private static final long   HOVER_TIMEOUT      = 150;
    private static final String MORE_COLOR         = "999999"; // grey for the "+N more" / "…and N more" overflow
    private static final int    TOOLTIP_MAX_CHARS  = 60;       // visible chars per line before we force-wrap

    // Live data is published to the orphan "data" branch and fetched on start-up so updates reach
    // users without a plugin rebuild/redistribution. The copies bundled in the jar are the offline
    // fallback. Trailing slash intended: file names are appended directly.
    private static final String DATA_BASE_URL =
        "https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/data/";

    @Inject
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private DropRatesClogTooltip tooltip;

    @Inject
    private DropRatesClogConfig config;

    @Inject
    private Gson gson;

    @Inject
    private OkHttpClient okHttpClient;

    // Reassigned wholesale (never mutated in place) from OkHttp callback threads and read on the
    // client thread, so volatile reference publication is all the synchronisation needed.
    private volatile Map<String, List<DropEntry>> dropRates       = Collections.emptyMap();
    private volatile Map<String, AcquirableItem>  acquirables     = Collections.emptyMap();
    private volatile Map<String, SkillingPet>     skillingPets    = Collections.emptyMap();
    // ID-indexed clog item table — wikiPage is the primary lookup key into the data files.
    private volatile Map<Integer, ClogItem>       clogItems       = Collections.emptyMap();
    private volatile Map<Integer, String>         itemAliases     = Collections.emptyMap();
    private volatile Map<String, Set<String>>     popularMethods  = Collections.emptyMap();

    // Guards against a late network callback repopulating data after the plugin has shut down.
    private volatile boolean running;

    private String  hoveredItem         = null;
    private int     hoveredItemId       = -1;
    private boolean hoveredItemObtained = false;
    private long    lastSeenTime        = 0;

    @Override
    protected void startUp()
    {
        running = true;

        // Load the copies bundled in the jar first so tooltips work instantly (and offline)...
        loadBundled();
        // ...then try to refresh each dataset from the data branch in the background.
        refreshFromNetwork();

        overlayManager.add(tooltip);
    }

    @Override
    protected void shutDown()
    {
        running = false;
        overlayManager.remove(tooltip);
        hoveredItem         = null;
        hoveredItemId       = -1;
        hoveredItemObtained = false;
        lastSeenTime        = 0;
    }

    /** Populate every dataset from the JSON bundled in the jar. Synchronous, but cheap (local). */
    private void loadBundled()
    {
        Map<String, List<DropEntry>> drops = loadResource(
            "/com/dropratesclog/drop_rates.json",
            new TypeToken<Map<String, List<DropEntry>>>() {}.getType());
        if (drops != null)
        {
            dropRates = drops;
            log.info("Loaded drop rates for {} items", drops.size());
        }

        Map<String, AcquirableItem> acquire = loadResource(
            "/com/dropratesclog/buyable.json",
            new TypeToken<Map<String, AcquirableItem>>() {}.getType());
        if (acquire != null)
        {
            acquirables = acquire;
            log.info("Loaded acquirable info for {} items", acquire.size());
        }

        Map<String, SkillingPet> pets = loadResource(
            "/com/dropratesclog/skilling_pets.json",
            new TypeToken<Map<String, SkillingPet>>() {}.getType());
        if (pets != null)
        {
            skillingPets = pets;
            log.info("Loaded skilling pet data for {} pets", pets.size());
        }

        Map<Integer, String> aliases = loadResource(
            "/com/dropratesclog/item_aliases.json",
            new TypeToken<Map<Integer, String>>() {}.getType());
        if (aliases != null)
        {
            itemAliases = aliases;
            log.info("Loaded {} item-name aliases", aliases.size());
        }

        // clog_items.json arrives as a JSON list of ClogItem; index by id for O(1) lookup.
        List<ClogItem> clogList = loadResource(
            "/com/dropratesclog/clog_items.json",
            new TypeToken<List<ClogItem>>() {}.getType());
        if (clogList != null)
        {
            Map<Integer, ClogItem> indexed = new HashMap<>(clogList.size() * 2);
            for (ClogItem ci : clogList)
            {
                if (ci != null) indexed.put(ci.getId(), ci);
            }
            clogItems = indexed;
            log.info("Loaded clog item table with {} entries", indexed.size());
        }

        Map<String, List<String>> popularList = loadResource(
            "/com/dropratesclog/popular_methods.json",
            new TypeToken<Map<String, List<String>>>() {}.getType());
        if (popularList != null)
        {
            popularMethods = toSetMap(popularList);
            log.info("Loaded popular methods for {} skills", popularMethods.size());
        }
    }

    /** Fire off an async fetch of each dataset from the data branch; successes replace the bundled
     *  copy, failures are logged and leave the bundled copy in place. Never blocks the caller. */
    private void refreshFromNetwork()
    {
        fetchData("drop_rates.json",
            new TypeToken<Map<String, List<DropEntry>>>() {}.getType(),
            (Map<String, List<DropEntry>> data) -> dropRates = data);

        fetchData("buyable.json",
            new TypeToken<Map<String, AcquirableItem>>() {}.getType(),
            (Map<String, AcquirableItem> data) -> acquirables = data);

        fetchData("skilling_pets.json",
            new TypeToken<Map<String, SkillingPet>>() {}.getType(),
            (Map<String, SkillingPet> data) -> skillingPets = data);

        fetchData("item_aliases.json",
            new TypeToken<Map<Integer, String>>() {}.getType(),
            (Map<Integer, String> data) -> itemAliases = data);

        fetchData("clog_items.json",
            new TypeToken<List<ClogItem>>() {}.getType(),
            (List<ClogItem> data) -> {
                Map<Integer, ClogItem> indexed = new HashMap<>(data.size() * 2);
                for (ClogItem ci : data) if (ci != null) indexed.put(ci.getId(), ci);
                clogItems = indexed;
            });

        fetchData("popular_methods.json",
            new TypeToken<Map<String, List<String>>>() {}.getType(),
            (Map<String, List<String>> data) -> popularMethods = toSetMap(data));
    }

    /** GET {@code DATA_BASE_URL + fileName}, parse as {@code type}, and hand the result to
     *  {@code apply} on success. Runs entirely on OkHttp's background threads. */
    private <T> void fetchData(String fileName, Type type, Consumer<T> apply)
    {
        Request request = new Request.Builder().url(DATA_BASE_URL + fileName).build();
        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Could not fetch {} from data branch, keeping bundled copy", fileName, e);
            }

            @Override
            public void onResponse(Call call, Response response)
            {
                try (ResponseBody body = response.body())
                {
                    if (!response.isSuccessful() || body == null)
                    {
                        log.debug("Unexpected response {} fetching {}", response.code(), fileName);
                        return;
                    }
                    T data = gson.fromJson(body.charStream(), type);
                    if (data != null && running)
                    {
                        apply.accept(data);
                        log.debug("Refreshed {} from data branch", fileName);
                    }
                }
                catch (Exception e)
                {
                    log.warn("Failed to parse {} fetched from data branch", fileName, e);
                }
            }
        });
    }

    /** Convert the popular-methods JSON shape (skill → list) to the set-backed map used for lookups. */
    private static Map<String, Set<String>> toSetMap(Map<String, List<String>> source)
    {
        Map<String, Set<String>> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : source.entrySet())
        {
            out.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        return out;
    }

    @Provides
    DropRatesClogConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DropRatesClogConfig.class);
    }

    private <T> T loadResource(String path, Type type)
    {
        try (InputStream is = getClass().getResourceAsStream(path))
        {
            if (is == null)
            {
                log.warn("{} not found", path);
                return null;
            }
            return gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), type);
        }
        catch (Exception e)
        {
            log.error("Failed to load {}", path, e);
            return null;
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        MenuEntry entry = event.getMenuEntry();
        if ((entry.getParam1() >>> 16) != InterfaceID.COLLECTION) return;

        String name = Text.removeTags(event.getTarget());
        if (name.isEmpty()) return;

        hoveredItem         = name;
        hoveredItemId       = entry.getItemId();
        hoveredItemObtained = isObtained(entry);
        lastSeenTime        = System.currentTimeMillis();

        if (config.hideCheckPopup())
        {
            // Blanking option + target hides the cursor's "Check <item>" hover indicator.
            // The right-click entry for this clog item also becomes empty as a side effect.
            entry.setOption("");
            entry.setTarget("");
        }
    }

    /** Whether the hovered clog item has been obtained. Unobtained slots are rendered with a
     *  reduced opacity (greyed-out); obtained slots are fully opaque. */
    private static boolean isObtained(MenuEntry entry)
    {
        Widget widget = entry.getWidget();
        // RuneLite's convention: opacity 0 = fully visible (obtained), >0 = partially transparent (locked).
        return widget != null && widget.getOpacity() == 0;
    }

    /**
     * Resolve a hovered clog slot to two lookup keys (wiki page + in-game/alias name) and a
     * display label. Looking up data maps tries {@link #pick} on both keys so the plugin works
     * with either the new wiki_page-keyed scrape output OR the legacy name-keyed files.
     */
    private static final class Lookup
    {
        final String wikiKey;       // clog_items.json's wiki_page (e.g. "Medallion fragment#1")
        final String nameKey;       // alias-resolved in-game name (back-compat with old data)
        final String displayLabel;  // what we render in the tooltip header

        Lookup(String wikiKey, String nameKey, String displayLabel)
        {
            this.wikiKey      = wikiKey;
            this.nameKey      = nameKey;
            this.displayLabel = displayLabel;
        }
    }

    private Lookup currentLookup()
    {
        ClogItem ci = clogItems.get(hoveredItemId);
        String wikiKey = ci != null ? ci.getWikiPage() : null;
        String nameKey = itemAliases.getOrDefault(hoveredItemId, hoveredItem);
        return new Lookup(wikiKey, nameKey, nameKey);
    }

    private static <T> T pick(Map<String, T> map, Lookup k)
    {
        T r = k.wikiKey != null ? map.get(k.wikiKey) : null;
        return r != null ? r : map.get(k.nameKey);
    }

    String buildActiveTooltip()
    {
        if (hoveredItem == null) return null;
        if (System.currentTimeMillis() - lastSeenTime > HOVER_TIMEOUT) return null;
        if (client.isMenuOpen()) return null;
        if (config.hideTooltipIfObtained() && hoveredItemObtained) return null;

        Lookup k = currentLookup();

        List<String> sections = new ArrayList<>(2);

        // For skilling pets, show only the skilling_pets.json data — suppress the (often noisy,
        // e.g. "Very rare"/"Unknown") drop-rate and acquirable entries the other files carry for them.
        boolean skillingPetOnly = config.showSkillingPetChance() && pick(skillingPets, k) != null;

        if (!skillingPetOnly)
        {
            if (config.showDropRates())
            {
                String drops = buildDropSection(k);
                if (drops != null) sections.add(drops);
            }

            if (config.showAcquirableItems())
            {
                String acquire = buildAcquirableSection(k);
                if (acquire != null) sections.add(acquire);
            }
        }

        if (config.showSkillingPetChance())
        {
            String pets = buildSkillingPetSection(k);
            if (pets != null) sections.add(pets);
        }

        if (sections.isEmpty())
        {
            // None of the data files cover this item (typical for special-currency reward
            // shops the wiki doesn't put in Bucket:Storeline — Mage Training Arena pizazz
            // points, Barbarian Assault honour points, etc.). Still render the item name
            // so the user has visual confirmation the plugin recognised the slot.
            String itemCol = toHex(config.itemNameColor());
            return wrapTooltipLines(
                "<col=" + itemCol + ">" + k.displayLabel + "</col>",
                TOOLTIP_MAX_CHARS);
        }
        return wrapTooltipLines(String.join("<br>", sections), TOOLTIP_MAX_CHARS);
    }

    /**
     * Word-wrap long tooltip lines so they don't clip off the right side of the screen.
     * Walks the wikitext char-by-char: passes <tags> through transparently (they don't
     * count toward the visible width), and when the visible count for the current line
     * exceeds {@code maxChars} the next whitespace is replaced with <br>. Existing <br>
     * separators reset the counter.
     */
    static String wrapTooltipLines(String text, int maxChars)
    {
        if (text == null || maxChars <= 0) return text;
        StringBuilder out = new StringBuilder(text.length() + 16);
        int visible = 0;
        int i = 0;
        final int n = text.length();
        while (i < n)
        {
            char c = text.charAt(i);
            if (c == '<')
            {
                int end = text.indexOf('>', i);
                if (end < 0) { out.append(text, i, n); return out.toString(); }
                // <br> tags reset the visible-width counter; other tags (e.g. <col=...>) just pass through.
                if (end - i == 3 && text.charAt(i + 1) == 'b' && text.charAt(i + 2) == 'r')
                {
                    out.append("<br>");
                    visible = 0;
                }
                else
                {
                    out.append(text, i, end + 1);
                }
                i = end + 1;
                continue;
            }
            // Wrap at the first whitespace once we're past the limit. Mid-word breaks would
            // split words awkwardly; long words just briefly overshoot until the next space.
            if (visible >= maxChars && Character.isWhitespace(c))
            {
                out.append("<br>");
                visible = 0;
                i++;
                continue;
            }
            out.append(c);
            visible++;
            i++;
        }
        return out.toString();
    }

    private String buildDropSection(Lookup k)
    {
        List<DropEntry> groups = pick(dropRates, k);
        if (groups == null || groups.isEmpty()) return null;

        int maxGroups = config.maxSources();
        boolean asPercentage = config.showDropRateAsPercentage();
        boolean reduceFractions = config.reduceFractions();
        String headerCol = toHex(config.headerColor());
        String itemCol   = toHex(config.itemNameColor());
        String rateCol   = toHex(config.rateColor());
        String srcCol    = toHex(config.sourceColor());
        StringBuilder sb = new StringBuilder("<col=").append(headerCol).append(">Drop Rate: </col>")
            .append("<col=").append(itemCol).append(">").append(k.displayLabel).append("</col>");

        for (int i = 0; i < groups.size(); i++)
        {
            if (maxGroups > 0 && i >= maxGroups)
            {
                sb.append("<br><col=").append(MORE_COLOR).append(">…and ").append(groups.size() - i).append(" more</col>");
                break;
            }
            DropEntry entry = groups.get(i);
            sb.append("<br><col=").append(rateCol).append(">").append(formatDetail(entry.displayRate(asPercentage, reduceFractions))).append("</col>: ");
            appendSources(sb, entry.sources, srcCol);
        }

        return sb.toString();
    }

    private String buildAcquirableSection(Lookup k)
    {
        AcquirableItem data = pick(acquirables, k);
        if (data == null || data.entries == null) return null;

        // Drop entries that carry neither a detail nor any source — nothing to show.
        List<AcquirableEntry> entries = new ArrayList<>(data.entries.size());
        for (AcquirableEntry entry : data.entries)
        {
            boolean hasSources = entry.sources != null && !entry.sources.isEmpty();
            if (entry.displayDetail() == null && !hasSources) continue;
            entries.add(entry);
        }
        if (entries.isEmpty()) return null;

        int maxGroups = config.maxSources();
        String headerCol = toHex(config.headerColor());
        String itemCol   = toHex(config.itemNameColor());
        String rateCol   = toHex(config.rateColor());
        String srcCol    = toHex(config.sourceColor());
        StringBuilder sb = new StringBuilder("<col=").append(headerCol).append(">Acquirable Items: </col>")
            .append("<col=").append(itemCol).append(">").append(k.displayLabel).append("</col>");

        for (int i = 0; i < entries.size(); i++)
        {
            if (maxGroups > 0 && i >= maxGroups)
            {
                sb.append("<br><col=").append(MORE_COLOR).append(">…and ").append(entries.size() - i).append(" more</col>");
                break;
            }
            AcquirableEntry entry = entries.get(i);
            sb.append("<br>");

            String detail = entry.displayDetail();
            if (detail != null)
            {
                sb.append("<col=").append(rateCol).append(">").append(formatDetail(detail)).append("</col>");
            }
            if (entry.sources != null && !entry.sources.isEmpty())
            {
                if (detail != null) sb.append(": ");
                appendSources(sb, entry.sources, srcCol);
            }
        }

        return sb.toString();
    }

    private String buildSkillingPetSection(Lookup k)
    {
        SkillingPet pet = pick(skillingPets, k);
        if (pet == null || pet.sources == null) return null;

        int level = realLevel(pet.skill);

        Set<String> popular = popularMethods.get(pet.skill);
        boolean popularOnly = config.showOnlyPopularMethods() && popular != null;

        List<SkillingPetSource> rows = new ArrayList<>(pet.sources.size());
        for (SkillingPetSource source : pet.sources)
        {
            if (!source.isShowable(level)) continue;
            if (popularOnly && !popular.contains(source.method)) continue;
            rows.add(source);
        }
        if (rows.isEmpty()) return null;

        rows.sort(Comparator.comparingLong(source -> source.sortKey(level)));

        int maxGroups = config.maxSources();
        boolean asPercentage = config.showDropRateAsPercentage();
        String headerCol = toHex(config.headerColor());
        String itemCol   = toHex(config.itemNameColor());
        String rateCol   = toHex(config.rateColor());
        String srcCol    = toHex(config.sourceColor());
        StringBuilder sb = new StringBuilder("<col=").append(headerCol).append(">Skilling Pet: </col>")
            .append("<col=").append(itemCol).append(">").append(k.displayLabel).append("</col>")
            .append("<col=").append(headerCol).append("> - ").append(pet.skill);
        if (level > 0) sb.append(" (Lvl ").append(level).append(')');
        sb.append("</col>");

        for (int i = 0; i < rows.size(); i++)
        {
            if (maxGroups > 0 && i >= maxGroups)
            {
                sb.append("<br><col=").append(MORE_COLOR).append(">…and ").append(rows.size() - i).append(" more</col>");
                break;
            }
            SkillingPetSource source = rows.get(i);
            sb.append("<br><col=").append(rateCol).append(">").append(formatDetail(source.displayRate(level, asPercentage))).append("</col>: ");
            sb.append("<col=").append(srcCol).append(">").append(source.method).append("</col>");
        }

        return sb.toString();
    }

    /** The player's real (un-boosted) level in the named skill, or 0 if unknown (not logged in / unrecognised skill). */
    private int realLevel(String skillName)
    {
        if (skillName == null) return 0;
        try
        {
            return client.getRealSkillLevel(Skill.valueOf(skillName.toUpperCase()));
        }
        catch (IllegalArgumentException e)
        {
            return 0;
        }
    }

    /** Append a source list, collapsing more than 3 entries into a "+N more" suffix. */
    private static void appendSources(StringBuilder sb, List<String> sources, String sourceCol)
    {
        if (sources.size() <= 3)
        {
            sb.append("<col=").append(sourceCol).append(">").append(String.join(", ", sources)).append("</col>");
        }
        else
        {
            sb.append("<col=").append(sourceCol).append(">").append(String.join(", ", sources.subList(0, 3))).append("</col>")
              .append("<col=").append(MORE_COLOR).append("> +").append(sources.size() - 3).append(" more</col>");
        }
    }

    /** Convert a Color to a 6-char hex string suitable for OSRS &lt;col=...&gt; tags. */
    private static String toHex(Color color)
    {
        return String.format("%06x", color.getRGB() & 0xFFFFFF);
    }

    /**
     * Title-case the first letter of each word, leaving the rest of each word alone (so values like
     * "TzHaar-Hur" or "LMS" are preserved). Word boundary = any non-letter except apostrophe, so
     * "Justine's" stays as "Justine's" but "swordfish/tuna" becomes "Swordfish/Tuna".
     */
    private static String titleCase(String s)
    {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean atWordStart = true;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (Character.isLetter(c))
            {
                sb.append(atWordStart ? Character.toUpperCase(c) : c);
                atWordStart = false;
            }
            else
            {
                sb.append(c);
                atWordStart = (c != '\'');
            }
        }
        return sb.toString();
    }

    // 4+ contiguous digits not immediately after a '.', so decimal fractions like "0.000401%"
    // stay intact. Catches values like "1/120000" → "1/120,000" or "10000 points" → "10,000 points".
    private static final Pattern THOUSANDS_RX = Pattern.compile("(?<!\\.)\\b\\d{4,}\\b");

    private static String formatThousands(String s)
    {
        if (s == null) return s;
        Matcher m = THOUSANDS_RX.matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find())
        {
            String n = m.group();
            StringBuilder commaed = new StringBuilder(n.length() + n.length() / 3);
            int len = n.length();
            for (int i = 0; i < len; i++)
            {
                if (i > 0 && (len - i) % 3 == 0) commaed.append(',');
                commaed.append(n.charAt(i));
            }
            m.appendReplacement(out, commaed.toString());
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Cosmetic pass for rate/detail strings: Title-case + thousands separators. */
    private static String formatDetail(String s)
    {
        return formatThousands(titleCase(s));
    }
}
