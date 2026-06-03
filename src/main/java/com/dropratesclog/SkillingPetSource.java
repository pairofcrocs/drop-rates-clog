package com.dropratesclog;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One activity that can roll for a skilling pet. */
class SkillingPetSource
{
    private static final Pattern LEADING_RATE = Pattern.compile("1/(\\d[\\d,]*)");

    String  method;     // activity name, e.g. "Runite rocks"
    int     level;      // activity level requirement (informational); 0 if absent
    Integer baseChance; // level-based: chance per action = 1/(baseChance - playerLevel*25); null for fixed-rarity sources
    String  rarity;     // fixed rarity string, e.g. "1/120000"; null for level-based sources

    /**
     * Per-action chance shown in the tooltip, given the player's real level in the skill. When
     * {@code asPercentage} is set, level-based chances (and clean "1/N" fixed rarities) are shown
     * as a percentage; annotated rarity strings (ranges, notes) are left as-is.
     */
    String displayRate(int playerLevel, boolean asPercentage)
    {
        if (baseChance != null)
        {
            long denom = denominator(playerLevel);
            return asPercentage
                ? RateFormat.formatPercent(1.0 / denom) + "%"
                : "1/" + String.format(Locale.ENGLISH, "%,d", denom);
        }
        if (asPercentage)
        {
            return RateFormat.percentifyFractions(rarity);
        }
        return rarity;
    }

    /** Sort key: smaller denominator = better chance, so activities sort best-first. */
    long sortKey(int playerLevel)
    {
        if (baseChance != null)
        {
            return playerLevel <= 0 ? Long.MAX_VALUE : denominator(playerLevel);
        }
        if (rarity != null)
        {
            Matcher m = LEADING_RATE.matcher(rarity);
            if (m.find())
            {
                try
                {
                    return Long.parseLong(m.group(1).replace(",", ""));
                }
                catch (NumberFormatException ignored)
                {
                    // fall through
                }
            }
        }
        return Long.MAX_VALUE;
    }

    /** Whether this source can be displayed at the given level. */
    boolean isShowable(int playerLevel)
    {
        return baseChance != null ? playerLevel > 0 : rarity != null;
    }

    private long denominator(int playerLevel)
    {
        long denom = baseChance - (long) playerLevel * 25;
        return denom < 1 ? 1 : denom;
    }
}
