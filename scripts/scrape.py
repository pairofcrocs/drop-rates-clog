"""
Builds drop_rates.json for the Drop Rates Collection Log RuneLite plugin.

Single data source: the OSRS wiki's `dropsline` bucket (monster / skilling drops
that carry a numeric rarity). A rate is kept only if it is a fraction (e.g.
"1/512", "3 × 1/128", "90/18,014") or the word "always" — text rarities like
"Very rare", "Unknown", "Rare", "Once", "Varies" are dropped, as is any shop /
reward-currency data, which is covered by the curated Acquirable Items pipeline
(see scripts/scrape_acquisition.py → buyable.json).

Run:
  pip install requests
  python scripts/scrape.py --out src/main/resources/com/dropratesclog/drop_rates.json
"""

from __future__ import annotations

import argparse
import html
import json
import re
import time
from collections import defaultdict
from pathlib import Path

import requests

BASE          = "https://oldschool.runescape.wiki"
API           = f"{BASE}/api.php"
HEADERS       = {"User-Agent": "DropRatesClog/1.0 (RuneLite plugin; contact via GitHub)"}
REQUEST_DELAY = 0.3


_ROWID_TD_RX   = re.compile(r'<td\b[^>]*\bdata-rowid="[^"]*"[^>]*>(.*?)</td>', re.DOTALL)
_LINK_TITLE_RX = re.compile(r'<a\b[^>]*\btitle="([^"]+)"')


def get_clog_items() -> list[str]:
    """Parse the rendered Collection_log page and extract item names from every
    <td data-rowid="..."> cell. Each cell wraps a single clog item. Items appearing
    in multiple log categories share a name — dedupe."""
    print("Fetching collection log item list via parse API...")
    params = {
        "action": "parse",
        "page":   "Collection_log",
        "prop":   "text",
        "format": "json",
    }
    resp = requests.get(API, params=params, headers=HEADERS, timeout=60)
    resp.raise_for_status()
    page_html = resp.json().get("parse", {}).get("text", {}).get("*", "")

    names: set[str] = set()
    for cell in _ROWID_TD_RX.finditer(page_html):
        m = _LINK_TITLE_RX.search(cell.group(1))
        if m:
            # title attributes are HTML-entity-encoded (e.g. "Ahrim&#39;s hood").
            names.add(html.unescape(m.group(1)))

    print(f"  Found {len(names)} unique items across data-rowid cells")
    return sorted(names)


def dropsline_query(item_name: str) -> list[dict]:
    """Query the wiki `dropsline` bucket for every drop row of one item."""
    escaped = item_name.replace('"', '\\"')
    lua = (
        f'bucket("dropsline").select("drop_json")'
        f'.where("item_name","{escaped}").limit(500).run()'
    )
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


# Items often appear in dropsline under a variant-suffixed name
# (e.g. "Ring of endurance (uncharged)" or "Prospector jacket#Motherlode Mine").
# The wikitext template that renders the drop table names the right key — scrape it.
_DROP_SOURCES_RX = re.compile(
    r"\{\{\s*Drop sources\s*\|\s*([^|}\n]+?)\s*(?:\||\}\})",
    re.IGNORECASE,
)


def find_drop_template_alts(item_name: str) -> list[str]:
    """Return any {{Drop sources|X}} keys mentioned in `item_name`'s wikitext."""
    params = {
        "action":    "parse",
        "page":      item_name,
        "prop":      "wikitext",
        "format":    "json",
        "redirects": 1,
    }
    try:
        resp = requests.get(API, params=params, headers=HEADERS, timeout=30)
        resp.raise_for_status()
    except Exception:
        return []
    wikitext = resp.json().get("parse", {}).get("wikitext", {}).get("*", "")
    return [m.group(1).strip() for m in _DROP_SOURCES_RX.finditer(wikitext)]


# --- rate parsing & validation -------------------------------------------------------

_MULTI_ROLL_RX = re.compile(r"^(\d+)\s*[×x]\s*(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)$")
_SIMPLE_RATE_RX = re.compile(r"^(\d+(?:\.\d+)?)/(\d+(?:\.\d+)?)$")


def rate_key(drop: dict) -> str:
    """Build the human-readable rate string from a dropsline row (e.g. "1/512" or
    "3 × 1/512"). Returns empty if the row has no rarity."""
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


def is_drop_rate(rate: str) -> bool:
    """A rate is kept only if it's a fraction or the word "always". Drops text rarities
    like "Very rare", "Unknown", "Rare", "Once", "Varies"."""
    if not rate:
        return False
    s = rate.replace(",", "").strip().lower()
    if s in ("always", "1/1"):
        return True
    if _MULTI_ROLL_RX.match(s):
        return True
    if _SIMPLE_RATE_RX.match(s):
        return True
    return False


def rate_to_float(rate: str) -> float | None:
    """Parse a kept rate to a probability for sorting; returns None if non-numeric."""
    s = rate.replace(",", "").strip().lower()
    if s in ("always", "1/1"):
        return 1.0
    m = _MULTI_ROLL_RX.match(s)
    if m:
        return int(m.group(1)) * float(m.group(2)) / float(m.group(3))
    m = _SIMPLE_RATE_RX.match(s)
    if m:
        return float(m.group(1)) / float(m.group(2))
    return None


def clean_source(dropped_from: str) -> str:
    if "#" in dropped_from:
        base, variant = dropped_from.split("#", 1)
        return f"{base} ({variant.replace('_', ' ')})"
    return dropped_from


def group_drops(drops: list[dict]) -> list[dict]:
    """Group dropsline rows by (rate, approx), keeping only fraction/always rates and
    sorting by best chance first."""
    groups: dict[tuple, list[str]] = defaultdict(list)

    for drop in drops:
        key    = rate_key(drop)
        if not is_drop_rate(key):
            continue
        approx = bool(drop.get("Approx", False))
        source = clean_source((drop.get("Dropped from") or "").strip())
        if source:
            groups[(key, approx)].append(source)

    def sort_key(item):
        (rate, approx), _ = item
        val = rate_to_float(rate)
        return (val is None, -(val or 0))

    result = []
    for (rate, approx), sources in sorted(groups.items(), key=sort_key):
        result.append({"kind": "drop", "rate": rate, "approx": approx, "sources": sources})
    return result


def normalize_item_key(item: str) -> str:
    return re.sub(r"\s*\(pet\)$", "", item, flags=re.IGNORECASE)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True, help="Output JSON path")
    args = parser.parse_args()

    items = get_clog_items()

    drop_rates: dict[str, list] = {}
    no_data: list[str] = []
    total_rows = total_kept = 0

    for i, item in enumerate(items, 1):
        print(f"[{i:>3}/{len(items)}] {item}")
        entries: list[dict] = []

        try:
            drops = dropsline_query(item)
        except Exception as exc:
            print(f"         !! dropsline: {exc}")
            drops = []
        total_rows += len(drops)
        kept_groups = group_drops(drops)
        entries.extend(kept_groups)

        # Fallback: empty result usually means the bucket keys this item under a
        # variant-suffixed name (e.g. "Prospector jacket#Motherlode Mine"). The
        # {{Drop sources|...}} invocations in the article spell out the right keys.
        if not entries:
            alts = find_drop_template_alts(item)
            for alt in alts:
                if alt == item:
                    continue
                try:
                    entries.extend(group_drops(dropsline_query(alt)))
                except Exception as exc:
                    print(f"         !! dropsline alt '{alt}': {exc}")
                time.sleep(REQUEST_DELAY)
            if entries:
                print(f"         ↳ recovered via templates: {alts}")

        if entries:
            drop_rates[normalize_item_key(item)] = entries
            total_kept += sum(len(g["sources"]) for g in entries)
        else:
            no_data.append(item)

        time.sleep(REQUEST_DELAY)

    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(drop_rates, f, indent=2, ensure_ascii=False, sort_keys=True)

    # Dev sidecar: full clog item list (incl. ones with no kept data). Used by
    # scripts/preview.html to flag missing items. Not bundled into the plugin JAR.
    sidecar_path = Path(__file__).resolve().parent / "clog_items.json"
    with open(sidecar_path, "w", encoding="utf-8") as f:
        json.dump(items, f, indent=2, ensure_ascii=False)

    print(f"\nWrote {len(drop_rates)} items to {args.out}")
    print(f"Kept {total_kept} source rows across all items "
          f"(scraped {total_rows} raw rows from dropsline)")
    print(f"Wrote {len(items)} clog item names to {sidecar_path}")
    if no_data:
        print(f"No drop-rate data for {len(no_data)} items "
              f"(shop / currency / text-rarity-only items now live in buyable.json)")


if __name__ == "__main__":
    main()
