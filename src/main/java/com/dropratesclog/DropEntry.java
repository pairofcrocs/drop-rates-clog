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
     * Rate for display, transformed per the user's config. Multi-roll rates ("3 × 1/6") are kept verbatim
     * unless {@link DropRatesClogConfig#combineMultiRolls()} is set, in which case they are merged into one
     * probability ({@link DropRatesClogConfig#multiRollChancePerKill()}). The resulting probability is then shown as a
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

        // Keep "N × a/b" intact when the user doesn't want multiple rolls combined.
        if (roll.rolls > 1 && !config.combineMultiRolls())
        {
            return displayRate();
        }

        boolean atLeastOne = config.multiRollChancePerKill();
        double probability = RateFormat.combinedProbability(roll, atLeastOne);

        if (config.showDropRateAsPercentage())
        {
            return (approx ? "~" : "") + RateFormat.formatPercent(probability) + "%";
        }

        // Combined multi-rolls have no clean "a/b" form, so they are always shown as 1/N.
        boolean combined = roll.rolls > 1;
        if ((config.normaliseToOneInN() || combined) && probability > 0 && probability < 1.0)
        {
            return RateFormat.formatOneInN(probability, config.oneInNDecimals(), approx);
        }

        return displayRate(); // plain fraction, normalising disabled — leave as-is
    }
}
