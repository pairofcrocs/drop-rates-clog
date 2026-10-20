#!/usr/bin/env bash
# Rebuilds showcase.png (the README image): renders real plugin tooltips from the live data
# branch with RuneLite's TooltipComponent, then screenshots showcase.html with headless Chrome.
# Usage: docs/showcase/render.sh   (CHROME=/path/to/chrome to override)
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/showcase
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
DATA_URL=https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/data
ICON_URL=https://static.runelite.net/cache/item/icon

# Hovered item per panel: id:name:skill level (level only matters for skilling pets)
ITEMS=("11920:Dragon pickaxe:99" "24268:Basilisk jaw:99" "13226:Herb sack:99" "20665:Rift guardian:91")

rm -rf "$OUT" && mkdir -p "$OUT/data" "$OUT/img"
for f in drop_rates slayer_rates buyable skilling_pets popular_methods; do
  curl -sfo "$OUT/data/$f.json" "$DATA_URL/$f.json"
done

args="$PWD/$OUT/data|$PWD/$OUT"
for i in "${ITEMS[@]}"; do args+="|$i"; done
sh ./gradlew -q showcase -PshowcaseArgs="$args"

cp docs/showcase/showcase.html "$OUT/"
for id in $(grep -oE 'img/[0-9]+\.png' docs/showcase/showcase.html | grep -oE '[0-9]+' | sort -u); do
  curl -sfo "$OUT/img/$id.png" "$ICON_URL/$id.png"
done

"$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
  --force-device-scale-factor=2 --window-size=800,512 --user-data-dir="$PWD/$OUT/chrome" \
  --screenshot="$PWD/showcase.png" "file://$PWD/$OUT/showcase.html" 2>/dev/null
echo "wrote showcase.png"
