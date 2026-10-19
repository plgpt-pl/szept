# Grupy w Szepcie (v1: rozsyłanie pairwise)

Serwer nic nie wie o grupach. Grupa istnieje tylko w telefonach członków. Każda wiadomość grupowa jest szyfrowana
osobno do każdego członka istniejącą sesją 1:1 (PQXDH + Double Ratchet) i wrzucana do jego skrzynki jak zwykła
wiadomość. Do kilkudziesięciu osób. Przejście na Sender Keys (libsignal `GroupCipher`) możliwe później bez zmiany modelu.

## Pola w `MessageContent` (JSON po odszyfrowaniu)

Istniejące: `id, ts, kind, text, att, expire, ref, name`. Nowe (wszystkie opcjonalne, nieznane pola są ignorowane):

| pole      | typ                      | znaczenie |
|-----------|--------------------------|-----------|
| `g`       | string (UUID, wielkie)   | id grupy; obecne w KAŻDEJ wiadomości dotyczącej grupy (`text`, `image`, `timer`, `group`) |
| `gname`   | string                   | nazwa grupy (tylko `kind: group`, `op: set`) |
| `members` | [{`i`, `k`, `n`?}]       | pełna lista członków ŁĄCZNIE z nadawcą: `i` account_id, `k` base64 klucza tożsamości, `n` nazwa (tylko `op: set`) |
| `op`      | `"set"` \| `"leave"`     | operacja sterująca (tylko `kind: group`) |

Nowy `kind`: `group`. Pozostałe `kind` bez zmian.

## Semantyka

* **Utworzenie / zmiana** (`kind: group, op: set`): nadawca wysyła do wszystkich członków z listy (poza sobą) pełny stan
  grupy. Odbiorca:
  * grupa nieznana → tworzy ją lokalnie (zaproszenie) z nazwą i członkami; nadawca musi być na liście;
  * grupa znana, nadawca jest członkiem → podmienia nazwę i listę (łącznie: dodania i usunięcia); dokłada notatkę systemową;
  * nadawca NIE jest członkiem znanej grupy → ignoruje.
  * Członkowie, których odbiorca nie ma w kontaktach, są dodawani jako kontakty z kluczem `k` (TOFU, `fromUnknown = true`,
    nazwa z `n` albo „Kontakt xxxxxx”). Jeśli kontakt już istnieje, jego przypięty klucz NIE jest ruszany (nawet gdy `k` inny).
  * Odbiorca usuwa siebie z listy przy zapisie (lista lokalna = inni członkowie), ale przy wysyłce `set` zawsze dodaje siebie.
* **Wyjście** (`kind: group, op: leave`): nadawca wysyła do wszystkich, lokalnie usuwa grupę (lub oznacza `left`).
  Odbiorca usuwa nadawcę z członków i dokłada notatkę. Wiadomości od nie-członka są ignorowane.
* **Treść** (`kind: text|image` z `g`): zapis do grupy (nie do rozmowy 1:1 z nadawcą), `senderId` = nadawca, nazwa do
  wyświetlenia = nazwa kontaktu (lub `name` z wiadomości). Deduplikacja po `id` (ta sama wiadomość może przyjść raz).
  **Nie wysyłamy potwierdzeń odbioru dla wiadomości grupowych** (oszczędność n² kopert); status nadawcy = `sent`, gdy
  wszystkie wysyłki się powiodły, `failed` gdy choć jedna padła (ponowienie wysyła do wszystkich, odbiorcy deduplikują).
* **Znikanie** (`kind: timer` z `g`): ustawia `disappearAfter` grupy u odbiorców + notatka. Dotyczy wiadomości od chwili zmiany.
* Nadawca, którego klucz tożsamości się zmienił (`identityChanged`), jest traktowany jak w 1:1 – wiadomość nie odszyfruje
  się do czasu akceptacji nowego klucza w informacjach o kontakcie.

## Model lokalny

`Group { id, name, members: [account_id] (bez mnie), createdAt, lastActivity, unread, disappearAfter, left }`
`Message` dostaje `groupId?`, `senderId?` (dla cudzych wiadomości grupowych); `contact` staje się opcjonalny.
Lista rozmów = kontakty ∪ grupy posortowane po `lastActivity`. Nawigacja: grupa pod kluczem `"g:<id>"`.

## UI (parytet iOS/Android)

* Lista: wiersz grupy z ikoną grupy, nazwą, liczbą członków, podglądem „Nadawca: tekst”.
* „Nowa grupa”: nazwa + wybór kontaktów (co najmniej 1). Tworzy grupę i wysyła `set`.
* Czat grupowy: dymki cudzych wiadomości z nazwą nadawcy nad treścią; zdjęcia; znikanie w menu.
* Informacje o grupie: nazwa (edycja → `set`), lista członków (nazwa, status weryfikacji, link do kontaktu), „Dodaj osoby”
  (wybór z kontaktów → `set`), znikanie, „Opuść grupę” (→ `leave`, lokalne usunięcie).
