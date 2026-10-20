"""Powiadomienia push dla Szepta (APNs, docs/WERSJA-0.3.md §3).

Treść pusha jest zawsze generyczna („Nowa wiadomość”) – Apple dowiaduje się tylko, że na dany telefon coś przyszło,
bez nadawcy i bez treści. Uwierzytelnienie tokenem JWT ES256 podpisanym kluczem .p8 z portalu deweloperskiego.

Konfiguracja (env): SZEPT_APNS_KEY_PATH, SZEPT_APNS_KEY_ID, SZEPT_APNS_TEAM_ID, SZEPT_APNS_TOPIC.
Brak klucza = push wyłączony (NullPush), reszta serwera działa normalnie.
"""
import json, logging, os, time

log = logging.getLogger("szept.push")

APNS_HOSTS = {"production": "https://api.push.apple.com", "sandbox": "https://api.sandbox.push.apple.com"}
PAYLOAD = {"aps": {"alert": {"title": "Szept", "body": "Nowa wiadomość"}, "sound": "default",
                   "content-available": 1, "thread-id": "szept"}}
JWT_TTL = 50 * 60            # Apple wymaga odświeżenia tokenu co 20-60 min
# odpowiedzi APNs, po których token jest bezużyteczny i trzeba go usunąć
DEAD_TOKEN_REASONS = {"BadDeviceToken", "Unregistered", "DeviceTokenNotForTopic", "ExpiredToken"}


class NullPush:
    """Push wyłączony (brak klucza). `send` zwraca 0, nic nie wysyła."""
    enabled = False

    async def send(self, token: str, env: str) -> int:  # noqa: ARG002
        return 0

    async def close(self) -> None:
        return None


class ApnsPush:
    enabled = True

    def __init__(self, key_path: str, key_id: str, team_id: str, topic: str):
        import httpx  # import tu, żeby serwer działał bez httpx, gdy push wyłączony
        self.key = open(key_path).read()
        self.key_id, self.team_id, self.topic = key_id, team_id, topic
        self._jwt = ""
        self._jwt_ts = 0.0
        self._clients = {env: httpx.AsyncClient(http2=True, base_url=host, timeout=10.0) for env, host in APNS_HOSTS.items()}

    def jwt(self) -> str:
        import jwt as pyjwt
        now = time.time()
        if not self._jwt or now - self._jwt_ts > JWT_TTL:
            self._jwt = pyjwt.encode({"iss": self.team_id, "iat": int(now)}, self.key, algorithm="ES256", headers={"kid": self.key_id})
            self._jwt_ts = now
        return self._jwt

    async def send(self, token: str, env: str) -> int:
        """Wysyła generyczny push. Zwraca status HTTP APNs; 410 także gdy APNs odpowie 400 z powodem „martwy token”."""
        client = self._clients.get(env) or self._clients["production"]
        headers = {"authorization": f"bearer {self.jwt()}", "apns-topic": self.topic, "apns-push-type": "alert",
                   "apns-collapse-id": "szept", "apns-priority": "10", "apns-expiration": str(int(time.time()) + 86400)}
        try:
            r = await client.post(f"/3/device/{token}", content=json.dumps(PAYLOAD).encode(), headers=headers)
        except Exception as e:  # noqa: BLE001 – sieć/TLS; nie logujemy tokenu
            log.warning("apns: błąd połączenia (%s): %s", env, type(e).__name__)
            return 0
        if r.status_code == 200:
            return 200
        try:
            reason = r.json().get("reason", "")
        except ValueError:
            reason = ""
        log.warning("apns: %s %s (…%s, %s)", r.status_code, reason, token[-6:], env)
        if r.status_code == 410 or (r.status_code == 400 and reason in DEAD_TOKEN_REASONS):
            return 410
        return r.status_code

    async def close(self) -> None:
        for c in self._clients.values():
            await c.aclose()


def from_env():
    """ApnsPush z konfiguracji środowiska albo NullPush, gdy brak klucza."""
    key_path = os.environ.get("SZEPT_APNS_KEY_PATH", "")
    key_id = os.environ.get("SZEPT_APNS_KEY_ID", "")
    team_id = os.environ.get("SZEPT_APNS_TEAM_ID", "")
    topic = os.environ.get("SZEPT_APNS_TOPIC", "pl.plgpt.szept")
    if not (key_path and key_id and team_id):
        log.info("push: wyłączony (ustaw SZEPT_APNS_KEY_PATH, SZEPT_APNS_KEY_ID, SZEPT_APNS_TEAM_ID)")
        return NullPush()
    try:
        p = ApnsPush(key_path, key_id, team_id, topic)
    except Exception as e:  # noqa: BLE001
        log.error("push: wyłączony – nie można wczytać klucza APNs: %s", e)
        return NullPush()
    log.info("push: APNs włączony (key %s, team %s, topic %s)", key_id, team_id, topic)
    return p
