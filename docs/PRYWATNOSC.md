# Szept – co kto widzi (model zagrożeń v0.1)

| Dane | Ty i rozmówca | Serwer Szept (my) | Cloudflare | Apple | Ktoś z Twoim telefonem |
|---|---|---|---|---|---|
| Treść wiadomości i zdjęć | tak | **nie** (E2E, libsignal) | nie | nie | po odblokowaniu telefonu; z Face ID w apce – nie |
| Kto do kogo pisze | tak | **nie** (koperta HPKE, wysyłka anonimowa, brak logów) | nie | nie | lista kontaktów w apce |
| Że konto X odebrało coś o godz. H | – | tak, na czas doręczenia | tak (połączenie) | nie | – |
| Adres IP | – | nie zapisywany (limit zapytań tylko w RAM) | tak, na czas połączenia | nie | – |
| Numer telefonu / e-mail / nazwisko | – | **nie istnieją w systemie** | – | – | – |
| Kopia w chmurze | – | – | – | **nie** (katalog poza backupem, Keychain tylko to urządzenie) | – |
| Powiadomienia (iOS od 0.3) | – | token urządzenia; wysyła tylko „Nowa wiadomość” | – | APNs: sygnał bez nadawcy i treści (wie, że telefon coś dostał) | – |

Granice:
- **Urządzenie** jest granicą zaufania: jailbreak, złośliwy profil MDM, zrzut ekranu rozmówcy – poza modelem.
- **Pierwsza wiadomość od nieznanego** – klucz przyjmowany TOFU; weryfikuj numer bezpieczeństwa z osobami, z którymi mówisz o rzeczach ważnych.
- **Operator serwera** nie może podmienić kluczy kontaktom dodanym kodem QR/linkiem (klucz przypięty w linku). Może: skasować konto, nie doręczyć, policzyć rozmiary kopert.
- **Cloudflare** widzi IP i czas połączeń. Jeśli to za dużo – serwer na VPS z własnym TLS albo adres .onion (plan M11).
- **Kryptografia** nie jest nasza: libsignal 0.105.1 (ten sam kod co w aplikacji Signal), nic nie implementujemy sami.
