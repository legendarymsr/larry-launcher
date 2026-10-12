#!/usr/bin/env bash
# Renders art/icons/*.svg to the 256px PNGs the launcher installs into the guest
# (the drawer only loads PNG icons), then the Play Store icon from
# art/app-icon.svg. Needs rsvg-convert and ImageMagick (brew install librsvg imagemagick).
set -euo pipefail
cd "$(dirname "$0")/.."
out=app/src/main/assets/icons
mkdir -p "$out"
for svg in art/icons/*.svg; do
    rsvg-convert -w 256 -h 256 "$svg" -o "$out/$(basename "$svg" .svg).png"
done
rsvg-convert -w 512 -h 512 art/app-icon.svg | magick - -background '#16161e' -flatten -alpha off \
    fastlane/metadata/android/en-US/images/icon.png
