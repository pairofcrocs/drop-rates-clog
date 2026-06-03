package com.dropratesclog;

import java.util.List;
import java.util.Locale;

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
     * Rate for display, optionally transformed. {@code asPercentage} converts a ratio to a percentage
     * (e.g. "~0.0191%"); otherwise {@code reduceFraction} normalises it to "1 in N" (e.g. "90/18,014"
     * → "~1/200", "2 × 1/33" → "~1/17", with no ~ when the result is exact). Non-numeric rates ("Very
     * rare") and currency costs are always left unchanged. Percentage takes precedence if both are set.
     */
    String displayRate(boolean asPercentage, boolean reduceFraction)
    {
        if (asPercentage)
        {
            Double probability = RateFormat.parseProbability(rate);
            if (probability != null)
            {
                return (approx ? "~" : "") + RateFormat.formatPercent(probability) + "%";
            }
        }
        else if (reduceFraction)
        {
            String reduced = reduceToOneInN();
            if (reduced != null)
            {
                return reduced;
            }
        }
        return displayRate();
    }

    /**
     * Normalise the rate to "1/N" (numerator 1). Multi-roll rates are combined first (2 × 1/33 → 2/33).
     * The denominator is rounded; a "~" prefix marks any rounding (or an approximate source rate).
     * Returns null for rates that aren't a numeric probability.
     */
    private String reduceToOneInN()
    {
        Double probability = RateFormat.parseProbability(rate);
        if (probability == null || probability <= 0 || probability >= 1.0)
        {
            return null; // not a reducible fraction (e.g. "Always" / 1/1) — leave as-is
        }
        double denominator = 1.0 / probability;
        long rounded = Math.max(1, Math.round(denominator));
        boolean exact = !approx && Math.abs(denominator - rounded) < 1e-6;
        return (exact ? "" : "~") + "1/" + String.format(Locale.ENGLISH, "%,d", rounded);
    }
}
