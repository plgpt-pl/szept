# Szept v4 — ikony iOS i App Store

Wybrana szklana sowa, bez zmian projektu graficznego.

## Zawartość

- `Szept-AppStore-1024.png` — główna ikona 1024 × 1024 px, PNG RGB sRGB, bez kanału alfa i bez wbudowanych zaokrągleń.
- `AppIcon.appiconset` — kompletny katalog do Xcode: 18 przypisań rozmiarów dla iPhone, iPad i App Store, 13 plików PNG.

## Wgranie do projektu

1. Rozpakuj ZIP i zachowaj kopię obecnego katalogu AppIcon.
2. W projekcie Szept podmień folder `app/Sources/Szept/Assets.xcassets/AppIcon.appiconset` na folder `AppIcon.appiconset` z tego pakietu.
3. W Xcode sprawdź, że target Szept → General → App Icons and Launch Screen używa `AppIcon`.
4. Zwiększ numer wersji/builda, utwórz archiwum przez Product → Archive, a w Organizer wybierz Distribute App → App Store Connect.
5. W App Store Connect wybierz przesłany build do nowej wersji aplikacji. Ikona pochodzi z builda.

Apple wymaga nowej wersji aplikacji, jeśli zmieniasz ikonę opublikowanej aplikacji:
https://developer.apple.com/help/app-store-connect/manage-app-information/add-an-app-icon/

Ikony przygotowano z zatwierdzonego obrazu v4 przez techniczne skalowanie sips. Nie generowano nowej wersji znaku.
