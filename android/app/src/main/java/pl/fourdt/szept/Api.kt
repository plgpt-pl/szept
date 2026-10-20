package pl.fourdt.szept

import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

@Serializable data class Registered(val account_id: String, val token: String)
@Serializable data class Pending(val id: Long, val content: String, val ts: Double)
@Serializable private data class PendingList(val messages: List<Pending>)
@Serializable data class KeyCounts(val prekeys: Int, val kyber_prekeys: Int)
@Serializable private data class Uploaded(val id: String)
@Serializable private data class Health(val ok: Boolean)
@Serializable private data class DeliverBody(val messages: List<Item>) { @Serializable data class Item(val content: String) }

/** Klient serwera-skrzynki: tylko losowy account_id i token, bez ciasteczek i cache. */
class SzeptApi(@Volatile var baseUrl: String, @Volatile var token: String?) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()
    private val BIN = "application/octet-stream".toMediaType()

    private fun req(method: String, path: String, body: String? = null, bytes: ByteArray? = null, auth: Boolean): Request {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + "/" + path)
        if (auth) b.header("Authorization", "Bearer " + (token ?: throw SzeptException(Strings.get(R.string.not_registered))))
        val rb = when { body != null -> body.toRequestBody(JSON); bytes != null -> bytes.toRequestBody(BIN); method == "GET" -> null; else -> ByteArray(0).toRequestBody(null) }
        return b.method(method, rb).build()
    }

    private fun send(r: Request): ByteArray = client.newCall(r).execute().use { resp ->
        val data = resp.body?.bytes() ?: ByteArray(0)
        if (!resp.isSuccessful) throw SzeptException(Strings.get(R.string.server_error, resp.code, String(data).take(120)))
        data
    }
    private inline fun <reified T> call(method: String, path: String, body: String? = null, auth: Boolean = true): T =
        json.decodeFromString(String(send(req(method, path, body, auth = auth))))

    fun health(): Boolean = call<Health>("GET", "health", auth = false).ok
    fun register(p: RegistrationPayload): Registered = call("POST", "v1/accounts", json.encodeToString(RegistrationPayload.serializer(), p), auth = false)
    fun uploadKeys(p: KeyUploadPayload): KeyCounts = call("PUT", "v1/keys", json.encodeToString(KeyUploadPayload.serializer(), p))
    fun keyCounts(): KeyCounts = call("GET", "v1/keys")
    fun bundle(accountId: String): BundleJSON = call("GET", "v1/keys/$accountId", auth = false)
    /** Wysyłka bez uwierzytelnienia – serwer nie wie, kto jest nadawcą. */
    fun deliver(sealed: List<ByteArray>, to: String) { send(req("PUT", "v1/messages/$to", json.encodeToString(DeliverBody.serializer(), DeliverBody(sealed.map { DeliverBody.Item(it.b64()) })), auth = false)) }
    fun pending(): List<Pending> = call<PendingList>("GET", "v1/messages").messages
    fun ack(id: Long) { send(req("DELETE", "v1/messages/$id", auth = true)) }
    fun uploadAttachment(data: ByteArray): String = json.decodeFromString<Uploaded>(String(send(req("POST", "v1/attachments", bytes = data, auth = true)))).id
    fun downloadAttachment(id: String): ByteArray = send(req("GET", "v1/attachments/$id", auth = false)).also {
        try { send(req("DELETE", "v1/attachments/$id", auth = false)) } catch (_: Exception) {}
    }
    fun deleteAccount() { send(req("DELETE", "v1/accounts", auth = true)) }

    fun webSocket(listener: WebSocketListener): WebSocket {
        val url = baseUrl.trimEnd('/').replaceFirst("http", "ws") + "/v1/ws"
        return client.newWebSocket(Request.Builder().url(url).header("Authorization", "Bearer " + (token ?: throw SzeptException(Strings.get(R.string.not_registered)))).build(), listener)
    }
}
