package com.dropratesclog;

import java.util.List;

/** One item's acquisition info from buyable.json: the wiki revision it was scraped from plus its rate groups. */
class AcquirableItem
{
    long                 revid;   // source wiki revision id; provenance only, unused at runtime
    List<AcquirableEntry> entries;
}
