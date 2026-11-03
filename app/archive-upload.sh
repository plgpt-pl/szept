#!/bin/zsh
# Archiwizuje Szept (Release) i wysyła do App Store Connect / TestFlight.
# Wymaga: rekord apki „Szept – prywatny czat” (6824586767) w ASC,
#         certyfikat „Apple Distribution: Adam Dabrowski” w pęku kluczy, profil „Szept App Store” (utworzony 19.10 przez API),
#         klucz ASC w ~/.config/appstoreconnect/env (ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH).
# Użycie: ./archive-upload.sh            (archiwum + wysyłka)
#         ./archive-upload.sh --tylko-archiwum
set -euo pipefail; cd "$(dirname "$0")"; export PATH=/opt/homebrew/bin:$PATH
set -a; source ~/.config/appstoreconnect/env; set +a
xcodegen generate >/dev/null
ARCH=.build-archive/Szept.xcarchive
echo "== archiwum (Release, podpis automatyczny; dystrybucyjny nadaje eksport wg ExportOptions.plist – ręczny podpis psuje target LibSignalClient)"
xcodebuild -scheme Szept -configuration Release -destination 'generic/platform=iOS' \
  -archivePath "$ARCH" -derivedDataPath .build-archive \
  archive 2>&1 | grep -E "error:|warning: .*Swift|ARCHIVE" | tail -20
plutil -extract ApplicationProperties.CFBundleShortVersionString raw "$ARCH/Info.plist" | sed 's/^/wersja: /'
plutil -extract ApplicationProperties.CFBundleVersion raw "$ARCH/Info.plist" | sed 's/^/build:  /'
[ "${1:-}" = "--tylko-archiwum" ] && { echo "archiwum: $ARCH"; exit 0; }
echo "== wysyłka do App Store Connect"
xcodebuild -exportArchive -archivePath "$ARCH" -exportOptionsPlist ExportOptions.plist -exportPath .build-archive/export \
  -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
  -allowProvisioningUpdates 2>&1 | grep -E "error:|Upload|EXPORT|succeeded" | tail -10
echo "Gotowe. Build pojawi się w ASC → TestFlight po przetworzeniu (5–15 min). Deklaracja szyfrowania: klikana w ASC przy każdym buildzie (docs/PLAN.md)."
