#!/usr/bin/env python3
"""
propose.py — write a buyable/guideline proposal to a local checkout of the
`suggestions` branch (see the container's PROPOSALS.md). The review routine calls
this after researching a candidate, then commits + pushes the edited proposals.json.

Why a helper instead of hand-writing JSON: it computes `valueHash` with the EXACT
algorithm the container uses, and suppresses a proposal whose value was already
rejected — so the routine can't accidentally re-propose a rejected value or drift
the hash. Pure local file edits; git is the routine's job.

  # a brand-new buyable entry
  python scripts/propose.py --dir review buyable \
      --item-id 29472 --item-name "Prospector helmet" --change new \
      --entries '[{"approx":false,"cost":"26,000 Volcanic Mine reward points","description":null,"kind":"currency","rate":null,"sources":["Petrified Pete'\''s Ore Shop (Volcanic Mine)"]}]' \
      --revid 15211390 \
      --rationale "Volcanic Mine reward-shop version; separate id from the Motherlode pieces, absent from buyable.json." \
      --source https://oldschool.runescape.wiki/w/Prospector_helmet

  # a guideline promotion
  python scripts/propose.py --dir review guideline \
      --text "Points-shop items: use the reward-point cost, never a gp/GE price." \
      --rationale "Two rejections cited a GE price where a points cost was correct." \
      --from 29472 --from 12013

--dir is the directory holding proposals.json / rejections.json (a checkout of the
suggestions branch). Files are created if missing.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
import re
import sys


def utcnow_iso():
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def value_hash(proposed):
    """MUST match server.py's value_hash: sha1 over the entries (revid excluded)."""
    entries = proposed.get("entries", []) if isinstance(proposed, dict) else []
    blob = json.dumps(entries, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha1(blob.encode("utf-8")).hexdigest()


def load(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return default


def dump(path, obj):
    with open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:48] or "rule"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dir", default=".", help="dir with proposals.json / rejections.json (suggestions checkout)")
    sub = ap.add_subparsers(dest="kind", required=True)

    b = sub.add_parser("buyable", help="propose a new/updated buyable entry")
    b.add_argument("--item-id", type=int, required=True)
    b.add_argument("--item-name", required=True)
    b.add_argument("--change", choices=["new", "update"], required=True)
    b.add_argument("--entries", required=True, help="JSON array of AcquirableEntry objects")
    b.add_argument("--revid", type=int, default=None, help="wiki revision the data was read from")
    b.add_argument("--current", default=None, help="JSON of the existing buyable value (required for --change update)")
    b.add_argument("--rationale", required=True)
    b.add_argument("--source", action="append", default=[], help="wiki URL (repeatable)")

    g = sub.add_parser("guideline", help="propose a guideline rule")
    g.add_argument("--text", required=True)
    g.add_argument("--rationale", required=True)
    g.add_argument("--from", dest="from_ids", action="append", default=[], type=int,
                   help="itemId whose rejection motivated this rule (repeatable)")

    args = ap.parse_args()
    ppath = os.path.join(args.dir, "proposals.json")
    rpath = os.path.join(args.dir, "rejections.json")
    proposals = load(ppath, {})

    if args.kind == "buyable":
        try:
            entries = json.loads(args.entries)
        except json.JSONDecodeError as e:
            sys.exit(f"--entries is not valid JSON: {e}")
        if not isinstance(entries, list) or not entries:
            sys.exit("--entries must be a non-empty JSON array")
        if args.change == "update" and not args.current:
            sys.exit("--change update requires --current (the existing value, for the diff)")

        proposed = {"entries": entries, "revid": args.revid}
        vh = value_hash(proposed)

        # Suppress: this exact value was already rejected for this item.
        rejections = load(rpath, [])
        for rej in rejections:
            if str(rej.get("itemId")) == str(args.item_id) and rej.get("valueHash") == vh:
                print(f"suppressed: buyable:{args.item_id} matches a prior rejection "
                      f"({rej.get('reason') or 'no reason given'})")
                return

        pid = f"buyable:{args.item_id}"
        proposals[pid] = {
            "id": pid,
            "kind": "buyable",
            "change": args.change,
            "itemId": args.item_id,
            "itemName": args.item_name,
            "proposed": proposed,
            "current": json.loads(args.current) if args.current else None,
            "rationale": args.rationale,
            "sources": args.source,
            "valueHash": vh,
            "createdBy": "routine:buyable-audit",
            "createdAt": utcnow_iso(),
        }
        dump(ppath, proposals)
        print(f"wrote {pid} ({args.change}) -> {ppath}")

    else:  # guideline
        pid = "guideline:" + slug(args.text)
        proposals[pid] = {
            "id": pid,
            "kind": "guideline",
            "text": args.text.strip(),
            "rationale": args.rationale,
            "fromRejections": args.from_ids,
            "createdBy": "routine:buyable-audit",
            "createdAt": utcnow_iso(),
        }
        dump(ppath, proposals)
        print(f"wrote {pid} -> {ppath}")


if __name__ == "__main__":
    main()
