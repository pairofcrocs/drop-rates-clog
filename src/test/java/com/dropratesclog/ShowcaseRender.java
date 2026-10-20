package com.dropratesclog;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.TooltipComponent;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;

/**
 * Renders real plugin tooltips (and RuneLite's fonts) for the README images. Run via docs/render.sh.
 * args: dataDir outDir specFile... Each spec line is either
 *   name | itemId | itemName | skillLevel | obtained (y/n) | configKey=value;...
 * or, for a non-plugin tooltip such as RuneLite's own mouse tooltip,
 *   name | text | raw tooltip text
 */
public class ShowcaseRender
{
    private static final Map<String, String> overrides = new HashMap<>();
    private static int level = 99;

    public static void main(String[] args) throws Exception
    {
        File data = new File(args[0]), out = new File(args[1]);
        new File(out, "img").mkdirs();
        for (String font : new String[]{"runescape.ttf", "runescape_small.ttf", "runescape_bold.ttf"})
        {
            try (InputStream in = FontManager.class.getResourceAsStream(font))
            {
                Files.copy(in, new File(out, font).toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        DropRatesClogPlugin plugin = new DropRatesClogPlugin();
        Gson gson = new Gson();

        DropRatesClogConfig config = (DropRatesClogConfig) Proxy.newProxyInstance(
            DropRatesClogConfig.class.getClassLoader(), new Class<?>[]{DropRatesClogConfig.class},
            (p, m, a) -> {
                String v = overrides.get(m.getName());
                if (v != null)
                {
                    Class<?> r = m.getReturnType();
                    if (r == boolean.class) return Boolean.parseBoolean(v);
                    if (r == int.class) return Integer.parseInt(v);
                    if (r == Color.class) return Color.decode("#" + v);
                }
                return MethodHandles.privateLookupIn(DropRatesClogConfig.class, MethodHandles.lookup())
                    .unreflectSpecial(m, DropRatesClogConfig.class).bindTo(p).invokeWithArguments(a == null ? new Object[0] : a);
            });
        Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
            (p, m, a) -> {
                if (m.getName().equals("getRealSkillLevel")) return level;
                Class<?> r = m.getReturnType();
                if (r == boolean.class) return false;
                if (r == int.class) return 0;
                if (r == long.class) return 0L;
                return null;
            });
        set(plugin, "config", config);
        set(plugin, "client", client);
        set(plugin, "gson", gson);

        set(plugin, "dropRates", idMap(gson.fromJson(new FileReader(new File(data, "drop_rates.json")), new TypeToken<Map<String, List<DropEntry>>>(){}.getType())));
        set(plugin, "slayerRates", idMap(gson.fromJson(new FileReader(new File(data, "slayer_rates.json")), new TypeToken<Map<String, List<DropEntry>>>(){}.getType())));
        set(plugin, "acquirables", idMap(gson.fromJson(new FileReader(new File(data, "buyable.json")), new TypeToken<Map<String, AcquirableItem>>(){}.getType())));
        set(plugin, "skillingPets", idMap(gson.fromJson(new FileReader(new File(data, "skilling_pets.json")), new TypeToken<Map<String, SkillingPet>>(){}.getType())));
        Method toSet = DropRatesClogPlugin.class.getDeclaredMethod("toSetMap", Map.class);
        toSet.setAccessible(true);
        set(plugin, "popularMethods", toSet.invoke(null, (Object) gson.fromJson(new FileReader(new File(data, "popular_methods.json")), new TypeToken<Map<String, List<String>>>(){}.getType())));

        List<String> lines = new ArrayList<>();
        for (int i = 2; i < args.length; i++) lines.addAll(Files.readAllLines(new File(args[i]).toPath(), StandardCharsets.UTF_8));
        for (String line : lines)
        {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            for (int i = 0; i < f.length; i++) f[i] = f[i].trim();

            String text;
            if (f[1].equals("text"))
            {
                text = f[2];
            }
            else
            {
                overrides.clear();
                if (f.length > 5 && !f[5].isEmpty())
                {
                    for (String kv : f[5].split(";"))
                    {
                        String[] p = kv.split("=", 2);
                        overrides.put(p[0].trim(), p[1].trim());
                    }
                }
                level = Integer.parseInt(f[3]);
                set(plugin, "hoveredItemId", Integer.parseInt(f[1]));
                set(plugin, "hoveredItem", f[2]);
                set(plugin, "hoveredItemObtained", f[4].equals("y"));
                set(plugin, "lastSeenTime", System.currentTimeMillis());
                text = plugin.buildActiveTooltip();
            }
            // A hidden tooltip (e.g. "Hide tooltip if obtained") writes no image.
            File png = new File(out, "img/tip_" + f[0] + ".png");
            png.delete();
            if (text != null) ImageIO.write(render(text), "png", png);
        }
    }

    static BufferedImage render(String text)
    {
        TooltipComponent tc = new TooltipComponent();
        tc.setText(text);
        tc.setBackgroundColor(ComponentConstants.STANDARD_BACKGROUND_COLOR);
        tc.setModIcons(new IndexedSprite[0]);
        tc.setPreferredLocation(new Point(0, 0));
        BufferedImage scratch = new BufferedImage(1000, 400, BufferedImage.TYPE_INT_ARGB);
        Dimension d = tc.render(prep(scratch.createGraphics()));
        BufferedImage img = new BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = prep(img.createGraphics());
        tc.render(g);
        g.dispose();
        return img;
    }

    static Graphics2D prep(Graphics2D g)
    {
        g.setFont(FontManager.getRunescapeSmallFont());
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        return g;
    }

    static Object idMap(Object raw) throws Exception
    {
        Method m = DropRatesClogPlugin.class.getDeclaredMethod("idMap", Map.class);
        m.setAccessible(true);
        return m.invoke(null, raw);
    }

    static void set(Object o, String f, Object v) throws Exception
    {
        Field field = DropRatesClogPlugin.class.getDeclaredField(f);
        field.setAccessible(true);
        field.set(o, v);
    }
}
