package com.dropratesclog;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared parsing/formatting for rate strings, used by both drop rates and skilling-pet chances. */
final class RateFormat
{
    private RateFormat()
    {
    }

    // "3 × 1/512" — N independent rolls at a/b each (matches the scraper's linear interpretation).
    private static final Pattern MULTI_RATE  = Pattern.compile("^(\\d+)\\s*[×x]\\s*(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)$");
    // "1/512", "1/249262.5"
    private static final Pattern SIMPLE_RATE = Pattern.compile("^(\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)$");
    // A single "a/b" fraction anywhere within a larger string (commas allowed in the numbers).
    private static final Pattern FRACTION    = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s*/\\s*(\\d[\\d,]*(?:\\.\\d+)?)");

    /** A parsed numeric rate: a per-roll probability (a/b) repeated over {@code rolls} independent rolls. */
    static final class Roll
    {
        final int rolls;      // N (1 for a plain "a/b" rate)
        final double perRoll; // a/b

        Roll(int rolls, double perRoll)
        {
            this.rolls = rolls;
            this.perRoll = perRoll;
        }
    }

    /** Parse a rate string into a {@link Roll}, or null if it isn't a plain numeric rate. */
    static Roll parseRoll(String rate)
    {
        if (rate == null)
        {
            return null;
        }
        String s = rate.replace(",", "").trim().toLowerCase();
        if (s.equals("always") || s.equals("1/1"))
        {
            return new Roll(1, 1.0);
        }

        Matcher m = MULTI_RATE.matcher(s);
        if (m.matches())
        {
            double perRoll = Double.parseDouble(m.group(2)) / Double.parseDouble(m.group(3));
            return finiteRoll(Integer.parseInt(m.group(1)), perRoll);
        }
        if ((m = SIMPLE_RATE.matcher(s)).matches())
        {
            double perRoll = Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
            return finiteRoll(1, perRoll);
        }
        return null;
    }

    private static Roll finiteRoll(int rolls, double perRoll)
    {
        return (Double.isFinite(perRoll) && perRoll > 0) ? new Roll(rolls, perRoll) : null;
    }

    /**
     * Combine a roll's N independent attempts into a single probability. With {@code atLeastOne} this is the
     * chance of at least one drop per kill, 1 − (1 − a/b)^N; otherwise the expected number of drops, N × a/b
     * (clamped to 1.0). Single-roll rates return their per-roll probability unchanged.
     */
    static double combinedProbability(Roll roll, boolean atLeastOne)
    {
        if (roll.rolls <= 1)
        {
            return roll.perRoll;
        }
        return atLeastOne
            ? 1.0 - Math.pow(1.0 - roll.perRoll, roll.rolls)
            : Math.min(1.0, roll.rolls * roll.perRoll);
    }

    /** Parse a rate string into a probability in (0,1], or null if it isn't a plain numeric rate. */
    static Double parseProbability(String rate)
    {
        Roll roll = parseRoll(rate);
        if (roll == null)
        {
            return null;
        }
        double value = combinedProbability(roll, false); // legacy linear combine (matches the scraper)
        return (Double.isFinite(value) && value > 0) ? value : null;
    }

    /**
     * Format a probability as "1/N", keeping up to {@code decimals} fractional digits in N (trailing zeros
     * dropped, so 1/512 stays "1/512"). A "~" prefix marks an approximate source rate or any value that had
     * to be rounded to fit the requested precision (e.g. 90/18,014 at 1 decimal → "~1/200.2").
     */
    static String formatOneInN(double probability, int decimals, boolean approxSource)
    {
        int d = Math.max(0, decimals);
        double n = 1.0 / probability;
        double rounded = BigDecimal.valueOf(n).setScale(d, RoundingMode.HALF_UP).doubleValue();
        boolean exact = !approxSource && Math.abs(n - rounded) < 1e-6;

        DecimalFormat df = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        df.setMaximumFractionDigits(d);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return (exact ? "" : "~") + "1/" + df.format(n);
    }

    /** Format a probability as a percentage with 3 significant figures, e.g. 0.001953 → "0.195". */
    static String formatPercent(double probability)
    {
        return BigDecimal.valueOf(probability * 100.0)
            .round(new MathContext(3))
            .stripTrailingZeros()
            .toPlainString();
    }

    /**
     * Replace every "a/b" fraction inside a string with its percentage, leaving the rest intact, e.g.
     * "1/6000 to 1/3150 (scales by task level)" → "0.0167% to 0.0317% (scales by task level)" and
     * "1/360000 per fishing attempt" → "0.000278% per fishing attempt". Strings with no fraction are
     * returned unchanged. Used to keep annotated rarity strings consistent in percentage mode.
     */
    static String percentifyFractions(String text)
    {
        if (text == null)
        {
            return null;
        }
        Matcher m = FRACTION.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find())
        {
            double numerator   = Double.parseDouble(m.group(1).replace(",", ""));
            double denominator = Double.parseDouble(m.group(2).replace(",", ""));
            String replacement = denominator > 0 ? formatPercent(numerator / denominator) + "%" : m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
