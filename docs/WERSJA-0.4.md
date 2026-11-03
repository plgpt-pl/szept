# Szept 0.4 – „pisze…”, przeczytane, przypinanie, wyciszanie, wyszukiwanie, notatki, przekazywanie

Obowiązuje oba klienty. Serwer bez zmian. `MessageContent` pozostaje wspólny 1:1 (iOS `SzeptProtocol.swift`,
Android `Protocol.kt`); nieznane pola/rodzaje klient ignoruje (zgodność wstecz z 0.3.x).

## 1. Protokół

### 1.1 Wskaźnik pisania – nowy `kind: "typing"`

| pole | wartość |
|---|---|
| `op` | `"start"` \| `"stop"` |
| `g` | id grupy (opcjonalnie – w grupie rozsyłane do wszystkich członków jak każda wiadomość grupowa) |

- Wysyłka: przy pierwszym znaku w polu tekstowym `start`, potem najwyżej co 5 s kolejny `start`, dopóki użytkownik pisze.
  `stop` po wysłaniu wiadomości, po wyczyszczeniu pola albo po 5 s bez zmian w polu. Nie wysyłamy, gdy pole jest
  w trybie edycji wiadomości (edycja to nie pisanie nowej).
- Odbiór: NIE tworzy wiadomości, nie zmienia `lastActivity`/`unread`, nie wysyła potwierdzeń, nie wywołuje powiadomień.
  Stan w pamięci (nie w bazie): `typing[targetId] = {senderId: wygasa}`; `start` ustawia wygaśnięcie na +7 s,
  `stop` lub nadejście zwykłej wiadomości od tego nadawcy w tej rozmowie usuwa wpis.
- UI: w nagłówku czatu pod nazwą „pisze…” (1:1) / „Ola pisze…”, „Ola i Tomek piszą…” (grupa);
  na liście rozmów zamiast podglądu ostatniej wiadomości kursywą „pisze…”.
- Serwer: zwykła koperta, więc każda koperta `typing` do telefonu bez otwartej apki wywołałaby push APNs.
  Dlatego **`typing` wysyłamy tylko w rozmowach, w których ostatnia wiadomość od rozmówcy (w grupie: od
  kogokolwiek) przyszła < 2 min temu** – rozmowa „na żywo”, druga strona najpewniej ma apkę otwartą.
  Telefonów leżących w kieszeni nie budzimy.

### 1.2 Potwierdzenie przeczytania – `kind: "receipt"`, `op: "read"`

- `ref` = id **najnowszej** przeczytanej wiadomości rozmówcy. Odbiorca oznacza jako `read` wszystkie MOJE wiadomości
  w tej rozmowie 1:1 z `sentAt <= sentAt(ref)` i statusem `sent`/`delivered`.
- Wysyłane, gdy rozmowa jest na ekranie i apka aktywna: po otwarciu czatu i po nadejściu nowej wiadomości,
  najwyżej raz na 2 s (debounce), tylko gdy jest nowa nieprzeczytana wiadomość od czasu ostatniego `read`.
- Tylko 1:1 (grupy bez potwierdzeń jak dotąd).
- Starsze klienty (0.3.x) potraktują to jak zwykłe potwierdzenie odbioru `ref` – nieszkodliwe.
- Status wiadomości: nowa wartość `read = 4` (iOS `Message.Status`, Android `Msg.READ`). UI: dwa haczyki / pełne kółko
  w kolorze magenta (`delivered` zostaje cyan).

### 1.3 Koperty „niepilne” – bez pusha (zmiana serwera, wstecznie zgodna)

`PUT /v1/messages/{id}` przyjmuje w elemencie `messages[]` opcjonalne `urgent: false`. Serwer kolejkuje kopertę jak
zwykle, ale nie wysyła pusha APNs, jeśli WSZYSTKIE koperty w żądaniu są niepilne (jak `urgent` w Signalu).
Klient oznacza `urgent: false` dla: `receipt` (odbiór i przeczytanie), `typing`, `reaction`, `edit`, `delete`.
Pilne (domyślne): `text`, `image`, `file`, `audio`, `timer`, `group`. Naprawia też błąd z 0.3: potwierdzenie odbioru
budziło telefon nadawcy pushem „Nowa wiadomość”. Serwer dowiaduje się tylko, czy koperta jest pilna – nie jej rodzaju.

### 1.4 Ustawienia prywatności (lokalne, wzajemne – jak w Signalu)

- **Potwierdzenia przeczytania** (domyślnie WŁ.). Wyłączone: nie wysyłam `read` i NIE pokazuję cudzych `read`
  (u mnie status kończy się na `delivered`).
- **Wskaźnik pisania** (domyślnie WŁ.). Wyłączone: nie wysyłam `typing` i nie pokazuję cudzych.

## 2. Funkcje lokalne (bez protokołu)

| funkcja | model | UI |
|---|---|---|
| **Przypięte rozmowy** | `pinned: Bool` na kontakcie i grupie | przypięte na górze listy (sortowane między sobą po `lastActivity`), ikonka pinezki; akcja swipe/długie przytrzymanie „Przypnij/Odepnij” |
| **Wyciszenie** | `mutedUntil: Date?` (iOS) / `muted_until INTEGER` ms (Android); `Date.distantFuture`/`Long.MAX_VALUE` = zawsze | opcje: 1 h, 8 h, 1 tydzień, zawsze, wyłącz; ikonka dzwonka przekreślonego na liście; wyciszona rozmowa nie wywołuje powiadomień lokalnych (licznik nieprzeczytanych dalej rośnie, ale szary). APNs bez zmian (serwer nie wie, od kogo) |
| **Wyszukiwanie** | – | pole szukania nad listą rozmów: filtruje rozmowy po nazwie ORAZ pokazuje sekcję „Wiadomości” z trafieniami w treści tekstów (bez usuniętych), max 50, tap otwiera rozmowę |
| **Notatka dla siebie** | kontakt o `id == mój accountId`, nazwa „Notatka dla siebie” / „Note to self”, `verified = true` | stała pozycja tworzona przy pierwszym uruchomieniu (jeśli konto zarejestrowane); wiadomości zapisywane lokalnie ze statusem `sent`, NIGDY nie wysyłane na serwer (także pliki/głosówki – kopiowane do mediów lokalnie); brak wskaźnika pisania i potwierdzeń; nie pojawia się w wyborze członków grupy |
| **Przekazywanie** | – | akcja w menu wiadomości „Przekaż” → arkusz z listą rozmów (z notatką) → wysyła NOWĄ wiadomość z tym samym tekstem / plikiem (załącznik szyfrowany na nowo z lokalnej kopii) bez `r` (bez odpowiedzi) i bez informacji, że przekazane. Nie dotyczy usuniętych i wiadomości systemowych |

## 3. Migracje

- iOS SwiftData (lekka): `Contact.pinned: Bool = false`, `Contact.mutedUntil: Date?`, `ChatGroup.pinned`, `ChatGroup.mutedUntil`.
- Android `Db` → wersja 4: `ALTER TABLE contacts ADD pinned INTEGER DEFAULT 0`, `ADD muted_until INTEGER DEFAULT 0`;
  to samo dla `groups`. Status `READ = 4`.

## 4. Testy

- Protokół: dekodowanie `typing` i `receipt/read` (także przez starszy dekoder – nieznane `op` nie psuje).
- Silnik: `read` oznacza wszystkie moje wcześniejsze wiadomości; `read` od kontaktu dla wiadomości z innej rozmowy – ignorowany;
  wyłączone potwierdzenia → `read` przychodzące ignorowane, wychodzące nie wysyłane.
- Notatka: wysłanie tekstu do notatki nie woła `deliver` (brak żądań do serwera).
- Typing: `start` ustawia stan, wygasa po 7 s, zwykła wiadomość go czyści.
- Interop iOS↔Android (istniejące testy warunkowe) – jeśli da się uruchomić: read i typing w obie strony.
