"""
Builds drop_rates.json for the Drop Rates Collection Log RuneLite plugin.

Uses the OSRS wiki's Bucket API (Module:Get drop info uses dropsline bucket):
  bucket("dropsline").select("drop_json").where("item_name", <item>).run()

Run:
  pip install requests
  python scripts/scrape.py --out src/main/resources/com/dropratesclog/drop_rates.json
"""

import argparse
import json
import re
import time
from collections import defaultdict

import requests

BASE          = "https://oldschool.runescape.wiki"
API           = f"{BASE}/api.php"
HEADERS       = {"User-Agent": "DropRatesClog/1.0 (RuneLite plugin; contact via GitHub)"}
REQUEST_DELAY = 0.3


def get_clog_items() -> list[str]:
    print("Fetching collection log item list via API...")
    names: list[str] = []
    params = {
        "action":      "query",
        "prop":        "links",
        "titles":      "Collection_log/Table",
        "pllimit":     500,
        "plnamespace": 0,
        "format":      "json",
        "formatversion": 2,
    }

    while True:
        resp = requests.get(API, params=params, headers=HEADERS, timeout=30)
        resp.raise_for_status()
        data = resp.json()

        pages = data.get("query", {}).get("pages", [])
        for page in pages:
            for link in page.get("links", []):
                names.append(link["title"])

        if "continue" not in data:
            break
        params.update(data["continue"])

    print(f"  Found {len(names)} items")
    return names


def bucket_query(item_name: str) -> list[dict]:
    lua = f'bucket("dropsline").select("drop_json").where("item_name","{item_name}").limit(500).run()'
    params = {"action": "bucket", "query": lua, "format": "json"}
    resp = requests.get(API, params=params, headers=HEADERS, timeout=30)
    resp.raise_for_status()
    data = resp.json()

    if "error" in data:
        return []

    result = []
    for row in data.get("bucket", []):
        dj = row.get("drop_json")
        if isinstance(dj, dict):
            result.append(dj)
        elif isinstance(dj, str):
            try:
                result.append(json.loads(dj))
            except json.JSONDecodeError:
                pass
    return result


def rate_key(drop: dict) -> str:
    rarity = (drop.get("Rarity") or "").strip()
    if not rarity:
        return ""
    rolls = 1
    try:
        rolls = int(drop.get("Rolls", 1))
    except (TypeError, ValueError):
        pass
    if rolls > 1:
        return f"{rolls} × {rarity}"
    return rarity


def rate_to_float(rate: str) -> float | None:
    s = rate.strip().lower().replace(",", "")
    if s in ("always", "1/1"):
        return 1.0

    m = re.match(r"(\d+)\s*[×x]\s*(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)", s)
    if m:
        return int(m.group(1)) * float(m.group(2)) / float(m.group(3))

    m = re.match(r"(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)", s)
    if m:
        return float(m.group(1)) / float(m.group(2))

    return None


def clean_source(dropped_from: str) -> str:
    if "#" in dropped_from:
        base, variant = dropped_from.split("#", 1)
        return f"{base} ({variant.replace('_', ' ')})"
    return dropped_from


def group_drops(drops: list[dict]) -> list[dict]:
    groups: dict[tuple, list[str]] = defaultdict(list)

    for drop in drops:
        key    = rate_key(drop)
        approx = bool(drop.get("Approx", False))
        source = clean_source((drop.get("Dropped from") or "").strip())
        if key and source:
            groups[(key, approx)].append(source)

    def sort_key(item):
        (rate, approx), _ = item
        val = rate_to_float(rate)
        return (val is None, -(val or 0))

    result = []
    for (rate, approx), sources in sorted(groups.items(), key=sort_key):
        result.append({"rate": rate, "approx": approx, "sources": sources})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="Output JSON path")
    args = parser.parse_args()

    items = get_clog_items()

    drop_rates: dict[str, list] = {}
    no_data: list[str] = []

    for i, item in enumerate(items, 1):
        print(f"[{i:>3}/{len(items)}] {item}")
        try:
            drops = bucket_query(item)
        except Exception as exc:
            print(f"         !! {exc}")
            no_data.append(item)
            time.sleep(REQUEST_DELAY)
            continue

        if not drops:
            no_data.append(item)
            time.sleep(REQUEST_DELAY)
            continue

        grouped = group_drops(drops)
        if grouped:
            key = re.sub(r"\s*\(pet\)$", "", item, flags=re.IGNORECASE)
            drop_rates[key] = grouped

        time.sleep(REQUEST_DELAY)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(drop_rates, f, indent=2, ensure_ascii=False, sort_keys=True)

    print(f"\nWrote {len(drop_rates)} items to {args.out}")
    if no_data:
        print(f"No drop data for {len(no_data)} items")


if __name__ == "__main__":
    main()
