package com.dropratesclog;

import com.google.gson.annotations.JsonAdapter;

import java.util.List;

/** One way to acquire an item from buyable.json: a cost / requirement / rate shared by one or more sources. */
class AcquirableEntry
{
    // The free-text fields below are usually scalar but the scraper occasionally emits an array,
    // so they tolerate either shape (arrays are joined into one string).
    String       kind;        // currently always "currency"
    @JsonAdapter(StringOrArrayAdapter.class)
    String       rate;        // drop-like rarity for rare acquisitions, e.g. "1/25,000"
    @JsonAdapter(StringOrArrayAdapter.class)
    String       cost;        // currency price, e.g. "2,250 Mox, 2,800 Aga, 3,700 Lye"
    @JsonAdapter(StringOrArrayAdapter.class)
    String       description; // free-text requirement, e.g. "Complete Animal Magnetism and Pandemonium"
    boolean      approx;      // true → display rate with ~ prefix
    List<String> sources;     // shops / activities offering it, e.g. ["Mixology Rewards"]

    /**
     * The detail shown in yellow before the sources — whichever of rate, cost and
     * description are present, joined. Returns null when the entry carries none of them.
     */
    String displayDetail()
    {
        StringBuilder sb = new StringBuilder();
        if (rate != null && !rate.isEmpty())
        {
            sb.append(approx ? "~" + rate : rate);
        }
        if (cost != null && !cost.isEmpty())
        {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(cost);
        }
        if (description != null && !description.isEmpty())
        {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(description);
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
