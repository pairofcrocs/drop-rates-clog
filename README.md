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

| Setting | Default | Description |
|---|---|---|
| Max sources shown | 5 | Maximum rate groups shown per section (0 = unlimited) |
| Show virtual currencies | on | Include items bought with virtual currencies (e.g. golden nuggets, pieces of eight) in the drop rate section |
| Show Drop Rate at Percentage | off | Convert drop rate ratios (e.g. 1/200) to a percentage (e.g. 0.5%); text rates like "Very rare" are left as-is |
| Reduce fractions | off | Normalise awkward fractions to "1 in N" (e.g. 90/18,014 → ~1/200, 2 × 1/33 → ~1/17); ignored when percentage is on |
| Show acquirable items | on | Show the "Acquirable Items" section (shop costs, minigame rewards, requirements) below the drop rate |
| Show skilling pet chance | on | For skilling pets, show your real per-action chance at your current level for each activity (best chance first) |
