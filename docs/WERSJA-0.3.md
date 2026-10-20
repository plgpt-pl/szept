# Szept 0.3 – powiadomienia, odpowiedzi/reakcje/edycja/usuwanie, pliki i głosówki, angielski

Obowiązuje oba klienty i serwer. JSON `MessageContent` pozostaje wspólny 1:1 (iOS `SzeptProtocol.swift`,
Android `Protocol.kt`). Nieznane pola/rodzaje klient ignoruje (zgodność wstecz z 0.2.0).

## 1. Nowe pola i rodzaje w `MessageContent`

| Pole | Typ | Znaczenie |
|---|---|---|
| `r` | string? | id wiadomości, na którą to jest odpowiedź (dla `text`, `image`, `file`, `audio`) |
| `fn` | string? | nazwa pliku (`file`) |
| `mime` | string? | typ MIME (`file`, `audio`; głosówki zawsze `audio/mp4`) |
| `dur` | number? | długość nagrania w sekundach (`audio`) |

Nowe wartości `kind`:

| kind | pola | semantyka |
|---|---|---|
| `file` | `att` (w/h = 0), `fn`, `mime`, `r?`, `expire?`, `g?` | dowolny plik, szyfrowany jak obraz (AES-256-GCM-SIV, klucz w treści) |
| `audio` | `att`, `dur`, `mime: audio/mp4`, `r?`, `expire?`, `g?` | wiadomość głosowa, AAC w kontenerze m4a (odtwarzalne natywnie na iOS i Android) |
| `reaction` | `ref` = id wiadomości, `text` = emoji, `op` = `set` \| `remove`, `g?` | jedna reakcja na nadawcę i wiadomość; `set` zastępuje poprzednią |
| `edit` | `ref`, `text`, `g?` | nowa treść wiadomości tekstowej; **tylko własne** wiadomości nadawcy |
| `delete` | `ref`, `g?` | usunięcie u wszystkich; **tylko własne**; odbiorca kasuje treść i załącznik, zostawia placeholder „Wiadomość usunięta” |

Walidacja u odbiorcy: `edit`/`delete` przyjmowane tylko, gdy wiadomość `ref` istnieje i jej nadawcą jest nadawca
koperty (w 1:1: wiadomość nie jest moja i pochodzi od tego kontaktu; w grupie: `senderId == nadawca`). `reaction`
może dotyczyć każdej wiadomości w tej rozmowie (także mojej). Wszystkie trzy, jeśli `ref` nieznany, są ignorowane
(nie kolejkujemy). Limity: emoji ≤ 8 znaków, `text` edycji ≤ 64 KB jak zwykła wiadomość, plik ≤ 32 MB (serwer `MAX_ATT`).

`receipt` bez zmian; na `reaction`/`edit`/`delete` nie wysyłamy potwierdzeń i nie zmieniają one `lastActivity`
ani licznika nieprzeczytanych. `timer` i `group` bez zmian; `expire` dziedziczy się na `file`/`audio` jak na `image`.

## 2. Model lokalny

iOS `Message` (SwiftData, pola opcjonalne/z domyślnymi – migracja lekka):
`kindRaw: String = "text"` (text/image/file/audio), `replyToId: String?`, `reactionsJSON: String = "{}"`
(mapa `senderId → emoji`; własne reakcje pod kluczem własnego `accountId`), `editedAt: Date?`, `isDeleted: Bool = false`,
`fileName: String?`, `mimeType: String?`, `duration: Double = 0`. `attachmentFile` wspólny dla image/file/audio.

Android `Db` → wersja 3: kolumny `kind TEXT DEFAULT 'text'`, `reply_to TEXT`, `reactions TEXT DEFAULT '{}'`,
`edited_at INTEGER`, `deleted INTEGER DEFAULT 0`, `file_name TEXT`, `mime TEXT`, `duration REAL DEFAULT 0`.

## 3. Powiadomienia push (iOS, APNs)

- Serwer: `PUT /v1/push` (auth) `{platform:"apns", token:"<hex>", env:"sandbox"|"production"}`; `DELETE /v1/push` (auth).
  Jeden token na konto (nadpisanie). Token kasowany przy `DELETE /v1/accounts` i po odpowiedzi APNs 410 / `BadDeviceToken`.
- Wysyłka: przy `enqueue` kopert dla konta, które ma token i **nie ma otwartego WebSocketu**. Treść zawsze generyczna:
  `{"aps":{"alert":{"title":"Szept","body":"Nowa wiadomość"},"sound":"default","content-available":1,"thread-id":"szept"}}`,
  nagłówki `apns-topic: pl.plgpt.szept`, `apns-push-type: alert`, `apns-collapse-id: szept`, `apns-priority: 10`.
  Nie więcej niż 1 push na konto na 10 s. Apple dowiaduje się tylko, że na ten telefon coś przyszło – bez nadawcy, bez treści.
- Uwierzytelnienie APNs: token JWT ES256 (klucz .p8 z portalu deweloperskiego), cache 50 min. Konfiguracja przez env:
  `SZEPT_APNS_KEY_PATH`, `SZEPT_APNS_KEY_ID`, `SZEPT_APNS_TEAM_ID`, `SZEPT_APNS_TOPIC`. Brak klucza = push wyłączony
  (reszta działa). HTTP/2 przez `httpx[http2]`. Hosty: `api.push.apple.com` / `api.sandbox.push.apple.com` wg `env`.
- iOS: `UIApplicationDelegateAdaptor`, `registerForRemoteNotifications`, token → `PUT /v1/push`; środowisko odczytane
  z `embedded.mobileprovision` (`aps-environment`), w symulatorze pomijane. `didReceiveRemoteNotification` → `engine.sync()`
  (pobranie kopert w tle). Po wejściu na pierwszy plan kasujemy dostarczone powiadomienia. Entitlement `aps-environment`,
  `UIBackgroundModes` + `remote-notification`. Powiadomienia lokalne (z WebSocketu/odświeżania) bez zmian.
- Android bez zmian (stałe połączenie WebSocket).

## 4. UI (parytet iOS/Android)

- Przytrzymanie/menu kontekstowe dymka: **Odpowiedz**, **Reaguj** (👍 ❤️ 😂 😮 😢 🙏 + dowolne), **Kopiuj**,
  **Edytuj** (moje, tekstowe), **Usuń u wszystkich** (moje), **Usuń u mnie**. Przesunięcie dymka w bok = Odpowiedz.
- Cytat nad dymkiem: nazwa nadawcy + skrót treści („📷 Zdjęcie”, „📎 nazwa.pdf”, „🎤 Głosówka 0:12”); dotknięcie przewija do oryginału.
- Reakcje pod dymkiem jako pastylki (emoji + liczba); dotknięcie własnej cofa.
- Edytowana: dopisek „(edytowano)”; usunięta: kursywa „Wiadomość usunięta”, bez menu poza „Usuń u mnie”.
- Pasek wpisywania: przycisk 📎 (zdjęcie / plik), przytrzymanie 🎤 nagrywa (przesunięcie w lewo anuluje), puszczenie wysyła;
  podczas nagrywania licznik czasu. Dymek audio: play/pause, pasek postępu, czas. Dymek pliku: ikona, nazwa, rozmiar,
  dotknięcie otwiera podgląd (iOS QuickLook / Android ACTION_VIEW przez FileProvider), udostępnienie systemowe.
- Podgląd na liście czatów: „📎 nazwa.pdf”, „🎤 Głosówka”, „👍 do: …” nie pojawia się (reakcje nie zmieniają podglądu).

## 5. Języki

Interfejs po polsku (źródło) i angielsku. iOS: String Catalog `Localizable.xcstrings` (klucze = polskie literały,
tłumaczenia `en`), `developmentLanguage: pl`; wszystkie teksty przez `Text("…")`/`String(localized:)`. Android:
`res/values/strings.xml` = angielski (domyślny dla reszty świata), `res/values-pl/strings.xml` = polski; w Compose
`stringResource(R.string.…)`. Nazwy w sklepie już są w obu językach.

## 6. Kolejność prac

1. Serwer (push, `MAX_ATT` 32 MB, testy) – niezależnie.
2. iOS i Android równolegle: protokół + model + UI punktów 1–4, testy jednostkowe JSON i e2e (dwa/trzy „telefony” przez
   lokalny serwer: odpowiedź, reakcja, edycja, usunięcie, plik, głosówka; nieuprawniona edycja cudzej wiadomości ignorowana).
3. Lokalizacja (po 2, bo dotyka wszystkich widoków).
4. Interop iOS↔Android, buildy 0.3.0: TestFlight (4) + APK; wyłączenie `SZEPT_ATTACH_REQUIRE_AUTH=0` gdy wszyscy zaktualizują.
