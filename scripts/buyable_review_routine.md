# Buyable review routine

This is the prompt for the nightly cloud agent (a Claude Code routine on claude.ai). It
finds gaps and drifts in `buyable.json`, researches them against the OSRS wiki, and writes
**proposals** to the `suggestions` branch for a human to approve in the container editor.

It is the producer end of the pipeline described in the container's `PROPOSALS.md`.

---

## Hard rules

- **Write ONLY to the `suggestions` branch.** Never commit to `data` or `master`. You
  propose; the human approves in the editor, and only that commits to `data`.
- **Never approve, merge, or edit `buyable.json`.** You don't have that job.
- **Trust the human's rejections.** If `propose.py` says a value was already rejected,
  do not try to re-submit the same value — read the reason and either propose a corrected
  value or move on.
- Keep the queue digestible: at most ~**20 proposals per run** (all `changed` from this
  run's window, then fill the rest with `missing`). The backlog shrinks over nights.

## Setup

```sh
git clone https://github.com/pairofcrocs/drop-rates-clog repo && cd repo
# a worktree for the suggestions branch (create it from the seed if it's the first run)
git worktree add ../suggestions suggestions
```

The `suggestions` checkout (`../suggestions`) holds `proposals.json`, `rejections.json`,
and `buyable-guidelines.md`. Read all three before proposing.

## 1. Get candidates (deterministic — no judgment here)

```sh
python scripts/audit_buyables.py \
  --clog-items scripts/clog_items.json \
  --buyable    https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/data/buyable.json \
  --rejections ../suggestions/rejections.json \
  --cursor     ../suggestions/audit_cursor.json --window 25 \
  --out candidates.json
```

`candidates.json` has `missing` (items with a store row but no entry) and `changed`
(existing items whose stored cost no longer matches the wiki). Each candidate already
carries the raw store rows (`store[]`, with `effectiveCost` = the player-facing price)
and the item's `priorRejections`. Commit the advanced `audit_cursor.json` at the end so
the next run picks up where this one left off.

## 2. Research each candidate, then propose

Load `../suggestions/buyable-guidelines.md` and **apply every rule** — they encode past
human corrections. For each candidate:

1. Read the wiki page for the item (the store rows name `soldBy`; verify against the page,
   e.g. `WebFetch` the wiki URL or the wiki API). Confirm the `effectiveCost`, the exact
   currency name, the shop/source, and any requirement worth a one-line `description`.
2. Re-read this item's `priorRejections` — honour the reasons (don't repeat a rejected
   value or framing).
3. For a `changed` candidate, decide whether the wiki is actually right. The audit only
   flags a numeric mismatch; the curated value may be the wrong one *or* the wiki may be.
   Propose only when you're confident the stored value should change.
4. Build the entry(ies) in the exact `buyable.json` shape and call `propose.py`:

```sh
python scripts/propose.py --dir ../suggestions buyable \
  --item-id <id> --item-name "<name>" --change new|update \
  --entries '[{"approx":false,"cost":"<cost> <currency>","description":<null|"…">,"kind":"currency","rate":null,"sources":["<shop> (<location>)"]}]' \
  --revid <wiki revid> \
  --rationale "<one sentence: what you found and why it belongs>" \
  --source <wiki URL>
# for --change update, also pass --current '<the existing buyable.json value as JSON>'
```

`propose.py` computes the `valueHash`, suppresses anything matching a prior rejection, and
writes `proposals.json`. If it prints `suppressed:`, move on — that's the rejection memory
working.

Multi-source items (a drop *and* a shop, or two shops) get one entry per source in the
`entries` array. Apply the cost formula sanity check: the store's player price is
`store_buy_price × 1000 / store_buy_multiplier` — already computed as `effectiveCost`.

### Keep `cost` and `description` terse — they render in a small in-game tooltip

Space is tight in the collection-log popup, so condense **without dropping information**:

- **Factor out a shared quantity or unit.** `500 noted yew logs + 500 noted redwood logs`
  → `500 noted yew and redwood logs`. Only merge the number when the quantities are equal;
  if they differ, keep them separate (`1,500 bark + 60 noted yew logs` stays explicit).
- **Collapse a shared noun across a list.** `120 noted magic logs + 120 noted redwood logs`
  → `120 noted magic and redwood logs`; three or more → `A, B and C` with one trailing noun.
- Use `+` to join genuinely different cost parts (`1,200 bark + 200 noted yew logs`); use
  `and` only inside a merged same-quantity group.
- Keep every number, currency, and source name exact — brevity never changes a value, and a
  reader must still be able to reconstruct the full cost. Don't merge across different units
  (`bark` and `logs` stay separate parts).
- `description` is for a genuinely needed qualifier only (a requirement, an "untradeable"
  note). If it just restates the cost or source, leave it `null`.

## 3. Distill guidelines from rejections

Scan `rejections.json` for `scope:"general"` reasons that express a rule not already in
`buyable-guidelines.md`. When two or more rejections share a theme, propose a guideline:

```sh
python scripts/propose.py --dir ../suggestions guideline \
  --text "<the rule, imperative, one line>" \
  --rationale "<which rejections motivated it>" --from <id> --from <id>
```

Don't restate rules already in the guidelines file. Don't invent rules with no rejection
behind them.

## 4. Commit + push (suggestions branch only)

```sh
cd ../suggestions
git add proposals.json audit_cursor.json
git commit -m "routine: $(date -u +%F) buyable proposals (N new, M changed, K guidelines)"
git push origin suggestions
```

Then stop. The proposals now appear in the editor's Review tab for the human to approve or
reject. Report a short summary: how many proposals of each kind, and anything you
deliberately skipped (e.g. a `changed` flag where the wiki looked wrong, or a candidate a
rejection suppressed).
