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


IP = lambda ip: {"CF-Connecting-IP": ip}   # noqa: E731 – klient testowy łączy się z 127.0.0.1, więc nagłówek CF jest honorowany


@pytest.fixture
async def cli(aiohttp_client, tmp_path):
    S._rate.clear()
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
    for i in range(3):   # różne adresy nadawców (rezerwa kluczy dotyczy jednego adresu, patrz test_prekey_reserve)
        b = await (await cli.get(f"/v1/keys/{acc}", headers=IP(f"10.0.0.{i}"))).json()
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
    acc, h = await register(cli)
    blob = os.urandom(5000)
    r = await cli.post("/v1/attachments", data=blob, headers=h)
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


# --- poprawki bezpieczeństwa 2026-10-19 -------------------------------------------------------------------

async def test_attachment_upload_requires_token_and_is_rate_limited(cli, monkeypatch):
    assert (await cli.post("/v1/attachments", data=b"x" * 100)).status == 401                       # M-3: bez tokenu
    assert (await cli.post("/v1/attachments", data=b"x", headers={"Authorization": "Bearer zly"})).status == 401
    acc, h = await register(cli)
    monkeypatch.setattr(S, "ATT_RATE_PER_MIN", 3)
    codes = [(await cli.post("/v1/attachments", data=b"x" * 10, headers=h)).status for _ in range(4)]
    assert codes == [201, 201, 201, 429]
    monkeypatch.setattr(S, "ATTACH_REQUIRE_AUTH", False)                                             # tryb przejściowy
    assert (await cli.post("/v1/attachments", data=b"x", headers=IP("10.9.9.9"))).status == 201


async def test_attachment_quota_per_account_and_global(cli, monkeypatch):
    acc, h = await register(cli)
    acc2, h2 = await register(cli)
    monkeypatch.setattr(S, "ACCOUNT_ATT_QUOTA", 10_000)
    monkeypatch.setattr(S, "MAX_ATTACH_BYTES", 12_000)
    r = await cli.post("/v1/attachments", data=b"a" * 8000, headers=h)
    assert r.status == 201
    att = (await r.json())["id"]
    assert (await cli.post("/v1/attachments", data=b"a" * 3000, headers=h)).status == 507           # kwota konta
    await cli.delete(f"/v1/attachments/{att}")                                                       # po pobraniu zwalnia kwotę
    assert (await cli.post("/v1/attachments", data=b"a" * 3000, headers=h)).status == 201
    assert (await cli.post("/v1/attachments", data=b"b" * 9000, headers=h2)).status == 201
    assert (await cli.post("/v1/attachments", data=b"b" * 100, headers=h2)).status == 507             # pula globalna
    assert cli.server.app["store"].attachment_bytes() == 12_000
    await cli.delete("/v1/accounts", headers=h)                                                      # kasowanie konta sprząta jego bloby
    assert cli.server.app["store"].attachment_bytes() == 9000


async def test_mailbox_flood_drops_oldest_and_caps_bytes(cli, monkeypatch):
    acc, h = await register(cli)
    monkeypatch.setattr(S, "MAX_QUEUE", 5)
    for i in range(1, 8):
        r = await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(i)}]})
        assert r.status == 200                                                                       # M-4: nigdy „mailbox full”
    msgs = (await (await cli.get("/v1/messages", headers=h)).json())["messages"]
    assert [m["content"] for m in msgs] == [B(i) for i in range(3, 8)]                                # zostały najnowsze
    monkeypatch.setattr(S, "MAX_QUEUE", 2000)
    monkeypatch.setattr(S, "MAX_MAILBOX_BYTES", 32 * 2 + 10)                                         # dwie koperty po 32 B
    await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(9)}]})
    msgs = (await (await cli.get("/v1/messages", headers=h)).json())["messages"]
    assert [m["content"] for m in msgs] == [B(7), B(9)]


async def test_mailbox_rate_per_ip_and_recipient(cli, monkeypatch):
    acc, h = await register(cli)
    acc2, _ = await register(cli)
    monkeypatch.setattr(S, "MSG_RATE_PER_RECIPIENT", 3)
    codes = [(await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(1)}]}, headers=IP("1.1.1.1"))).status for _ in range(4)]
    assert codes == [200, 200, 200, 429]
    assert (await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(1)}]}, headers=IP("2.2.2.2"))).status == 200   # inny adres
    assert (await cli.put(f"/v1/messages/{acc2}", json={"messages": [{"content": B(1)}]}, headers=IP("1.1.1.1"))).status == 200  # inny odbiorca
    assert len((await (await cli.get("/v1/messages", headers=h)).json())["messages"]) == 4


async def test_ws_auth_via_subprotocol_and_query_token_off_by_default(cli, monkeypatch):
    acc, h = await register(cli)
    tok = h["Authorization"][7:]
    await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(3)}]})
    ws = await cli.ws_connect("/v1/ws", protocols=("szept", f"szept-token.{tok}"))                     # L-7
    assert ws.protocol == "szept"                                                                    # serwer odsyła podprotokół
    assert (await ws.receive_json(timeout=5))["content"] == B(3)
    await ws.close()
    with pytest.raises(Exception):
        await cli.ws_connect("/v1/ws", protocols=("szept", "szept-token.zly"))
    with pytest.raises(Exception):
        await cli.ws_connect(f"/v1/ws?token={tok}")                                                   # ?token= domyślnie wyłączony
    assert (await cli.get(f"/v1/messages?token={tok}")).status == 401
    monkeypatch.setattr(S, "ALLOW_QUERY_TOKEN", True)
    ws = await cli.ws_connect(f"/v1/ws?token={tok}")
    await ws.close()


async def test_prekey_bundle_rate_limit_and_reserve(cli, monkeypatch):
    acc, h = await register(cli, prekeys=[{"id": i, "key": B(10 + i)} for i in range(8)],
                            kyber_prekeys=[{"id": i, "key": B(20 + i), "signature": B(6)} for i in range(8)])
    monkeypatch.setattr(S, "BUNDLE_RATE_PER_MIN", 4)
    codes = [(await cli.get(f"/v1/keys/{acc}", headers=IP("3.3.3.3"))).status for _ in range(5)]
    assert codes == [200, 200, 200, 200, 429]                                                        # L-8: 10/min na (IP, konto)
    assert (await cli.get(f"/v1/keys/{acc}", headers=IP("4.4.4.4"))).status == 200
    # 8 kluczy -> 3.3.3.3 wziął 3 (przy 5 pozostałych rezerwa go zatrzymała), 4.4.4.4 wziął 1 -> zostały 4
    assert (await (await cli.get("/v1/keys", headers=h)).json())["prekeys"] == 4
    b = await (await cli.get(f"/v1/keys/{acc}", headers=IP("4.4.4.4"))).json()
    assert "prekey" not in b and b["kyber_prekey"] == REG["kyber_last_resort"]                        # ten sam adres w ciągu godziny: bez rezerwy
    assert (await (await cli.get("/v1/keys", headers=h)).json())["prekeys"] == 4
    b = await (await cli.get(f"/v1/keys/{acc}", headers=IP("5.5.5.5"))).json()
    assert b["prekey"]["id"] == 4                                                                    # świeży adres dostaje klucz z rezerwy
    assert (await (await cli.get("/v1/keys", headers=h)).json())["prekeys"] == 3


async def test_cf_header_trusted_only_from_local_proxy(cli, monkeypatch):
    acc, h = await register(cli)
    monkeypatch.setattr(S, "MSG_RATE_PER_RECIPIENT", 2)
    monkeypatch.setattr(S, "TRUSTED_PROXY_IPS", set())                                               # I-4: połączenie nie z tunelu
    codes = [(await cli.put(f"/v1/messages/{acc}", json={"messages": [{"content": B(1)}]}, headers=IP(f"9.9.9.{i}"))).status for i in range(3)]
    assert codes == [200, 200, 429]                                                                  # podrobiony nagłówek nie omija limitu


def test_rate_dict_evicts_empty_keys(monkeypatch):
    S._rate.clear()
    assert S._rate_hit("a", 5) and S._rate_hit("b", 5)
    S._rate["a"].clear()
    S._rate_sweep()
    assert "a" not in S._rate and "b" in S._rate                                                     # L-9
    monkeypatch.setattr(S, "_RATE_MAX_KEYS", 3)
    for i in range(10):
        S._rate_hit(f"k{i}", 5)
    assert len(S._rate) <= 3
    S._rate.clear()


async def test_privacy_page(cli):
    r = await cli.get("/prywatnosc")
    assert r.status == 200 and "Polityka prywatności" in await r.text()
    assert r.headers["Content-Security-Policy"].startswith("default-src 'none'")
