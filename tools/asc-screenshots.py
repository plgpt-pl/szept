#!/usr/bin/env python3
"""Wgrywa zrzuty ekranu do App Store Connect.

    asc-screenshots.py <katalog> <id lokalizacji wersji> [<id lokalizacji> ...]

W katalogu: iphone-*.png (6,9" 1320×2868) i ipad-*.png (13" 2064×2752). Dla każdej lokalizacji tworzy (lub czyści)
zestaw zrzutów na typ ekranu, rezerwuje plik, wysyła kawałki pod adresy z uploadOperations i zatwierdza sumą MD5.
"""
import glob, hashlib, os, sys, time
import requests

sys.path.insert(0, os.path.dirname(__file__))
import asc  # noqa: E402

DISPLAY_TYPES = {  # kolejność prób: nowsza nazwa enum, potem starsza (ASC przyjmuje 6,9" i 13" także w starszych slotach)
    "iphone": ["APP_IPHONE_67"],
    "ipad": ["APP_IPAD_PRO_3GEN_129"],
}


def screenshot_set(loc_id: str, display_type: str) -> str:
    s, j = asc.call("GET", f"/v1/appStoreVersionLocalizations/{loc_id}/appScreenshotSets?filter[screenshotDisplayType]={display_type}")
    if s == 200 and j["data"]:
        set_id = j["data"][0]["id"]
        s2, j2 = asc.call("GET", f"/v1/appScreenshotSets/{set_id}/appScreenshots")
        for old in j2.get("data", []):
            asc.call("DELETE", f"/v1/appScreenshots/{old['id']}")
        return set_id
    s, j = asc.call("POST", "/v1/appScreenshotSets", {"data": {"type": "appScreenshotSets", "attributes": {"screenshotDisplayType": display_type},
                    "relationships": {"appStoreVersionLocalization": {"data": {"type": "appStoreVersionLocalizations", "id": loc_id}}}}})
    if s != 201:
        raise SystemExit(f"nie utworzono zestawu {display_type}: {s} {j}")
    return j["data"]["id"]


def upload(set_id: str, path: str) -> None:
    data = open(path, "rb").read()
    s, j = asc.call("POST", "/v1/appScreenshots", {"data": {"type": "appScreenshots", "attributes": {"fileName": os.path.basename(path), "fileSize": len(data)},
                    "relationships": {"appScreenshotSet": {"data": {"type": "appScreenshotSets", "id": set_id}}}}})
    if s != 201:
        raise SystemExit(f"rezerwacja {path}: {s} {j}")
    shot_id = j["data"]["id"]
    for op in j["data"]["attributes"]["uploadOperations"]:
        chunk = data[op["offset"]:op["offset"] + op["length"]]
        headers = {h["name"]: h["value"] for h in op["requestHeaders"]}
        r = requests.request(op["method"], op["url"], headers=headers, data=chunk, timeout=120)
        r.raise_for_status()
    s, j = asc.call("PATCH", f"/v1/appScreenshots/{shot_id}", {"data": {"type": "appScreenshots", "id": shot_id,
                    "attributes": {"uploaded": True, "sourceFileChecksum": hashlib.md5(data).hexdigest()}}})
    if s != 200:
        raise SystemExit(f"zatwierdzenie {path}: {s} {j}")
    for _ in range(30):
        s, j = asc.call("GET", f"/v1/appScreenshots/{shot_id}")
        st = j["data"]["attributes"]["assetDeliveryState"]["state"]
        if st == "COMPLETE":
            print(f"  OK {os.path.basename(path)}")
            return
        if st == "FAILED":
            raise SystemExit(f"  FAILED {path}: {j['data']['attributes']['assetDeliveryState']}")
        time.sleep(2)
    print(f"  {os.path.basename(path)}: nadal {st}")


def main() -> None:
    folder, locs = sys.argv[1], sys.argv[2:]
    for loc in locs:
        print("lokalizacja", loc)
        for kind, types in DISPLAY_TYPES.items():
            files = sorted(glob.glob(os.path.join(folder, f"{kind}-*.png")))
            if not files:
                continue
            set_id = screenshot_set(loc, types[0])
            print(f" zestaw {types[0]} ({len(files)} plików)")
            for f in files:
                upload(set_id, f)


if __name__ == "__main__":
    main()
