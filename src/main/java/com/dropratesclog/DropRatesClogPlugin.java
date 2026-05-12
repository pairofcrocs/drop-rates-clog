package com.dropratesclog;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

import javax.inject.Inject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@PluginDescriptor(
    name = "Clog Drop Rates",
    description = "Shows drop rates when hovering over items in the collection log",
    tags = {"collection", "log", "drop", "rate", "rarity"}
)
public class DropRatesClogPlugin extends Plugin
{
    private static final int CLOG_INTERFACE = 621;
    private static final long HOVER_TIMEOUT = 150;

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

    private Map<String, List<DropEntry>> dropRates = Collections.emptyMap();

    private String hoveredItem = null;
    private long lastSeenTime = 0;

    @Override
    protected void startUp()
    {
        loadDropRates();
        overlayManager.add(tooltip);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(tooltip);
        hoveredItem  = null;
        lastSeenTime = 0;
    }

    @Provides
    DropRatesClogConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(DropRatesClogConfig.class);
    }

    private void loadDropRates()
    {
        try (InputStream is = getClass().getResourceAsStream("/com/dropratesclog/drop_rates.json"))
        {
            if (is == null)
            {
                log.warn("drop_rates.json not found");
                return;
            }
            Type type = new TypeToken<Map<String, List<DropEntry>>>() {}.getType();
            dropRates = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), type);
            log.info("Loaded drop rates for {} items", dropRates.size());
        }
        catch (Exception e)
        {
            log.error("Failed to load drop_rates.json", e);
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        MenuEntry entry = event.getMenuEntry();
        if ((entry.getParam1() >>> 16) != CLOG_INTERFACE) return;

        String name = Text.removeTags(event.getTarget());
        if (name.isEmpty()) return;

        hoveredItem  = name;
        lastSeenTime = System.currentTimeMillis();
    }

    String buildActiveTooltip()
    {
        if (hoveredItem == null) return null;
        if (System.currentTimeMillis() - lastSeenTime > HOVER_TIMEOUT) return null;
        if (client.isMenuOpen()) return null;

        List<DropEntry> groups = dropRates.get(hoveredItem);
        if (groups == null || groups.isEmpty()) return null;

        int maxGroups = config.maxSources();
        StringBuilder sb = new StringBuilder("<col=ffffff>Drop Rate</col>");

        for (int i = 0; i < groups.size(); i++)
        {
            if (maxGroups > 0 && i >= maxGroups)
            {
                sb.append("<br><col=999999>…and ").append(groups.size() - i).append(" more</col>");
                break;
            }
            DropEntry entry = groups.get(i);
            sb.append("<br><col=ffff00>").append(entry.displayRate()).append("</col>: ");

            List<String> srcs = entry.sources;
            if (srcs.size() <= 3)
            {
                sb.append(String.join(", ", srcs));
            }
            else
            {
                sb.append(String.join(", ", srcs.subList(0, 3)))
                  .append("<col=999999> +").append(srcs.size() - 3).append(" more</col>");
            }
        }

        return sb.toString();
    }
}
