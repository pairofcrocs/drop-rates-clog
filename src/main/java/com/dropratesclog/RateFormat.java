package com.dropratesclog;

import java.math.BigDecimal;
import java.math.MathContext;
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

    /** Parse a rate string into a probability in (0,1], or null if it isn't a plain numeric rate. */
    static Double parseProbability(String rate)
    {
        if (rate == null)
        {
            return null;
        }
        String s = rate.replace(",", "").trim().toLowerCase();
        if (s.equals("always") || s.equals("1/1"))
        {
            return 1.0;
        }

        double value;
        Matcher m = MULTI_RATE.matcher(s);
        if (m.matches())
        {
            value = Integer.parseInt(m.group(1)) * Double.parseDouble(m.group(2)) / Double.parseDouble(m.group(3));
        }
        else if ((m = SIMPLE_RATE.matcher(s)).matches())
        {
            value = Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
        }
        else
        {
            return null;
        }

        return (Double.isFinite(value) && value > 0) ? value : null;
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
