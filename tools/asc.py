#!/Users/Adam/.config/appstoreconnect/.venv/bin/python
"""Mały klient App Store Connect API. Użycie: asc.py GET /v1/apps | asc.py POST /v1/bundleIds '{json}'"""
import json, os, sys, time, jwt, requests

def env():
    d = {}
    for line in open(os.path.expanduser("~/.config/appstoreconnect/env")):
        if "=" in line:
            k, v = line.strip().split("=", 1); d[k] = v
    return d

def token():
    e = env()
    key = open(e["ASC_KEY_PATH"]).read()
    now = int(time.time())
    return jwt.encode({"iss": e["ASC_ISSUER_ID"], "iat": now, "exp": now + 1100, "aud": "appstoreconnect-v1"},
                      key, algorithm="ES256", headers={"kid": e["ASC_KEY_ID"]})

def call(method, path, body=None):
    url = path if path.startswith("http") else "https://api.appstoreconnect.apple.com" + path
    r = requests.request(method, url, headers={"Authorization": f"Bearer {token()}", "Content-Type": "application/json"},
                         data=json.dumps(body) if body is not None else None, timeout=60)
    try:
        return r.status_code, r.json()
    except Exception:
        return r.status_code, r.text

if __name__ == "__main__":
    m, p = sys.argv[1], sys.argv[2]
    b = json.loads(sys.argv[3]) if len(sys.argv) > 3 else None
    code, data = call(m, p, b)
    print(code); print(json.dumps(data, indent=1, ensure_ascii=False)[:6000])
