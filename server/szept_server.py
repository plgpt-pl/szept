#!/usr/bin/env python3
"""Serwer-skrzynka komunikatora Szept (koperty szyfrowane end-to-end, serwer widzi tylko szyfrogram).

Zasady prywatności:
- brak kont z danymi osobowymi: konto = losowe 16 bajtów (id) + losowy token; w bazie tylko hash tokenu,
- serwer przechowuje: klucze publiczne (bundle PQXDH), kolejkę nieodebranych kopert, zaszyfrowane załączniki,
- nadawca NIE uwierzytelnia się przy wysyłaniu (jak „sealed sender” w Signalu) - serwer nie wie, kto do kogo pisze,
- brak logów dostępu (access_log wyłączony), żadnych adresów IP w bazie,
- koperty znikają po doręczeniu (ack) albo po SZEPT_MSG_TTL_DAYS, załączniki po SZEPT_ATT_TTL_DAYS.

API v1 (JSON, base64 standard):
  POST   /v1/accounts                     rejestracja: identity_key, registration_id, signed_prekey, kyber_last_resort,
                                           prekeys[], kyber_prekeys[]  -> {account_id, token}
  PUT    /v1/keys              (auth)      dosyłanie kluczy jednorazowych / nowy signed_prekey
  GET    /v1/keys              (auth)      ile kluczy jednorazowych zostało
  GET    /v1/keys/{account_id}             bundle odbiorcy (zużywa 1 prekey + 1 kyber_prekey, gdy są; 10/min na (IP, konto),
                                           ostatnie 5 kluczy jednorazowych nie trafia dwa razy w ciągu godziny do tego samego IP)
  PUT    /v1/messages/{account_id}         {messages:[{content}]} - zakolejkuj koperty (bez auth; 60 kopert/min na (IP, odbiorca);
                                           pełna skrzynka = najstarsze nieodebrane koperty są wypierane)
  GET    /v1/messages          (auth)      nieodebrane koperty
  DELETE /v1/messages/{id}     (auth)      potwierdzenie odbioru
  GET    /v1/ws                (auth)      WebSocket: {"type":"message",...}; klient odsyła {"type":"ack","id":...}
                                           auth: nagłówek Authorization albo Sec-WebSocket-Protocol: szept, szept-token.<token>
                                           (?token= tylko gdy SZEPT_ALLOW_QUERY_TOKEN=1)
  POST   /v1/attachments       (auth)      surowe bajty (szyfrogram) -> {id}; limit 200 MB żywych bajtów na konto,
                                           globalnie SZEPT_MAX_ATTACH_BYTES (5 GB), 20 wysyłek/min na IP
  GET    /v1/attachments/{id}              pobranie, DELETE (bez auth) po pobraniu
  DELETE /v1/accounts          (auth)      skasowanie konta i kolejki
"""
import asyncio, base64, hashlib, hmac, json, logging, os, secrets, sqlite3, time
from collections import defaultdict, deque
from pathlib import Path

from aiohttp import web, WSMsgType

PORT = int(os.environ.get("SZEPT_PORT", "11437"))
DATA = Path(os.environ.get("SZEPT_DATA", Path(__file__).parent / "data"))
MSG_TTL = int(os.environ.get("SZEPT_MSG_TTL_DAYS", "30")) * 86400
ATT_TTL = int(os.environ.get("SZEPT_ATT_TTL_DAYS", "7")) * 86400
MAX_MSG = 64 * 1024
MAX_ATT = 12 * 1024 * 1024
MAX_QUEUE = 2000                       # kopert na konto
MAX_MAILBOX_BYTES = int(os.environ.get("SZEPT_MAILBOX_BYTES", str(32 * 1024 * 1024)))  # bajtów kopert na konto
RATE_PER_MIN = int(os.environ.get("SZEPT_RATE_PER_MIN", "240"))
MSG_RATE_PER_RECIPIENT = int(os.environ.get("SZEPT_MSG_RATE_PER_MIN", "60"))     # kopert/min na parę (IP, odbiorca)
ATT_RATE_PER_MIN = int(os.environ.get("SZEPT_ATT_RATE_PER_MIN", "20"))          # wysyłek załączników/min na IP
ACCOUNT_ATT_QUOTA = int(os.environ.get("SZEPT_ACCOUNT_ATTACH_BYTES", str(200 * 1024 * 1024)))  # żywych bajtów na konto
MAX_ATTACH_BYTES = int(os.environ.get("SZEPT_MAX_ATTACH_BYTES", str(5 * 1024 ** 3)))             # żywych bajtów globalnie
# Wysyłka załączników wymaga tokenu konta (ochrona dysku). Załącznik nie jest powiązany z odbiorcą ani z kopertą,
# więc serwer nadal nie wie, kto do kogo pisze - zna jedynie konto, które wgrało blob. SZEPT_ATTACH_REQUIRE_AUTH=0
# tylko przejściowo dla starych klientów (0.2.0 wysyłały bez tokenu).
ATTACH_REQUIRE_AUTH = os.environ.get("SZEPT_ATTACH_REQUIRE_AUTH", "1") != "0"
BUNDLE_RATE_PER_MIN = int(os.environ.get("SZEPT_BUNDLE_RATE_PER_MIN", "10"))    # pobrań bundle/min na parę (IP, konto)
PREKEY_RESERVE = 5                     # ostatnich kluczy jednorazowych nie wydajemy ponownie temu samemu IP w ciągu godziny
PREKEY_RESERVE_WINDOW = 3600
# ?token= w adresie WS ląduje w logach proxy/przeglądarek - domyślnie wyłączone; klienci używają nagłówka
# Authorization albo podprotokołu WebSocket "szept-token.<token>" (razem z "szept", które serwer odsyła).
ALLOW_QUERY_TOKEN = os.environ.get("SZEPT_ALLOW_QUERY_TOKEN", "0") == "1"
WS_PROTOCOL = "szept"
WS_TOKEN_PREFIX = "szept-token."
# CF-Connecting-IP wierzymy tylko, gdy połączenie przyszło z lokalnego tunelu cloudflared (127.0.0.1); inaczej
# każdy klient mógłby podać dowolny adres i omijać limity.
TRUSTED_PROXY_IPS = {"127.0.0.1", "::1"}
HEARTBEAT = 30

log = logging.getLogger("szept")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def unb64(s: str, max_len: int = MAX_MSG) -> bytes:
    if not isinstance(s, str) or len(s) > max_len * 4 // 3 + 4:
        raise web.HTTPBadRequest(text="bad base64 field")
    try:
        return base64.b64decode(s, validate=True)
    except Exception:
        raise web.HTTPBadRequest(text="bad base64 field")


def new_id() -> str:
    return base64.urlsafe_b64encode(secrets.token_bytes(16)).decode().rstrip("=")


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS accounts(
  id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, identity_key TEXT NOT NULL,
  registration_id INTEGER NOT NULL, signed_prekey TEXT NOT NULL, kyber_last_resort TEXT NOT NULL,
  created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS prekeys(account_id TEXT NOT NULL, key_id INTEGER NOT NULL, key TEXT NOT NULL,
  PRIMARY KEY(account_id, key_id));
CREATE TABLE IF NOT EXISTS kyber_prekeys(account_id TEXT NOT NULL, key_id INTEGER NOT NULL, key TEXT NOT NULL,
  signature TEXT NOT NULL, PRIMARY KEY(account_id, key_id));
CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY AUTOINCREMENT, account_id TEXT NOT NULL,
  content BLOB NOT NULL, ts REAL NOT NULL);
CREATE INDEX IF NOT EXISTS messages_account ON messages(account_id, id);
CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY, size INTEGER NOT NULL, ts REAL NOT NULL, owner TEXT);
CREATE INDEX IF NOT EXISTS attachments_owner ON attachments(owner);
"""


class Store:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, isolation_level=None, check_same_thread=False)
        # migracja starej bazy (bez kolumny owner) przed CREATE INDEX ze schematu
        cols = [r[1] for r in self.db.execute("PRAGMA table_info(attachments)").fetchall()]
        if cols and "owner" not in cols:
            self.db.execute("ALTER TABLE attachments ADD COLUMN owner TEXT")
        self.db.executescript(SCHEMA)
        self.att_dir = path.parent / "attachments"
        self.att_dir.mkdir(exist_ok=True)

    # --- konta i klucze -------------------------------------------------------------------------
    def create_account(self, identity_key, registration_id, signed_prekey, kyber_last_resort) -> tuple[str, str]:
        for _ in range(5):
            account_id, token = new_id(), secrets.token_urlsafe(32)
            try:
                self.db.execute("INSERT INTO accounts VALUES(?,?,?,?,?,?,?)",
                                (account_id, token_hash(token), identity_key, registration_id,
                                 json.dumps(signed_prekey), json.dumps(kyber_last_resort), time.time()))
                return account_id, token
            except sqlite3.IntegrityError:
                continue
        raise web.HTTPInternalServerError(text="id collision")

    def account_by_token(self, token: str):
        row = self.db.execute("SELECT id FROM accounts WHERE token_hash=?", (token_hash(token),)).fetchone()
        return row[0] if row else None

    def account_exists(self, account_id: str) -> bool:
        return self.db.execute("SELECT 1 FROM accounts WHERE id=?", (account_id,)).fetchone() is not None

    def update_keys(self, account_id, signed_prekey=None, kyber_last_resort=None, prekeys=(), kyber_prekeys=()):
        with self.db:
            if signed_prekey:
                self.db.execute("UPDATE accounts SET signed_prekey=? WHERE id=?", (json.dumps(signed_prekey), account_id))
            if kyber_last_resort:
                self.db.execute("UPDATE accounts SET kyber_last_resort=? WHERE id=?", (json.dumps(kyber_last_resort), account_id))
            self.db.executemany("INSERT OR REPLACE INTO prekeys VALUES(?,?,?)",
                                [(account_id, p["id"], p["key"]) for p in prekeys])
            self.db.executemany("INSERT OR REPLACE INTO kyber_prekeys VALUES(?,?,?,?)",
                                [(account_id, p["id"], p["key"], p["signature"]) for p in kyber_prekeys])

    def key_counts(self, account_id) -> dict:
        n1 = self.db.execute("SELECT COUNT(*) FROM prekeys WHERE account_id=?", (account_id,)).fetchone()[0]
        n2 = self.db.execute("SELECT COUNT(*) FROM kyber_prekeys WHERE account_id=?", (account_id,)).fetchone()[0]
        return {"prekeys": n1, "kyber_prekeys": n2}

    def take_bundle(self, account_id, allow_reserve: bool = True):
        """Bundle odbiorcy. Gdy allow_reserve=False, a kluczy jednorazowych zostało <= PREKEY_RESERVE, nie zużywamy ich
        (bundle bez prekey, kyber z klucza ostatniej szansy) - żeby jeden adres nie wyczerpał puli odbiorcy."""
        row = self.db.execute("SELECT identity_key, registration_id, signed_prekey, kyber_last_resort FROM accounts WHERE id=?",
                              (account_id,)).fetchone()
        if not row:
            return None
        bundle = {"account_id": account_id, "device_id": 1, "identity_key": row[0], "registration_id": row[1],
                  "signed_prekey": json.loads(row[2])}
        if not allow_reserve:
            left = self.db.execute("SELECT COUNT(*) FROM prekeys WHERE account_id=?", (account_id,)).fetchone()[0]
            if left <= PREKEY_RESERVE:
                bundle["kyber_prekey"] = json.loads(row[3])
                return bundle
        with self.db:
            pk = self.db.execute("SELECT key_id, key FROM prekeys WHERE account_id=? ORDER BY key_id LIMIT 1", (account_id,)).fetchone()
            if pk:
                self.db.execute("DELETE FROM prekeys WHERE account_id=? AND key_id=?", (account_id, pk[0]))
                bundle["prekey"] = {"id": pk[0], "key": pk[1]}
            kk = self.db.execute("SELECT key_id, key, signature FROM kyber_prekeys WHERE account_id=? ORDER BY key_id LIMIT 1",
                                 (account_id,)).fetchone()
            if kk:
                self.db.execute("DELETE FROM kyber_prekeys WHERE account_id=? AND key_id=?", (account_id, kk[0]))
                bundle["kyber_prekey"] = {"id": kk[0], "key": kk[1], "signature": kk[2]}
            else:
                bundle["kyber_prekey"] = json.loads(row[3])
        return bundle

    def delete_account(self, account_id):
        with self.db:
            for t in ("prekeys", "kyber_prekeys", "messages"):
                self.db.execute(f"DELETE FROM {t} WHERE account_id=?", (account_id,))
            self.db.execute("DELETE FROM accounts WHERE id=?", (account_id,))
        for (att_id,) in self.db.execute("SELECT id FROM attachments WHERE owner=?", (account_id,)).fetchall():
            self.delete_attachment(att_id)

    # --- kolejka kopert --------------------------------------------------------------------------
    def enqueue(self, account_id, contents: list[bytes]) -> int:
        """Kolejkuje koperty. Skrzynka ma limit liczby (MAX_QUEUE) i bajtów (MAX_MAILBOX_BYTES); po przekroczeniu
        wypieramy NAJSTARSZE nieodebrane koperty. Kompromis: ktoś, kto zna account_id, może zalewem wyprzeć cudze
        nieodebrane koperty (Double Ratchet nadrobi brakujące klucze, treść przepada) - ale nie może na stałe
        zablokować skrzynki, co dawałoby odrzucanie nowych kopert. Zalew ogranicza dodatkowo limit na (IP, odbiorca)."""
        now = time.time()
        with self.db:
            self.db.executemany("INSERT INTO messages(account_id, content, ts) VALUES(?,?,?)",
                                [(account_id, c, now) for c in contents])
            n, total = self.db.execute("SELECT COUNT(*), COALESCE(SUM(LENGTH(content)),0) FROM messages WHERE account_id=?",
                                       (account_id,)).fetchone()
            if n > MAX_QUEUE or total > MAX_MAILBOX_BYTES:
                drop = max(0, n - MAX_QUEUE)
                for mid, size in self.db.execute("SELECT id, LENGTH(content) FROM messages WHERE account_id=? ORDER BY id",
                                                 (account_id,)).fetchall():
                    if drop <= 0 and total <= MAX_MAILBOX_BYTES:
                        break
                    self.db.execute("DELETE FROM messages WHERE id=?", (mid,))
                    drop -= 1
                    total -= size
        return len(contents)

    def pending(self, account_id, limit=100) -> list[dict]:
        rows = self.db.execute("SELECT id, content, ts FROM messages WHERE account_id=? ORDER BY id LIMIT ?",
                               (account_id, limit)).fetchall()
        return [{"id": r[0], "content": b64(r[1]), "ts": r[2]} for r in rows]

    def ack(self, account_id, msg_id: int) -> bool:
        cur = self.db.execute("DELETE FROM messages WHERE id=? AND account_id=?", (msg_id, account_id))
        return cur.rowcount > 0

    # --- załączniki ----------------------------------------------------------------------------
    def attachment_bytes(self, owner: str | None = None) -> int:
        """Żywe bajty załączników: globalnie albo konta."""
        if owner is None:
            return self.db.execute("SELECT COALESCE(SUM(size),0) FROM attachments").fetchone()[0]
        return self.db.execute("SELECT COALESCE(SUM(size),0) FROM attachments WHERE owner=?", (owner,)).fetchone()[0]

    def put_attachment(self, data: bytes, owner: str | None = None) -> str:
        if self.attachment_bytes() + len(data) > MAX_ATTACH_BYTES:
            raise web.HTTPInsufficientStorage(text="attachment storage full")
        if owner is not None and self.attachment_bytes(owner) + len(data) > ACCOUNT_ATT_QUOTA:
            raise web.HTTPInsufficientStorage(text="account attachment quota exceeded")
        att_id = new_id()
        (self.att_dir / att_id).write_bytes(data)
        self.db.execute("INSERT INTO attachments(id, size, ts, owner) VALUES(?,?,?,?)", (att_id, len(data), time.time(), owner))
        return att_id

    def get_attachment(self, att_id: str):
        if not self.db.execute("SELECT 1 FROM attachments WHERE id=?", (att_id,)).fetchone():
            return None
        p = self.att_dir / att_id
        return p.read_bytes() if p.exists() else None

    def delete_attachment(self, att_id: str):
        self.db.execute("DELETE FROM attachments WHERE id=?", (att_id,))
        (self.att_dir / att_id).unlink(missing_ok=True)

    # --- sprzątanie ------------------------------------------------------------------------------
    def cleanup(self):
        now = time.time()
        with self.db:
            self.db.execute("DELETE FROM messages WHERE ts<?", (now - MSG_TTL,))
            old = [r[0] for r in self.db.execute("SELECT id FROM attachments WHERE ts<?", (now - ATT_TTL,)).fetchall()]
            for a in old:
                self.delete_attachment(a)
        return len(old)


# --- aplikacja ----------------------------------------------------------------------------------------
# Limity tylko w pamięci (klucz = adres / para adres+konto); nigdy nie zapisywane. Słownik jest sprzątany:
# puste kolejki znikają przy każdym sprawdzeniu danego klucza oraz w okresowym przebiegu (_rate_sweep), a gdy urośnie
# ponad _RATE_MAX_KEYS, przebieg wykonuje się od razu.
_rate: dict[str, deque] = defaultdict(deque)
_RATE_MAX_KEYS = 50_000
_rate_last_sweep = 0.0


def _rate_hit(key: str, limit: int, window: float = 60.0) -> bool:
    """Zalicza jedno trafienie dla klucza; False gdy limit w oknie wyczerpany (trafienie nie jest wtedy liczone)."""
    global _rate_last_sweep
    now = time.monotonic()
    q = _rate[key]
    while q and now - q[0] > window:
        q.popleft()
    if len(q) >= limit:
        return False
    q.append(now)
    if len(_rate) > _RATE_MAX_KEYS or now - _rate_last_sweep > 300:
        _rate_sweep(now)
    return True


def _rate_sweep(now: float | None = None, window: float = PREKEY_RESERVE_WINDOW) -> int:
    """Usuwa wygasłe trafienia i puste klucze; gdy mimo to słownik jest za duży, kasuje najstarsze klucze."""
    global _rate_last_sweep
    now = time.monotonic() if now is None else now
    _rate_last_sweep = now
    for k in list(_rate):
        q = _rate[k]
        while q and now - q[0] > window:
            q.popleft()
        if not q:
            del _rate[k]
    if len(_rate) > _RATE_MAX_KEYS:
        for k in sorted(_rate, key=lambda k: _rate[k][-1])[: len(_rate) - _RATE_MAX_KEYS]:
            del _rate[k]
    return len(_rate)


def client_key(req: web.Request) -> str:
    # tylko w pamięci, do limitu zapytań; nigdy nie zapisywane. Nagłówkowi CF wierzymy wyłącznie od lokalnego tunelu.
    if req.remote in TRUSTED_PROXY_IPS:
        cf = req.headers.get("CF-Connecting-IP")
        if cf:
            return cf
    return req.remote or "?"


SECURITY_HEADERS = {
    # rok HSTS: przeglądarka sama wymusi HTTPS przy kolejnych wejściach
    "Strict-Transport-Security": "max-age=31536000",
    "Content-Security-Policy": "default-src 'none'; img-src 'self'; style-src 'self' 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Resource-Policy": "same-origin",
}


def _is_plain_http(req: web.Request) -> bool:
    """Za Cloudflare/tunelem: schemat oryginalnego połączenia z CF-Visitor albo X-Forwarded-Proto."""
    visitor = req.headers.get("CF-Visitor", "")
    if visitor:
        return '"scheme":"http"' in visitor.replace(" ", "")
    proto = req.headers.get("X-Forwarded-Proto")
    return proto == "http"


@web.middleware
async def guard(req: web.Request, handler):
    if _is_plain_http(req):
        # nigdy nie serwujemy strony, APK ani sumy przez zwykły HTTP – ktoś na trasie mógłby je podmienić
        raise web.HTTPMovedPermanently(location=f"https://{req.host}{req.path_qs}", headers=SECURITY_HEADERS)
    if req.path != "/health" and not _rate_hit("ip:" + client_key(req), RATE_PER_MIN):
        raise web.HTTPTooManyRequests(text="slow down")
    resp = await handler(req)
    try:
        resp.headers["Cache-Control"] = "no-store"
        for k, v in SECURITY_HEADERS.items():
            resp.headers.setdefault(k, v)
    except Exception:  # noqa: BLE001 – odpowiedź WebSocket już wysłana
        pass
    return resp


def store(req) -> Store:
    return req.app["store"]


def _ws_protocol_token(req: web.Request) -> str:
    """Token z podprotokołu WebSocket: Sec-WebSocket-Protocol: szept, szept-token.<token> (dla klientów bez nagłówków)."""
    for proto in req.headers.get("Sec-WebSocket-Protocol", "").split(","):
        proto = proto.strip()
        if proto.startswith(WS_TOKEN_PREFIX):
            return proto[len(WS_TOKEN_PREFIX):]
    return ""


def auth(req: web.Request) -> str:
    h = req.headers.get("Authorization", "")
    token = h[7:].strip() if h.startswith("Bearer ") else _ws_protocol_token(req)
    if not token and ALLOW_QUERY_TOKEN:
        token = req.query.get("token", "")
    account_id = store(req).account_by_token(token) if token else None
    if not account_id:
        raise web.HTTPUnauthorized(text="bad token")
    return account_id


async def body_json(req: web.Request) -> dict:
    if req.content_length and req.content_length > 4 * 1024 * 1024:
        raise web.HTTPRequestEntityTooLarge(max_size=4 * 1024 * 1024, actual_size=req.content_length)
    try:
        data = await req.json()
    except Exception:
        raise web.HTTPBadRequest(text="bad json")
    if not isinstance(data, dict):
        raise web.HTTPBadRequest(text="bad json")
    return data


def check_signed(obj, name, need_sig=True):
    if not isinstance(obj, dict) or not isinstance(obj.get("id"), int):
        raise web.HTTPBadRequest(text=f"bad {name}")
    unb64(obj.get("key", ""), 4096)
    if need_sig:
        unb64(obj.get("signature", ""), 256)
    return {"id": obj["id"], "key": obj["key"], **({"signature": obj["signature"]} if need_sig else {})}


def check_key_lists(data):
    prekeys = [check_signed(p, "prekey", need_sig=False) for p in data.get("prekeys", [])[:200]]
    kyber = [check_signed(p, "kyber_prekey") for p in data.get("kyber_prekeys", [])[:200]]
    return prekeys, kyber


async def register(req):
    d = await body_json(req)
    identity_key = d.get("identity_key", "")
    unb64(identity_key, 64)
    reg_id = d.get("registration_id")
    if not isinstance(reg_id, int) or not 1 <= reg_id <= 16380:
        raise web.HTTPBadRequest(text="bad registration_id")
    spk = check_signed(d.get("signed_prekey"), "signed_prekey")
    klr = check_signed(d.get("kyber_last_resort"), "kyber_last_resort")
    prekeys, kyber = check_key_lists(d)
    account_id, token = store(req).create_account(identity_key, reg_id, spk, klr)
    store(req).update_keys(account_id, prekeys=prekeys, kyber_prekeys=kyber)
    return web.json_response({"account_id": account_id, "token": token}, status=201)


async def put_keys(req):
    account_id = auth(req)
    d = await body_json(req)
    spk = check_signed(d["signed_prekey"], "signed_prekey") if d.get("signed_prekey") else None
    klr = check_signed(d["kyber_last_resort"], "kyber_last_resort") if d.get("kyber_last_resort") else None
    prekeys, kyber = check_key_lists(d)
    store(req).update_keys(account_id, spk, klr, prekeys, kyber)
    return web.json_response(store(req).key_counts(account_id))


async def get_key_counts(req):
    return web.json_response(store(req).key_counts(auth(req)))


async def get_bundle(req):
    account_id = req.match_info["account_id"]
    ip = client_key(req)
    if not _rate_hit(f"bundle:{ip}:{account_id}", BUNDLE_RATE_PER_MIN):
        raise web.HTTPTooManyRequests(text="slow down")
    # rezerwa: ten sam adres dostaje z ostatnich PREKEY_RESERVE kluczy jednorazowych najwyżej jeden na godzinę
    allow_reserve = _rate_hit(f"prekey:{ip}:{account_id}", 1, PREKEY_RESERVE_WINDOW)
    bundle = store(req).take_bundle(account_id, allow_reserve=allow_reserve)
    if not bundle:
        raise web.HTTPNotFound(text="no such account")
    return web.json_response(bundle)


async def put_messages(req):
    account_id = req.match_info["account_id"]
    if not store(req).account_exists(account_id):
        raise web.HTTPNotFound(text="no such account")
    d = await body_json(req)
    msgs = d.get("messages")
    if not isinstance(msgs, list) or not 1 <= len(msgs) <= 50:
        raise web.HTTPBadRequest(text="bad messages")
    contents = [unb64((m or {}).get("content", "")) for m in msgs]
    if any(len(c) == 0 or len(c) > MAX_MSG for c in contents):
        raise web.HTTPBadRequest(text="bad message size")
    key = f"msg:{client_key(req)}:{account_id}"
    if not all(_rate_hit(key, MSG_RATE_PER_RECIPIENT) for _ in contents):
        raise web.HTTPTooManyRequests(text="slow down")
    n = store(req).enqueue(account_id, contents)
    req.app["wake"](account_id)
    return web.json_response({"queued": n})


async def get_messages(req):
    return web.json_response({"messages": store(req).pending(auth(req))})


async def ack_message(req):
    account_id = auth(req)
    try:
        msg_id = int(req.match_info["id"])
    except ValueError:
        raise web.HTTPBadRequest(text="bad id")
    store(req).ack(account_id, msg_id)
    return web.json_response({"ok": True})


async def websocket(req):
    account_id = auth(req)
    # klient uwierzytelniający się podprotokołem oferuje "szept, szept-token.<token>"; odsyłamy "szept"
    ws = web.WebSocketResponse(heartbeat=HEARTBEAT, max_msg_size=64 * 1024, protocols=(WS_PROTOCOL,))
    await ws.prepare(req)
    sockets: dict[str, set] = req.app["sockets"]
    sockets[account_id].add(ws)
    wake = asyncio.Event()
    waiters: dict[str, set] = req.app["waiters"]
    waiters[account_id].add(wake)
    sent: set[int] = set()
    try:
        while not ws.closed:
            for m in store(req).pending(account_id):
                if m["id"] not in sent:
                    sent.add(m["id"])
                    await ws.send_json({"type": "message", **m})
            wake.clear()
            recv = asyncio.create_task(ws.receive())
            waiter = asyncio.create_task(wake.wait())
            done, _ = await asyncio.wait({recv, waiter}, return_when=asyncio.FIRST_COMPLETED)
            if recv in done:
                msg = recv.result()
                if msg.type == WSMsgType.TEXT:
                    try:
                        d = json.loads(msg.data)
                    except ValueError:
                        d = {}
                    if d.get("type") == "ack" and isinstance(d.get("id"), int):
                        store(req).ack(account_id, d["id"])
                        sent.discard(d["id"])
                elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED, WSMsgType.ERROR):
                    break
            else:
                recv.cancel()
                try:
                    await recv
                except (asyncio.CancelledError, Exception):
                    pass
            if waiter in done:
                waiter.result()
            else:
                waiter.cancel()
    finally:
        sockets[account_id].discard(ws)
        waiters[account_id].discard(wake)
        if not waiters[account_id]:
            waiters.pop(account_id, None)
            sockets.pop(account_id, None)
    return ws


async def post_attachment(req):
    # Token konta chroni dysk (kwota na konto + globalna). Załącznik nie wskazuje odbiorcy ani koperty, więc
    # routing wiadomości pozostaje anonimowy wobec serwera: wie tylko, które konto wgrało blob, nie dla kogo.
    owner = auth(req) if ATTACH_REQUIRE_AUTH else None
    if req.content_length and req.content_length > MAX_ATT:
        raise web.HTTPRequestEntityTooLarge(max_size=MAX_ATT, actual_size=req.content_length)
    if not _rate_hit("att:" + client_key(req), ATT_RATE_PER_MIN):
        raise web.HTTPTooManyRequests(text="slow down")
    data = await req.read()
    if not data or len(data) > MAX_ATT:
        raise web.HTTPBadRequest(text="bad size")
    return web.json_response({"id": store(req).put_attachment(data, owner)}, status=201)


def _att_id(req) -> str:
    att_id = req.match_info["id"]
    if not (10 <= len(att_id) <= 32 and att_id.replace("-", "").replace("_", "").isalnum()):
        raise web.HTTPNotFound(text="no such attachment")
    return att_id


async def get_attachment(req):
    data = store(req).get_attachment(_att_id(req))
    if data is None:
        raise web.HTTPNotFound(text="no such attachment")
    return web.Response(body=data, content_type="application/octet-stream")


async def delete_attachment(req):
    store(req).delete_attachment(_att_id(req))
    return web.json_response({"ok": True})


async def delete_account(req):
    store(req).delete_account(auth(req))
    return web.json_response({"ok": True})


async def health(req):
    return web.json_response({"ok": True, "service": "szept", "v": 1})


STATIC = Path(__file__).parent / "static"


def _read(name: str, default: str = "") -> str:
    try:
        return (STATIC / name).read_text().strip()
    except OSError:
        return default


async def index(req):
    # wersja i suma APK wstawiane z plików obok APK – strona nigdy nie pokazuje nieaktualnej sumy
    html = (STATIC / "index.html").read_text()
    html = html.replace("{{APK_VERSION}}", _read("szept.apk.version", "?")).replace("{{APK_SHA256}}", _read("szept.apk.sha256", "brak"))
    return web.Response(text=html, content_type="text/html", charset="utf-8")


async def privacy(req):
    return web.Response(text=(STATIC / "prywatnosc.html").read_text(), content_type="text/html", charset="utf-8")


async def icon(req):
    return web.FileResponse(STATIC / "ikona.png")


async def apk(req):
    return web.FileResponse(STATIC / "szept.apk", headers={"Content-Type": "application/vnd.android.package-archive",
                                                           "Content-Disposition": 'attachment; filename="szept.apk"'})


async def apk_sum(req):
    return web.FileResponse(STATIC / "szept.apk.sha256", headers={"Content-Type": "text/plain"})


async def cleanup_loop(app):
    while True:
        try:
            n = app["store"].cleanup()
            if n:
                log.info("cleanup: %d attachments", n)
            _rate_sweep()
        except Exception as e:  # noqa: BLE001
            log.warning("cleanup: %s", e)
        await asyncio.sleep(3600)


async def on_startup(app):
    app["cleanup"] = asyncio.create_task(cleanup_loop(app))


async def on_cleanup(app):
    app["cleanup"].cancel()


def make_app(data_dir: Path = DATA) -> web.Application:
    app = web.Application(middlewares=[guard], client_max_size=MAX_ATT + 1024)
    app["store"] = Store(data_dir / "szept.db")
    app["sockets"] = defaultdict(set)
    app["waiters"] = defaultdict(set)

    def wake(account_id: str):
        for ev in app["waiters"].get(account_id, ()):
            ev.set()
    app["wake"] = wake
    app.add_routes([
        web.get("/", index),
        web.get("/prywatnosc", privacy),
        web.get("/ikona.png", icon),
        web.get("/szept.apk", apk),
        web.get("/szept.apk.sha256", apk_sum),
        web.get("/health", health),
        web.post("/v1/accounts", register),
        web.delete("/v1/accounts", delete_account),
        web.put("/v1/keys", put_keys),
        web.get("/v1/keys", get_key_counts),
        web.get("/v1/keys/{account_id}", get_bundle),
        web.put("/v1/messages/{account_id}", put_messages),
        web.get("/v1/messages", get_messages),
        web.delete("/v1/messages/{id}", ack_message),
        web.get("/v1/ws", websocket),
        web.post("/v1/attachments", post_attachment),
        web.get("/v1/attachments/{id}", get_attachment),
        web.delete("/v1/attachments/{id}", delete_attachment),
    ])
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    return app


if __name__ == "__main__":
    log.info("szept server :%d data=%s", PORT, DATA)
    web.run_app(make_app(), host="127.0.0.1", port=PORT, access_log=None, print=None)
