# Data branch

This orphan branch hosts the JSON datasets the **Clog Drop Rates** RuneLite
plugin fetches at start-up. It deliberately contains no code or build files —
just the data, at the branch root, so each file is reachable at a stable raw
URL:

```
https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/data/<file>.json
```

| File | Contents |
|---|---|
| `drop_rates.json` | Drop rates per collection-log item |
| `buyable.json` | Acquirable items (shop costs, minigame-reward currencies, requirements) |
| `skilling_pets.json` | Skilling-pet per-action chances by activity |
| `item_aliases.json` | Item ID → canonical name, to disambiguate items that share an in-game name |
| `popular_methods.json` | Skill → popular training methods, for the "popular only" filter |

## How it's used

On start-up the plugin loads the copies bundled in its jar (so it works
instantly and offline), then asynchronously fetches these files to pick up any
updates. If a fetch fails, the bundled copy is kept. This means data refreshes
reach users without a plugin rebuild or plugin-hub redistribution.

## How it's updated

`drop_rates.json` is refreshed automatically by the weekly `Update drop rates`
GitHub Action on `master`, which scrapes the OSRS wiki, sanity-checks the
result, and commits it here. The other files are updated manually.

**Do not** add code, history, or unrelated files to this branch.
