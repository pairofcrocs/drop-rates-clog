# Clog Drop Rates

A RuneLite plugin that shows drop rates and acquisition methods in a tooltip when hovering over items in the Collection Log.

![Screenshot](screenshot.png)

## Features

- Hover any item in the Collection Log to see its drop rate
- Each rate group lists the sources that share that rate
- Sources with more than 3 entries are collapsed with a "+N more" indicator
- Multiple rate groups per item are shown when sources differ
- An "Acquirable Items" section shows how items can be obtained outside of drops — shop costs, minigame-reward currencies, and requirements (enabled by default)
- For skilling pets, a "Skilling Pet" section shows your real per-action chance at your current level for each activity, best chance first (enabled by default)
- Optionally display drop rates as a percentage (e.g. 1/200 → 0.5%) instead of a ratio
- Optionally reduce awkward drop rate fractions to "1 in N" (e.g. 90/18,014 → ~1/200, 2 × 1/33 → ~1/17)
- Configurable cap on how many rate groups to display

## Configuration

**Drop Rates**

| Setting | Default | Description |
|---|---|---|
| Show Drop Rates | on | Show the Drop Rate section |
| Show as percentage | off | Show drop rates as a percentage (e.g. 1/200 → 0.5%) instead of a ratio; text rates like "Very rare" are left as-is |
| Reduce fractions | on | Normalise awkward fractions to "1/N" (e.g. 90/18,014 → ~1/200, 2 × 1/33 → ~1/17); ignored when percentage is on |

**Skilling Pet Chances**

| Setting | Default | Description |
|---|---|---|
| Show Skilling Pet Chances | on | For skilling pets, show your real per-action chance at your current level for each activity (best chance first) |
| Show popular methods only | off | Filter to popular training methods (from the wiki training guides) |

**Acquirable Items**

| Setting | Default | Description |
|---|---|---|
| Show Acquirable Items | on | Show the "Acquirable Items" section (shop costs, minigame-reward currencies, requirements) |

**General**

| Setting | Default | Description |
|---|---|---|
| Max sources shown | 7 | Maximum rate groups shown per section (0 = unlimited) |
| Hide "Check" popup | on | Hide the cursor's "Check &lt;item&gt;" indicator on collection-log items |
| Hide tooltip if obtained | off | Hide the tooltip for items you already own |

**UI Colors**

| Setting | Default | Description |
|---|---|---|
| Header color | white | Section label colour, e.g. "Drop Rate:" |
| Item name color | orange (#FFA21E) | The hovered item's name in the header |
| Rate / detail color | yellow (#FFFF00) | The rate / cost / requirement value before each source |
| Source color | white | Source / monster / activity names |

---

IGN: Two Crocs
