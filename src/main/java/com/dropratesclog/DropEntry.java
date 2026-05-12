package com.dropratesclog;

import java.util.List;

/** One rate group for an item: a rate shared by one or more drop sources. */
class DropEntry
{
    String       rate;     // e.g. "1/512", "3 × 1/512", "Very rare"
    boolean      approx;   // true → display with ~ prefix
    List<String> sources;  // e.g. ["Abyssal demon (Standard)", "Abyssal demon (Wilderness)"]

    /** Formatted rate string shown in the tooltip, e.g. "~1/70,000" or "1/512". */
    String displayRate()
    {
        return approx ? "~" + rate : rate;
    }
}
