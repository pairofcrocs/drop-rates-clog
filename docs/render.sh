#!/usr/bin/env bash
# Rebuilds the README images (showcase.png and docs/settings/*.png): renders real plugin
# tooltips from the live data branch with RuneLite's TooltipComponent (see tooltips.txt in
# docs/showcase and docs/settings), then screenshots the HTML pages with headless Chrome.
# Usage: docs/render.sh   (CHROME=/path/to/chrome to override)
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/docs
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
DATA_URL=https://raw.githubusercontent.com/pairofcrocs/drop-rates-clog/data
ICON_URL=https://static.runelite.net/cache/item/icon

rm -rf "$OUT" && mkdir -p "$OUT/data" "$OUT/img"
for f in drop_rates slayer_rates buyable skilling_pets popular_methods; do
  curl -sfo "$OUT/data/$f.json" "$DATA_URL/$f.json"
done

sh ./gradlew -q showcase \
  -PshowcaseArgs="$PWD/$OUT/data|$PWD/$OUT|$PWD/docs/showcase/tooltips.txt|$PWD/docs/settings/tooltips.txt"

cp docs/showcase/showcase.html docs/settings/settings.html "$OUT/"
for id in $(grep -ohE 'img/[0-9]+\.png|item: [0-9]+' "$OUT"/*.html | grep -oE '[0-9]+' | sort -u); do
  curl -sfo "$OUT/img/$id.png" "$ICON_URL/$id.png"
done

chrome() {
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files \
    --user-data-dir="$PWD/$OUT/chrome" "$@" 2>/dev/null
}
shot() { chrome --force-device-scale-factor=2 --window-size="$2" --screenshot="$PWD/$3" "file://$PWD/$OUT/$1"; }

shot showcase.html 800,512 showcase.png
for key in $(grep -oE '^  [A-Za-z]+: +\{' docs/settings/settings.html | grep -oE '[A-Za-z]+'); do
  # settings.html reports its rendered height in <title>; size the screenshot to fit.
  h=$(chrome --virtual-time-budget=3000 --dump-dom "file://$PWD/$OUT/settings.html#$key" \
    | grep -oE '<title>[0-9]+</title>' | grep -oE '[0-9]+')
  shot "settings.html#$key" "760,$h" "docs/settings/$key.png"
done
echo "wrote showcase.png and docs/settings/*.png"
