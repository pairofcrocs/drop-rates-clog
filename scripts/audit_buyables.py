#!/usr/bin/env python3
"""
audit_buyables.py — deterministic candidate finder for the buyable review pipeline.

Emits candidates.json (NO LLM) — the items the cloud routine should research and turn
into proposals (see the container's PROPOSALS.md). Two kinds:

  missing  — a collection-log item that has a shop/currency source on the wiki but no
             entry in buyable.json.
  changed  — an existing buyable.json item whose wiki store cost/currency no longer
             matches what's stored. Re-checked on a rotating cursor, --window per run,
             so the whole file is swept over many nights without re-doing it all nightly.

Store data is the wiki's `storeline` bucket. The player-facing cost is

    effective = round(store_buy_price * 1000 / store_buy_multiplier)   (mult defaults 1000)

— the multiplier MUST be applied or every nugget/points shop looks "changed" (a nugget
helmet is buy_price 32, multiplier 800 -> 40, which is what buyable.json stores).

This script never asserts a final entry. It attaches the raw store rows and the item's
prior rejections so Claude can adjudicate multipliers, currency wording, descriptions and
multi-source items. Deterministic flagging in; human-readable proposals out.

stdlib only. Inputs may be local paths or http(s) URLs (e.g. raw.githubusercontent.com).

  python audit_buyables.py \
    --clog-items https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/master/scripts/clog_items.json \
    --buyable    https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/data/buyable.json \
    --rejections https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/suggestions/rejections.json \
    --cursor     audit_cursor.json --window 25 --out candidates.json
"""
from __future__ import annotations

import argparse
import datetime
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"
UA  = "DropRatesClog-audit/1.0 (RuneLite plugin; +github.com/pairofcrocs/drop-rates-clog)"
STORE_FIELDS = ["sold_item", "sold_by", "store_buy_price", "store_buy_multiplier",
                "store_currency", "store_stock", "store_notes"]


# ── io ────────────────────────────────────────────────────────────────────────

def load_json(src, default=None):
    """Load JSON from a local path or an http(s) URL. Returns `default` on a 404 /
    missing file (so an absent rejections.json on a fresh suggestions branch is fine)."""
    if not src:
        return default
    try:
        if src.startswith(("http://", "https://")):
            req = urllib.request.Request(src, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        with open(src, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return default
        raise


def utcnow_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# ── storeline bucket ──────────────────────────────────────────────────────────

def _bucket_page(offset, limit=500):
    sel = ",".join('"%s"' % f for f in STORE_FIELDS)
    q = 'bucket("storeline").select(%s).limit(%d).offset(%d).run()' % (sel, limit, offset)
    url = API + "?" + urllib.parse.urlencode({"action": "bucket", "format": "json", "query": q})
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = json.loads(r.read().decode("utf-8"))
    if isinstance(data, dict) and data.get("error"):
        raise RuntimeError("storeline bucket error: " + str(data["error"]))
    return data.get("bucket", [])


def fetch_storeline():
    """All storeline rows, paged via offset until a short page ends it."""
    rows, offset = [], 0
    while True:
        page = _bucket_page(offset)
        rows.extend(page)
        if len(page) < 500:
            return rows
        offset += 500
        time.sleep(0.2)


def base_name(sold_item):
    """Strip the wiki '#anchor' page-sub suffix → the in-game-ish base name. The store
    keys variant items as e.g. 'Prospector helmet#Motherlode Mine'."""
    return sold_item.split("#", 1)[0].strip() if sold_item else (sold_item or "")


def variant_label(sold_item):
    if sold_item and "#" in sold_item:
        return sold_item.split("#", 1)[1].replace("_", " ").strip()
    return None


def effective_cost(row):
    """Player-facing buy price: store_buy_price * 1000 / store_buy_multiplier."""
    try:
        price = float(str(row.get("store_buy_price", "")).replace(",", ""))
    except (TypeError, ValueError):
        return None
    try:
        mult = float(str(row.get("store_buy_multiplier", "")).replace(",", ""))
    except (TypeError, ValueError):
        mult = 0.0
    if not mult:
        mult = 1000.0
    return round(price * 1000.0 / mult)


def index_by_base(rows):
    idx = {}
    for r in rows:
        idx.setdefault(base_name(r.get("sold_item") or "").lower(), []).append(r)
    return idx


def store_summary(rows):
    """Compact, display-ready store info for the candidate payload."""
    out = []
    for r in rows:
        out.append({
            "soldBy":        r.get("sold_by"),
            "buyPrice":      r.get("store_buy_price"),
            "multiplier":    r.get("store_buy_multiplier"),
            "currency":      r.get("store_currency"),
            "effectiveCost": effective_cost(r),
            "variant":       variant_label(r.get("sold_item")),
            "stock":         r.get("store_stock"),
            "notes":         r.get("store_notes") or None,
        })
    return out


# ── change detection (heuristic; the routine verifies) ────────────────────────

_CUR_STOP = {"of", "the", "a", "points", "point"}


def norm_currency(s):
    """A currency as a set of singularized word tokens, so 'Marks of grace' and
    'Mark of grace' (and 'Golden nuggets'/'Golden nugget') compare equal."""
    words = re.findall(r"[a-z]+", (s or "").lower())
    return frozenset(w[:-1] if w.endswith("s") and len(w) > 3 else w
                     for w in words if w not in _CUR_STOP)


def parse_cost(cost):
    """('40 golden nuggets') -> (40, {'golden','nugget'})."""
    if not cost:
        return (None, frozenset())
    s = str(cost)
    m = re.search(r"([\d,]+)", s)
    num = int(m.group(1).replace(",", "")) if m else None
    return (num, norm_currency(re.sub(r"[\d,]+", "", s)))


def detect_change(stored_entries, summary):
    """Reason string for the ONE high-confidence signal: a stored entry whose currency
    overlaps a store row, but whose number differs (e.g. Foundry Reputation 4000 stored
    vs 3200 in-store). We deliberately do NOT flag "store has a currency not in stored" —
    for an existing item that's dominated by phrasing gaps, general-store sell rows, and
    recolor name-collisions, i.e. noise. A flag means 'Claude, verify this', not a value."""
    stored = [parse_cost(e.get("cost")) for e in (stored_entries or [])]
    stored = [(n, c) for (n, c) in stored if n is not None and c]
    reasons = []
    for r in summary:
        cost = r["effectiveCost"]
        cur = norm_currency(r["currency"])
        if cost is None or not cur:
            continue
        match = [s for s in stored if s[1] & cur]   # overlapping currency tokens
        if match and all(s[0] != cost for s in match):
            reasons.append(f"store cost {cost} {r['currency']} ≠ stored {match[0][0]}")
    return "; ".join(reasons) or None


# ── cursor (round-robin over existing buyable ids) ────────────────────────────

def load_cursor(path):
    if not path:
        return {"last": None}
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return {"last": None}


def save_cursor(path, cur):
    if not path:
        return
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cur, f)


def window_after(ids, last, window):
    """The next `window` ids after `last` in a sorted, wrapping ring."""
    if window <= 0 or not ids:
        return [], last
    start = 0
    if last is not None and last in ids:
        start = (ids.index(last) + 1) % len(ids)
    picked = [ids[(start + i) % len(ids)] for i in range(min(window, len(ids)))]
    return picked, picked[-1]


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--clog-items", required=True, help="path or URL to clog_items.json")
    ap.add_argument("--buyable", required=True, help="path or URL to buyable.json")
    ap.add_argument("--rejections", default=None, help="path or URL to rejections.json (optional)")
    ap.add_argument("--cursor", default=None, help="cursor state file (read+write); omit to always start fresh")
    ap.add_argument("--window", type=int, default=25, help="existing items to re-check for changes per run (0 = skip)")
    ap.add_argument("--storeline", default=None, help="cached storeline rows JSON (skip the wiki fetch; for testing)")
    ap.add_argument("--out", default=None, help="output file (default: stdout)")
    args = ap.parse_args()

    clog = load_json(args.clog_items) or []
    buyable = load_json(args.buyable) or {}
    rejections = load_json(args.rejections, default=[]) or []

    clog_by_id = {str(it["id"]): it for it in clog if "id" in it}
    # Names shared by >1 buyable id (Graceful, Decorative, …) can't be attributed to a
    # single store row, so change-detection skips them — the base-name store index would
    # compare every variant id against the same row and flag false drifts.
    name_buyable_count = {}
    for iid in buyable:
        it = clog_by_id.get(iid)
        if it:
            nm = (it.get("name") or "").lower()
            name_buyable_count[nm] = name_buyable_count.get(nm, 0) + 1
    rej_by_id = {}
    for r in rejections:
        rej_by_id.setdefault(str(r.get("itemId")), []).append(r)

    rows = load_json(args.storeline) if args.storeline else fetch_storeline()
    by_base = index_by_base(rows)

    def rows_for(item):
        return by_base.get((item.get("name") or "").lower(), [])

    # ── missing: clog items with a store row but no buyable entry ──
    missing = []
    for it in clog:
        iid = str(it.get("id"))
        if iid in buyable:
            continue
        srows = rows_for(it)
        if not srows:
            continue
        missing.append({
            "itemId": it["id"],
            "itemName": it.get("name"),
            "wikiPage": it.get("wiki_page"),
            "store": store_summary(srows),
            "priorRejections": rej_by_id.get(iid, []),
        })

    # ── changed: rotating window over existing buyable items ──
    changed = []
    cursor = load_cursor(args.cursor)
    ids = sorted(buyable.keys(), key=lambda s: (len(s), s))  # stable ring order
    picked, new_last = window_after(ids, cursor.get("last"), args.window)
    for iid in picked:
        it = clog_by_id.get(iid)
        if not it:
            continue  # buyable key with no clog item (orphan) — skip, not our job here
        if name_buyable_count.get((it.get("name") or "").lower(), 0) > 1:
            continue  # shared-name multi-id item — store row can't be attributed reliably
        srows = rows_for(it)
        if not srows:
            continue  # no store data to compare against
        summary = store_summary(srows)
        reason = detect_change(buyable[iid].get("entries"), summary)
        if reason:
            changed.append({
                "itemId": int(iid) if iid.isdigit() else iid,
                "itemName": it.get("name"),
                "reason": reason,
                "stored": buyable[iid],
                "store": summary,
                "priorRejections": rej_by_id.get(iid, []),
            })
    if args.window > 0:
        cursor["last"] = new_last
        cursor["checkedAt"] = utcnow_iso()
        save_cursor(args.cursor, cursor)

    result = {
        "generatedAt": utcnow_iso(),
        "counts": {"missing": len(missing), "changed": len(changed),
                   "windowChecked": len(picked), "storelineRows": len(rows)},
        "missing": missing,
        "changed": changed,
        "cursor": cursor,
    }
    text = json.dumps(result, indent=2, ensure_ascii=False)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text + "\n")
        print(f"wrote {args.out}: {len(missing)} missing, {len(changed)} changed "
              f"(checked {len(picked)} existing, {len(rows)} store rows)")
    else:
        print(text)


if __name__ == "__main__":
    main()
