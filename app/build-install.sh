#!/bin/zsh
# Buduje Szept i instaluje na iPhonie Adama (profil darmowego konta Apple wazny 7 dni; po aktywacji Apple Developer -> TestFlight).
set -e; cd "$(dirname "$0")"; export PATH=/opt/homebrew/bin:$PATH
UDID=${1:-163FA4C3-2260-5F19-B639-237CF2661794}
xcodegen generate >/dev/null
xcodebuild -scheme Szept -destination 'generic/platform=iOS' -allowProvisioningUpdates -derivedDataPath .build-device build 2>&1 | grep -E "error:|BUILD" | tail -20
APP=.build-device/Build/Products/Debug-iphoneos/Szept.app
security cms -D -i "$APP/embedded.mobileprovision" | plutil -extract ExpirationDate raw - | sed 's/^/profil wazny do: /'
xcrun devicectl device install app --device "$UDID" "$APP"
