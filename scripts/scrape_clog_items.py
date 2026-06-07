"""
Build clog_items.json — the canonical "every collection-log item with its wiki page"
table the plugin uses for ID-driven lookup.

Joins two upstream sources:
  - Module:Collection_log/data.json (the clog item list: id, name, tabs)
  - Bucket:Item_id                 (each in-game id -> page_name_sub on the wiki)

Output schema (one row per clog item, preserving the upstream ordering):
  [
    { "id": 32388, "name": "Medallion fragment", "tabs": ["Sea Treasures"],
      "wiki_page": "Medallion fragment#1" },
    ...
  ]

For IDs the wiki hasn't catalogued in Bucket:Item_id, `wiki_page` falls back to the
clog `name` so the plugin still has a usable lookup key.

Run:
  pip install requests
  python scripts/scrape_clog_items.py --out src/main/resources/com/dropratesclog/clog_items.json
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import requests

BASE    = "https://oldschool.runescape.wiki"
API     = f"{BASE}/api.php"
HEADERS = {"User-Agent": "DropRatesClog/1.0 (RuneLite plugin; contact via GitHub)"}

# Bucket caps: 5000 rows per call, 2 seconds per query. 100k offset cap is a safety
# net far above the actual table size (~10k rows today).
BUCKET_LIMIT      = 5000
BUCKET_MAX_OFFSET = 100_000


def fetch_clog_data() -> list[dict]:
    """The wiki's canonical clog item list (id, name, tabs[])."""
    url = f"{BASE}/w/Module:Collection_log/data.json"
    resp = requests.get(url, params={"action": "raw"}, headers=HEADERS, timeout=60)
    resp.raise_for_status()
    return resp.json()


def fetch_id_to_page() -> dict[str, str]:
    """Walk Bucket:Item_id and return {id_str -> page_name_sub}."""
    out: dict[str, str] = {}
    offset = 0
    while offset < BUCKET_MAX_OFFSET:
        query = (
            f"bucket('item_id').select('page_name_sub','id')"
            f".limit({BUCKET_LIMIT}).offset({offset}).run()"
        )
        resp = requests.get(API, params={
            "action": "bucket", "query": query, "format": "json",
        }, headers=HEADERS, timeout=60)
        resp.raise_for_status()
        rows = resp.json().get("bucket", [])
        if not rows:
            break
        for row in rows:
            sub = row.get("page_name_sub")
            ids = row.get("id") or []
            if not sub:
                continue
            for idv in ids:
                out[str(idv)] = sub
        if len(rows) < BUCKET_LIMIT:
            break
        offset += BUCKET_LIMIT
    return out


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--out", required=True, help="Output JSON path")
    args = p.parse_args()

    print("Fetching Module:Collection_log/data.json...")
    clog = fetch_clog_data()
    print(f"  {len(clog)} clog items")

    print("Walking Bucket:Item_id...")
    id_to_page = fetch_id_to_page()
    print(f"  {len(id_to_page)} ID -> page mappings")

    rows: list[dict] = []
    unresolved = 0
    for entry in clog:
        idv  = entry["id"]
        name = entry.get("name", "")
        tabs = entry.get("tabs", [])
        page = id_to_page.get(str(idv))
        if not page:
            page = name
            unresolved += 1
        rows.append({"id": idv, "name": name, "tabs": tabs, "wiki_page": page})

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("w", encoding="utf-8") as f:
        json.dump(rows, f, indent=2, ensure_ascii=False)

    print(f"\nWrote {len(rows)} rows to {out_path}")
    if unresolved:
        print(f"  {unresolved} IDs had no item_id bucket entry "
              f"(wiki_page fell back to the clog name)")


if __name__ == "__main__":
    main()
