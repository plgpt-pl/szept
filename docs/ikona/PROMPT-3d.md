# Ikona Szept – brief do renderu 3D (na aegpt2, nie na MacBookach)

Styl i paleta jak ikona PLGPT (sowa neonowa): tło granat #0A0E2B → fiolet #2A1456, neon cyjan #5CE6FF i magenta #DA6CFF,
subtelne odbicia, lekkie szkło. Kompozycja: **dymek rozmowy** (zaokrąglony, z ogonkiem w lewym dolnym rogu) wypełniający ~75 % kadru,
w dymku **głowa sowy** z dużymi okrągłymi oczami (lewe cyjan, prawe magenta, lekkie świecenie), w prawym dolnym rogu dymka
**kłódka** zachodząca na krawędź (korpus ciemny, pałąk cyjan, dziurka świecąca). Bez tekstu. Kadr 1:1, margines ok. 6 %
(iOS sam maskuje rogi – NIE rysować zaokrąglonej ramki ani telefonu, por. lekcja z ikoną PLGPT).

Prompt (EN, Flux/SDXL):
"app icon, 3D render, glossy dark navy to violet gradient background, a rounded chat speech bubble made of smoked glass with
neon cyan and magenta rim light, inside the bubble a cute minimalist owl head with two large round glowing eyes (left cyan,
right magenta), a small padlock with glowing cyan shackle overlapping the bubble's bottom right corner, soft bloom,
octane render, centered, no text, no frame, square"
Negative: "text, letters, watermark, phone, frame, border, multiple bubbles, realistic feathers, blurry"

Robocza wersja wektorowa: `szept-icon.svg` → `szept-icon-1024.png` (qlmanage), użyta w buildzie 0.1.0.
