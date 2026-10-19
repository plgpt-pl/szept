# Szept – plan i status (2026-10-19)

| Moduł | Zakres | Status |
|---|---|---|
| M01 Serwer-skrzynka | rejestracja bez PII, bundle PQXDH, kolejka kopert, WS, załączniki, TTL, limity, brak logów | ✅ 7 testów, LaunchAgent, https://szept.plgpt.pl |
| M02 libsignal w Xcode | prebuilt libsignal_ffi.a + wrapper Swift jako target framework (staticlib), -lc++ | ✅ device + symulator |
| M03 Magazyn kluczy | SignalStore: pliki z Data Protection, poza backupem; tożsamość, prekeys, Kyber, sesje, klucze kontaktów (TOFU + przypinanie) | ✅ |
| M04 Protokół Szept | rejestracja, dosyłanie kluczy, sesja z bundle (z weryfikacją przypiętego klucza), koperta HPKE, załączniki GCM-SIV, numer bezpieczeństwa, link/QR | ✅ 8 testów |
| M05 Silnik + UI | onboarding, lista, czat (tekst, zdjęcia, potwierdzenia, ponów), QR (pokaz/skan/wklej), info kontaktu + weryfikacja, znikające wiadomości (sync obu stron), ustawienia, Face ID, usuń wszystko, motyw Sowa | ✅ e2e przez serwer |
| M06 Instalacja na iPhone | build-install.sh | ✅ 19.10 – Apple Developer Program aktywny (team R8M2B5S4G3 bez zmian), profil roczny do 2027-10-19, apka uruchomiona na iPhonie Adama |
| M07 Powiadomienia | iOS: BGAppRefresh (bez APNs) ✅ 0.1.1; opcja pustego pusha APNs do decyzji. Android: własny kanał WS w usłudze pierwszoplanowej ✅ | częściowo |
| M08 Android | Kotlin/Compose, libsignal-android 0.105.1, ten sam serwer; parytet funkcji z iOS; usługa nasłuchu bez Google; APK release podpisany | ✅ 3 testy + interop z iOS przez serwer |
| M09 Grupy (ZROBIONE 19.10 – docs/GRUPY.md, rozsyłanie pairwise, iOS+Android 0.2.0, testy 3 telefonów na obu platformach) | Sender Keys (libsignal groupEncrypt/groupDecrypt), dystrybucja kluczy w kopertach 1:1 | do zrobienia |
| M10 Publikacja | strona szept.plgpt.pl ✅ + APK do pobrania z SHA-256 ✅; TestFlight (po Apple Developer); repo publiczne na AGPL | częściowo |
| M11 Metadane | tryb Tor (klient przez onion), rotacja signed prekey co 7 dni, padding długości kopert | pomysły |

## Decyzje
- Nazwa robocza **Szept** (do zmiany; bundle `pl.plgpt.szept` (od 19.10, wcześniej pl.4dt.szept – Szept należy do linii PLGPT), schemat linków `szept://`).
- Serwer za Cloudflare Tunnel (`bybit-bot`): Cloudflare widzi IP klientów i rozmiary/czas kopert, nie treść i nie nadawcę. Alternatywa bez CF: VPS z własnym TLS albo onion.
- Brak pushy w v0.1: WebSocket działa, gdy apka jest otwarta (i chwilę po zejściu do tła). To świadomy kompromis prywatności na iOS.
- Nadawca nie uwierzytelnia się przy wysyłce → spam możliwy tylko znając czyjś 128-bitowy `account_id` (nie do zgadnięcia); limit 240 zapytań/min/IP.
- Wiadomość od nieznanego nadawcy tworzy kontakt z flagą „napisał pierwszy” i banerem w czacie; weryfikacja numerem bezpieczeństwa.
- Zmiana klucza kontaktu (nowy telefon) blokuje odszyfrowanie do czasu akceptacji w „Informacje i weryfikacja”.

## Jak zbudować kolejną apkę z tej rodziny
Serwer jest generyczny (koperty do losowych id) – ten sam backend obsłuży Android i desktop. Paleta `Sowa` i ikona w stylu PLGPT.

## TestFlight (19.10.2026)
- App Store Connect: apka „Szept – prywatny czat” (id 6824586767, bundle `pl.plgpt.szept`, SKU `pl.plgpt.szept`), grupa wewnętrzna „Wewnętrzni” (17c7a80c-…), tester Adam.
- Klucz API `2X3F542W54` (App Manager) w `~/.config/appstoreconnect/` (env + .p8), klient `tools/asc.py` (`asc.py GET /v1/apps`).
- Klucz App Manager NIE ma podpisu w chmurze → lokalny certyfikat „Apple Distribution: Adam Dabrowski” (RA2ZYXP9Y7, klucz `~/.config/appstoreconnect/dist.key`) i profil „Szept App Store” (IOS_APP_STORE) utworzone przez API; `app/ExportOptions.plist` = podpis ręczny.
- Wysyłka: `xcodebuild archive` (Release, `-derivedDataPath .build-archive`) → `xcodebuild -exportArchive -exportOptionsPlist ExportOptions.plist` z `-authenticationKey*`. Pierwszy build 0.1.1 (1) wysłany 18:48.
- Do zrobienia: deklaracja eksportowa (szyfrowanie nieobjęte wyjątkiem, libsignal), grupa zewnętrzna (Ola i inni – wymaga Beta App Review + kontaktu z telefonem), weryfikacja DSA (dokument CEIDG) przed publikacją w App Store.
- 19.10 ~19:05: deklaracja szyfrowania (standardowe algorytmy, bez Francji) zrobiona ręcznie w ASC; build 0.1.1 (1) w testach wewnętrznych, grupa zewnętrzna „Znajomi” (link publiczny https://testflight.apple.com/join/qcFFH5Gp, limit 200, tester mlodykawa@gmail.com), wysłany do Beta App Review (WAITING_FOR_REVIEW). Kontakt review uzupełniony (telefon Adama).

## 0.2.0 (19.10.2026 wieczór)
- **Grupy** (docs/GRUPY.md): rozsyłanie pairwise, serwer nic nie wie o grupach. iOS: `ChatGroup` (SwiftData), `ChatTarget`, `GroupViews.swift`, test `GroupTests` (3 telefony + obcy odrzucony + wyjście). Android: tabela `groups`, `ChatBody` wspólny dla 1:1 i grup, test `threePhonesGroupThroughServer` (5/5 na emulatorze). Brak potwierdzeń odbioru w grupach. Następny krok przy dużych grupach: Sender Keys.
- **Crash 0.1.1 na starcie** przy włączonej blokadzie Face ID (LockScreen w `.overlay` przed `.environment`) – naprawiony w 0.1.2; build 1 wycofany z TestFlight.
- **Serwer**: 301 HTTP→HTTPS (CF-Visitor / X-Forwarded-Proto), HSTS, CSP, nosniff, DENY, Referrer-Policy; strona czyta wersję i SHA-256 APK z plików `szept.apk.version` / `szept.apk.sha256` (`tools/publish-apk.sh`); link TestFlight na stronie. Uwagi z zewnętrznego przeglądu strony (brak przekierowania, HSTS, nagłówków) – ZAŁATWIONE; brak publicznego repozytorium – OTWARTE (decyzja usera gdzie publikować, AGPL).
- **TestFlight**: deklaracja szyfrowania NIE przenosi się między buildami i API nie obsługuje wariantu „standardowe algorytmy, bez Francji” (brak zasobu `appEncryptionDeclarations` po odpowiedzi w UI) → przy każdym buildzie 4 kliknięcia usera w ASC (albo automatyzacja Chrome). Do sprawdzenia: `ITSEncryptionExportComplianceCode`.
