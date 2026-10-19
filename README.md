# Szept – prywatny komunikator (zestaw PLGPT)

https://szept.plgpt.pl · Android APK + iOS (TestFlight) · licencja AGPL-3.0

Komunikator end-to-end bez numerów telefonu i kont, na własnym serwerze. Kryptografia: **libsignal**
(oficjalna biblioteka Signala: PQXDH + Double Ratchet + HPKE), my piszemy tylko aplikację i cienki serwer-skrzynkę.

```
szept/
├── app/        iOS 17+, SwiftUI + SwiftData, xcodegen (project.yml) – `./build-install.sh` buduje i instaluje na iPhone
├── android/    Android 8+, Kotlin + Compose, libsignal-android 0.105.1 (repo Maven Signala), ten sam protokół i serwer
├── server/     aiohttp + SQLite, port 127.0.0.1:11437, LaunchAgent com.4dt.szept-server, publicznie https://szept.plgpt.pl
├── vendor/     libsignal 0.105.1 (wrapper Swift 1:1 + prebuilt libsignal_ffi.a przez fetch-libsignal.sh)
└── docs/       PLAN.md (moduły/status), PRYWATNOSC.md (model zagrożeń), ikona/
```

## Jak to działa
1. Telefon generuje tożsamość (klucz Curve25519) i paczkę kluczy wstępnych (signed prekey, Kyber-1024, klucze jednorazowe).
2. Rejestracja na serwerze zwraca losowy `account_id` (128 bitów) i token. Serwer nie zna niczego poza kluczami publicznymi.
3. Kontakt dodaje się kodem QR / linkiem `szept://add?i=<id>&k=<klucz>` – link **przypina klucz tożsamości**, więc serwer nie może
   podmienić kluczy (ochrona przed złośliwym operatorem). Numer bezpieczeństwa (60 cyfr) do weryfikacji osobistej.
4. Wiadomość: Double Ratchet (libsignal) → koperta JSON z `account_id` nadawcy → **HPKE do klucza odbiorcy**. Serwer dostaje
   wyłącznie szyfrogram HPKE i nie wie, kto wysłał (wysyłka bez uwierzytelnienia, jak sealed sender).
5. Odbiór przez WebSocket (`/v1/ws`) albo REST; koperta kasowana po potwierdzeniu. Załączniki: AES-256-GCM-SIV, klucz jedzie
   w zaszyfrowanej wiadomości, plik na serwerze znika po pobraniu (albo po 7 dniach).

## Uruchomienie
```bash
# serwer
cd server && python3 -m venv .venv && .venv/bin/pip install aiohttp pytest pytest-aiohttp && .venv/bin/python -m pytest -q tests
./run-server.sh                      # SZEPT_PORT, SZEPT_DATA, SZEPT_MSG_TTL_DAYS, SZEPT_ATT_TTL_DAYS w .env
# apka
cd vendor && ./fetch-libsignal.sh    # ~180 MB prebuilt z build-artifacts.signal.org, suma SHA-256 z GitHub
cd ../app && xcodegen generate && xcodebuild -scheme Szept -destination 'platform=iOS Simulator,name=iPhone 17 Pro' test
```
Testy: serwer 8 (pytest), iOS 13 (protokół, e2e przez prawdziwy serwer lokalny – dwa „telefony” w jednym procesie, grupa 3 telefonów),
Android 5 (instrumentowane na emulatorze, w tym e2e i grupa przez serwer) + test interop iOS↔Android (`InteropTest` z `-e interop 1`
i `InteropTests` z linkiem partnera w `/tmp/szept-peer-link`). Tryb zrzutów w symulatorze iOS: `-demo` / `-demo -demo-open`.

```bash
# Android (emulator wr4pme albo telefon przez adb)
cd android && ./gradlew :app:connectedDebugAndroidTest          # testy; serwer lokalny widoczny jako 10.0.2.2:11437
./gradlew :app:assembleRelease                                   # podpis z keystore.properties (poza gitem), APK → server/static/szept.apk
```
Android: brak usług Google – powiadomienia przez własne stałe połączenie WebSocket (usługa „Szept nasłuchuje”, do wyłączenia
w Ustawieniach). Biblioteki JNI libsignal są odchudzane NDK (117 MB → 9 MB na architekturę); bez NDK APK ma 1,1 GB.

## Licencja i zgłaszanie błędów
Cały kod Szeptu (serwer, iOS, Android) jest na **AGPL-3.0** – plik `LICENSE`. libsignal (Signal Messenger LLC) również jest
na AGPL-3.0, stąd wybór licencji. Publiczne repozytorium: <https://github.com/plgpt-pl/szept>.

Błędy bezpieczeństwa zgłaszaj przez Issues (bez szczegółów exploita) albo bezpośrednio do autora; nigdy nie wklejaj
prywatnych kluczy ani logów z treścią wiadomości. Model zagrożeń i to, czego serwer **nie** widzi: `docs/PRYWATNOSC.md`.
