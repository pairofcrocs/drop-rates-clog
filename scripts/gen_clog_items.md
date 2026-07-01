# Generating `clog_items.json` from the game cache

`gen_clog_items.py` builds `scripts/clog_items.json` directly from the OSRS game
cache instead of scraping the wiki's hand-maintained collection-log list. The cache
is the authoritative source — it updates the moment Jagex ships content — so new
collection-log items appear within a day of a game update rather than whenever a
wiki editor gets to them.

It replaces `scrape_clog_items.py`. The downstream `scrape.py` (drop rates) is
unchanged; it still reads `clog_items.json`.

## How it works

1. Finds the newest live OSRS cache on the [OpenRS2 archive](https://archive.openrs2.org)
   and fetches **only** the config groups it needs (enum / obj / struct) — a few MB,
   not the whole cache.
2. Walks the collection-log tree the game defines in the cache:

   ```
   enum 2102   → 5 tab structs (Bosses, Raids, Clues, Minigames, Other)
   tab struct  → param 682 = tab name, param 683 = page-list enum
   page-list   → ordered page struct ids
   page struct → param 689 = page name, param 690 = item-id enum
   item enum   → ordered item ids
   ```

   Item names come from the item definitions (obj, opcode 2). `tabs` is the list of
   collection-log pages an item appears on.
3. Resolves each item's `wiki_page` from its id via the wiki's `Special:Lookup`
   redirect (`?type=item&id=<id>`), which preserves `#anchor`s. It reuses the pages
   already in `clog_items.json`, so it only hits the wiki for genuinely new ids.
4. Writes `clog_items.json`: sorted by id, 2-space indent, keys `id/name/tabs/wiki_page`.

Pure standard library — no pip dependencies.

## Usage

```sh
python scripts/gen_clog_items.py \
    --out       scripts/clog_items.json \
    --prev      scripts/clog_items.json \
    --overrides scripts/clog_wiki_overrides.json
```

`--cache-id N` forces a specific OpenRS2 cache; otherwise the newest is used.

## Item-id caveat (`clog_wiki_overrides.json` and review)

Some collection-log items exist in the cache under **two** ids: the real in-game
item (which carries a bank `placeholderId`) and a stripped "display duplicate" Jagex
created for the log UI. The cache's collection-log enum references the **real** item,
so that is what this generator emits. Historically the wiki list used some display
duplicates instead. If the plugin needs a specific id for a given item, pin it:

```json
{ "25617": "Tea flask" }
```

`clog_wiki_overrides.json` maps `itemId → wiki_page` and always wins over both the
cache and the previous file. (It only overrides `wiki_page`; to force a different
*item id* into the log, that belongs in the plugin's hover mapping, not here.)

This is why the CI workflow opens a **pull request** rather than committing to
`master`: the first rebuild may swap a handful of ids, and that deserves a glance.
