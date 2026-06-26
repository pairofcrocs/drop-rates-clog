package com.dropratesclog;

import java.util.List;

/** One rate group for an item: a rate shared by one or more sources. */
class DropEntry
{
    String       kind;     // Gson sets this from the JSON's "kind" field; unused at runtime.
    String       rate;     // e.g. "1/512", "3 × 1/512"
    boolean      approx;   // true → display with ~ prefix
    List<String> sources;  // e.g. ["Abyssal demon (Standard)"]

    /** Formatted rate string shown in the tooltip, e.g. "~1/70,000" or "Very rare". */
    String displayRate()
    {
        return approx ? "~" + rate : rate;
    }

    /**
     * Rate for display, transformed per the user's config. When {@link DropRatesClogConfig#combineMultiRolls()}
     * is set, a multi-roll rate ("3 × 1/6") is merged into one probability
     * ({@link DropRatesClogConfig#multiRollChancePerKill()}); otherwise the "N ×" multiplier is kept and the
     * per-roll fraction is formatted on its own. Either way the per-roll/combined probability is shown as a
     * percentage ({@link DropRatesClogConfig#showDropRateAsPercentage()}) or, when
     * {@link DropRatesClogConfig#normaliseToOneInN()} is set, as "1/N" with
     * {@link DropRatesClogConfig#oneInNDecimals()} decimal places. Non-numeric rates ("Very rare") and
     * currency costs are always left unchanged.
     */
    String displayRate(DropRatesClogConfig config)
    {
        RateFormat.Roll roll = RateFormat.parseRoll(rate);
        if (roll == null)
        {
            return displayRate(); // non-numeric ("Very rare") / currency cost — leave as-is
        }

        // Keep the "N ×" multiplier when the user doesn't want multiple rolls merged, but still apply the
        // per-roll display format ("Show as 1/N" / percentage) to the underlying a/b fraction.
        if (roll.rolls > 1 && !config.combineMultiRolls())
        {
            String perRoll = formatProbability(config, roll.perRoll, false);
            return perRoll == null ? displayRate() : roll.rolls + " × " + perRoll;
        }

        // Combined multi-rolls have no clean "a/b" form, so they are always shown as 1/N (or percentage).
        double probability = RateFormat.combinedProbability(roll, config.multiRollChancePerKill());
        String formatted = formatProbability(config, probability, roll.rolls > 1);
        return formatted == null ? displayRate() : formatted;
    }

    /**
     * Format a single probability per the user's display settings, or null when neither percentage nor 1/N
     * applies (a plain fraction with normalising disabled) so the caller can fall back to the raw text.
     * {@code forceOneInN} shows 1/N even when {@link DropRatesClogConfig#normaliseToOneInN()} is off, used for
     * combined multi-rolls that have no clean fraction to fall back to.
     */
    private String formatProbability(DropRatesClogConfig config, double probability, boolean forceOneInN)
    {
        if (config.showDropRateAsPercentage())
        {
            return (approx ? "~" : "") + RateFormat.formatPercent(probability) + "%";
        }
        if ((forceOneInN || config.normaliseToOneInN()) && probability > 0 && probability < 1.0)
        {
            return RateFormat.formatOneInN(probability, config.oneInNDecimals(), approx);
        }
        return null; // plain fraction, normalising disabled — caller leaves it as-is
    }
}
