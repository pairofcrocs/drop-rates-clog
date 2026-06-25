package com.dropratesclog;

/** How a rate rolled several times per kill ("N × a/b") is combined into a single probability.
 *  MUST be public: it's the return type of a config item, and RuneLite's generated config proxy
 *  (loaded by the plugin-hub classloader, in com.sun.proxy) can't access a package-private type —
 *  doing so throws IllegalAccessError on every tooltip render. */
public enum MultiRollMethod
{
    /** Expected number of drops per kill: N × a/b (the scraper's linear interpretation). */
    EXPECTED_COUNT("Expected count (N × a/b)"),
    /** Chance of at least one drop per kill: 1 − (1 − a/b)^N. */
    AT_LEAST_ONE("Chance per kill (1−(1−a/b)^N)");

    private final String label;

    MultiRollMethod(String label)
    {
        this.label = label;
    }

    @Override
    public String toString()
    {
        return label;
    }
}
