#!/bin/zsh
# Kopiuje podpisany APK na stronę i aktualizuje sumę SHA-256 oraz wersję (strona czyta je z plików).
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK=${1:-$ROOT/android/app/build/outputs/apk/release/app-release.apk}
AAPT=$(ls -d ~/Library/Android/sdk/build-tools/*/aapt2 | tail -1)
VER=$($AAPT dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
cp "$APK" "$ROOT/server/static/szept.apk"
shasum -a 256 "$ROOT/server/static/szept.apk" | cut -d' ' -f1 > "$ROOT/server/static/szept.apk.sha256"
echo "$VER" > "$ROOT/server/static/szept.apk.version"
echo "APK $VER, SHA-256 $(cat $ROOT/server/static/szept.apk.sha256)"
