import base64, os, sys
import pytest
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
import szept_server as S  # noqa: E402

B = lambda n: base64.b64encode(bytes([n]) * 32).decode()  # noqa: E731
REG = {"identity_key": B(1), "registration_id": 1234,
       "signed_prekey": {"id": 1, "key": B(2), "signature": B(3)},
       "kyber_last_resort": {"id": 1, "key": B(4), "signature": B(5)},
       "prekeys": [{"id": i, "key": B(10 + i)} for i in range(3)],
       "kyber_prekeys": [{"id": i, "key": B(20 + i), "signature": B(6)} for i in range(2)]}


@pytest.fixture
async def cli(aiohttp_client, tmp_path):
    return await aiohttp_client(S.make_app(tmp_path))


async def register(cli, **over):
    r = await cli.post("/v1/accounts", json={**REG, **over})
    assert r.status == 201, await r.text()
    d = await r.json()
    return d["account_id"], {"Authorization": f"Bearer {d['token']}"}


async def test_register_and_bundle_consumes_one_time_keys(cli):
    acc, h = await register(cli)
    assert (await (await cli.get("/v1/keys", headers=h)).json()) == {"prekeys": 3, "kyber_prekeys": 2}
    b = await (await cli.get(f"/v1/keys/{acc}")).json()
    assert b["identity_key"] == B(1) and b["registration_id"] == 1234 and b["device_id"] == 1
    assert b["prekey"]["id"] == 0 and b["kyber_prekey"]["id"] == 0
    for _ in range(3):
        b = await (await cli.get(f"/v1/keys/{acc}")).json()
    assert "prekey" not in b                      # jednorazowe wyczerpane
    assert b["kyber_prekey"] == REG["kyber_last_resort"]   # fallback na klucz ostatniej szansy
    assert (await (await cli.get("/v1/keys", headers=h)).json()) == {"prekeys": 0, "kyber_prekeys": 0}
    r = await cli.put("/v1/keys", headers=h, json={"prekeys": [{"id": 9, "key": B(9)}]})
    assert (await r.json())["prekeys"] == 1


async def test_bad_registration_rejected(cli):
    r = await cli.post("/v1/accounts", json={**REG, "identity_key": "nie-base64!"})
    assert r.status == 400
    r = await cli.post("/v1/accounts", json={**REG, "registration_id": 99999})
    assert r.status == 400
    assert (await cli.get("/v1/keys", headers={"Authorization": "Bearer zly"})).status == 401
    assert (await cli.get("/v1/keys/nieistnieje")).status == 404


async def test_mailbox_roundtrip_and_ack(cli):
    acc, h = await register(cli)
    r = await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(7)}, {"content": B(8)}]})
    assert r.status == 200 and (await r.json()) == {"queued": 2}
    msgs = (await (await cli.get("/v1/messages", headers=h)).json())["messages"]
    assert [m["content"] for m in msgs] == [B(7), B(8)]
    await cli.delete(f"/v1/messages/{msgs[0]['id']}", headers=h)
    msgs = (await (await cli.get("/v1/messages", headers=h)).json())["messages"]
    assert [m["content"] for m in msgs] == [B(8)]
    assert (await cli.put("/v1/messages/brak", json={"messages": [{"content": B(7)}]})).status == 404
    assert (await cli.put(f"/v1/messages/{acc}", json={"messages": []})).status == 400


async def test_websocket_delivers_and_acks(cli):
    acc, h = await register(cli)
    await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(1)}]})
    ws = await cli.ws_connect("/v1/ws", headers=h)
    m = await ws.receive_json(timeout=5)
    assert m["type"] == "message" and m["content"] == B(1)
    await ws.send_json({"type": "ack", "id": m["id"]})
    # wiadomość wysłana w trakcie połączenia budzi gniazdo
    await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(2)}]})
    m2 = await ws.receive_json(timeout=5)
    assert m2["content"] == B(2)
    await ws.close()
    left = (await (await cli.get("/v1/messages", headers=h)).json())["messages"]
    assert [x["content"] for x in left] == [B(2)]   # nie potwierdzona zostaje w kolejce


async def test_attachments(cli):
    blob = os.urandom(5000)
    r = await cli.post("/v1/attachments", data=blob)
    assert r.status == 201
    att = (await r.json())["id"]
    r = await cli.get(f"/v1/attachments/{att}")
    assert r.status == 200 and await r.read() == blob
    await cli.delete(f"/v1/attachments/{att}")
    assert (await cli.get(f"/v1/attachments/{att}")).status == 404
    assert (await cli.get("/v1/attachments/../etc")).status == 404


async def test_delete_account_wipes_everything(cli):
    acc, h = await register(cli)
    await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(1)}]})
    assert (await cli.delete("/v1/accounts", headers=h)).status == 200
    assert (await cli.get(f"/v1/keys/{acc}")).status == 404
    assert (await cli.get("/v1/messages", headers=h)).status == 401


async def test_no_pii_in_database(cli, tmp_path):
    acc, h = await register(cli)
    import sqlite3
    db = sqlite3.connect(tmp_path / "szept.db")
    cols = {t: [c[1] for c in db.execute(f"PRAGMA table_info({t})")] for (t,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
    flat = " ".join(sum(cols.values(), []))
    for forbidden in ("ip", "phone", "email", "name", "sender"):
        assert forbidden not in flat.split()
    (tok,) = db.execute("SELECT token_hash FROM accounts").fetchone()
    assert tok not in h["Authorization"]  # w bazie tylko hash tokenu


async def test_https_redirect_and_security_headers(cli):
    r = await cli.get("/", headers={"CF-Visitor": '{"scheme":"http"}'}, allow_redirects=False)
    assert r.status == 301 and r.headers["Location"].startswith("https://")
    r = await cli.get("/szept.apk.sha256", headers={"X-Forwarded-Proto": "http"}, allow_redirects=False)
    assert r.status == 301
    r = await cli.get("/", headers={"CF-Visitor": '{"scheme":"https"}'})
    assert r.status == 200
    for h in ("Strict-Transport-Security", "Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy"):
        assert h in r.headers, h
    assert "frame-ancestors 'none'" in r.headers["Content-Security-Policy"]
    assert r.headers["Cache-Control"] == "no-store"
    body = await r.text()
    assert "{{APK_" not in body, "placeholdery wersji/sumy APK podstawione"
