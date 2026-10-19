#!/bin/zsh
# Pobiera prebuilt libsignal_ffi.a (iOS device + simulator) z oficjalnych artefaktów Signala i weryfikuje sumę SHA-256
# z wydania GitHub. Wynik: vendor/ffi/target/<triple>/release/libsignal_ffi.a oraz vendor/build/libsignal_ffi_sim.a (fat sim).
set -euo pipefail
VER=${1:-0.105.1}
cd "$(dirname "$0")"
ARCH="libsignal-client-ios-build-v${VER}.tar.gz"
SUM=$(curl -sL "https://github.com/signalapp/libsignal/releases/download/v${VER}/${ARCH}.sha256" | awk '{print $1}')
[ -n "$SUM" ] || { echo "brak sumy kontrolnej dla v$VER" >&2; exit 1; }
[ -f libsignal-ios.tar.gz ] || curl -sL -o libsignal-ios.tar.gz "https://build-artifacts.signal.org/libraries/${ARCH}"
echo "$SUM  libsignal-ios.tar.gz" | shasum -a 256 -c -
rm -rf ffi && mkdir -p ffi build && tar -xzf libsignal-ios.tar.gz -C ffi
lipo -create ffi/target/aarch64-apple-ios-sim/release/libsignal_ffi.a ffi/target/x86_64-apple-ios/release/libsignal_ffi.a -output build/libsignal_ffi_sim.a
echo "OK: $(ls ffi/target)"
