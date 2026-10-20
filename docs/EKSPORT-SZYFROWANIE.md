# Szept – zgodność eksportowa (szyfrowanie)

Apple dystrybuuje aplikacje z USA, więc każda apka z szyfrowaniem podlega amerykańskim przepisom eksportowym (EAR).
Szept używa wyłącznie **standardowych, publicznie opisanych algorytmów** (libsignal) i nie zawiera żadnej własnej
kryptografii, dlatego kwalifikuje się jako „mass market” (ECCN 5D992.c) i **nie wymaga** licencji ani dokumentów
w App Store Connect. Obowiązki są dwa:

1. **Przy każdym buildzie** w App Store Connect odpowiedzieć na pytania o szyfrowanie: używa → tak; wyjątki → nie;
   standardowe algorytmy zamiast/oprócz systemowych → tak; Francja → nie. (Nie da się tego zrobić przez API.)
2. **Raz w roku, do 1 lutego**, wysłać do BIS raport samoklasyfikacji za poprzedni rok kalendarzowy
   (Supplement No. 8 to Part 742 EAR). Pierwszy raport: do **1 lutego 2027** (za 2026).

## Algorytmy w aplikacji (do pytań recenzenta / dokumentacji)

| Zastosowanie | Algorytm | Źródło |
|---|---|---|
| Uzgodnienie klucza | X25519 + ML-KEM-1024 (Kyber) – PQXDH | libsignal (Signal Messenger LLC, AGPL-3.0) |
| Szyfrowanie wiadomości | Double Ratchet: AES-256-CBC + HMAC-SHA256, HKDF | libsignal |
| Pieczętowanie koperty do odbiorcy | HPKE (X25519, HKDF-SHA256, ChaCha20-Poly1305) | libsignal |
| Załączniki | AES-256-GCM-SIV | libsignal / CryptoKit |
| Podpisy kluczy | Ed25519 / XEdDSA | libsignal |
| Transport | TLS 1.3 (Cloudflare → serwer) | system operacyjny |
| Dane lokalne | Keychain, Data Protection, biometria | system operacyjny |

Brak algorytmów własnościowych, brak zmian w algorytmach standardowych.

## Raport samoklasyfikacji BIS – treść

Wysłać **jeden e-mail** z załączonym plikiem CSV (kodowanie ASCII/UTF-8, nazwa dowolna, np. `szept-2026.csv`) na oba adresy:

- `crypt-supp8@bis.doc.gov`
- `enc@nsa.gov`

Temat: `Annual self-classification report – Adam Dabrowski (PLGPT) – 2026`

Treść maila (wystarczy):

```
Please find attached the annual self-classification report for encryption items
(Supplement No. 8 to Part 742 EAR) for calendar year 2026.

Adam Dabrowski, PLGPT
```

Załącznik CSV (uzupełnić adres pocztowy; kolumny dokładnie w tej kolejności, pierwszy wiersz to nagłówki):

```
PRODUCT NAME,MODEL NUMBER,MANUFACTURER,ECCN,AUTHORIZATION TYPE,ITEM TYPE,SUBMITTER NAME,TELEPHONE NUMBER,E-MAIL ADDRESS,MAILING ADDRESS,NON-U.S. COMPONENTS,NON-U.S. MANUFACTURING LOCATIONS
Szept (iOS and Android messaging app),0.2,Adam Dabrowski (PLGPT),5D992.c,MMKT,Mobility and mobile applications n.e.s.,Adam Dabrowski,+48 531 138 157,dabrowskia@me.com,"[ulica, kod, miasto], Poland",Yes,"Poland"
```

Objaśnienia pól:
- **ECCN 5D992.c** – oprogramowanie „mass market” z szyfrowaniem; **MMKT** – autoryzacja mass market (§742.15(b)).
- **ITEM TYPE** – jedna z kategorii BIS; dla komunikatora najbliższa to „Mobility and mobile applications n.e.s.”.
- **NON-U.S. COMPONENTS: Yes** – libsignal jest rozwijane w USA, ale apka powstaje w Polsce; wpisujemy Polskę jako miejsce produkcji.
- Raport obejmuje wszystkie wersje wydane w danym roku; jeden wiersz na produkt wystarczy.

BIS nie odpowiada na raporty; wystarczy zachować kopię wysłanego maila.

## Pozostałe pozycje na ekranie „App Store Regulations & Permits”

- **App Encryption Documentation → Upload**: dotyczy tylko kryptografii własnościowej albo sprzedaży we Francji. Szept: nic nie wgrywamy.
- **Digital Services Act → Set Up**: dane handlowca widoczne w unijnym App Store (adres, e-mail, telefon) – trzeba podać i zweryfikować kodem z maila/SMS, inaczej aplikacja może zniknąć ze sklepów w UE. Niezależne od recenzji.
- **China Mainland ICP Filing Number**: tylko dla sprzedaży w Chinach kontynentalnych. Bez numeru apka po prostu nie jest tam dostępna. Pomijamy.
