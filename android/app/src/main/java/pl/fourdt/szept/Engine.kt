package pl.fourdt.szept

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.UntrustedIdentityException
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object Config {
    const val DEFAULT_SERVER = "https://szept.plgpt.pl"
    const val PREKEY_LOW_WATER = 10
    const val MAX_IMAGE_SIDE = 1600
}

enum class Connection { OFFLINE, CONNECTING, ONLINE }

object Durations {
    val options = listOf(0 to "Wyłączone", 30 to "30 sekund", 300 to "5 minut", 3600 to "1 godzina", 86400 to "1 dzień", 604800 to "1 tydzień")
    fun label(s: Int) = options.firstOrNull { it.first == s }?.second ?: "$s s"
}

/** Serce apki (odpowiednik Engine z iOS): rejestracja, wysyłka/odbiór, WebSocket, znikające wiadomości. */
class Engine(private val app: Context, keysDir: File = File(app.noBackupFilesDir, "keys"), val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
             private val prefsName: String = "szept", dbName: String = "szept.db") {
    val db = Db(app, dbName)
    val store = SignalStore(keysDir)
    private val prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    val proto = SzeptProtocol(store, prefs.getString("account_id", null))
    val api = SzeptApi(prefs.getString("server", null) ?: Config.DEFAULT_SERVER, prefs.getString("token", null))
    val registered = MutableStateFlow(proto.accountId != null && api.token != null)
    val connection = MutableStateFlow(Connection.OFFLINE)
    val lastError = MutableStateFlow<String?>(null)
    var onIncoming: ((Contact) -> Unit)? = null
    var onIncomingGroup: ((Group) -> Unit)? = null
    val accountId get() = proto.accountId
    val mediaDir: File get() = File(app.noBackupFilesDir, "media").also { it.mkdirs() }

    var displayName: String
        get() = prefs.getString("display_name", "") ?: ""
        set(v) { prefs.edit().putString("display_name", v).apply() }
    var serverUrl: String
        get() = api.baseUrl
        set(v) { if (v.startsWith("http")) { prefs.edit().putString("server", v).apply(); api.baseUrl = v } }
    var listenInBackground: Boolean
        get() = prefs.getBoolean("listen_bg", true)
        set(v) { prefs.edit().putBoolean("listen_bg", v).apply() }
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) { prefs.edit().putBoolean("app_lock", v).apply() }

    val myLink: ContactLink? get() = accountId?.let { ContactLink(it, proto.identityKey.serialize(), displayName.ifEmpty { null }) }

    // --- rejestracja ---
    suspend fun register() = withContext(Dispatchers.IO) {
        val r = api.register(proto.makeRegistration())
        prefs.edit().putString("token", r.token).putString("account_id", r.account_id).apply()
        api.token = r.token; proto.accountId = r.account_id
        registered.value = true
        start()
    }

    suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        stop()
        try { api.deleteAccount() } catch (_: Exception) {}
        prefs.edit().clear().apply()
        db.wipe(); store.destroy(); mediaDir.deleteRecursively()
        registered.value = false
    }

    // --- cykl życia ---
    private var socketJob: Job? = null
    private var expiryJob: Job? = null
    private var socket: WebSocket? = null

    fun start() {
        if (!registered.value) return
        if (expiryJob?.isActive != true) expiryJob = scope.launch { while (isActive) { purgeExpired(); delay(20_000) } }
        if (socketJob?.isActive != true) connect()
        scope.launch { topUpKeysIfNeeded() }
    }

    fun stop() {
        socketJob?.cancel(); socketJob = null
        socket?.close(1000, null); socket = null
        expiryJob?.cancel(); expiryJob = null
        connection.value = Connection.OFFLINE
    }

    fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch {
            var backoff = 1000L
            while (isActive) {
                connection.value = Connection.CONNECTING
                try {
                    drainPending()
                    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val ws = api.webSocket(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { connection.value = Connection.ONLINE }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            try {
                                val o = JSONObject(text)
                                if (o.optString("type") == "message") {
                                    handleIncoming(o.getString("content").unb64())
                                    webSocket.send("""{"type":"ack","id":${o.getLong("id")}}""")
                                }
                            } catch (e: Exception) { lastError.value = e.message }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                    })
                    socket = ws
                    backoff = 1000
                    closed.await()
                } catch (e: Exception) { lastError.value = e.message }
                connection.value = Connection.OFFLINE
                delay(backoff); backoff = min(backoff * 2, 30_000)
            }
        }
    }

    fun drainPending() {
        for (m in api.pending()) { handleIncoming(m.content.unb64()); try { api.ack(m.id) } catch (_: Exception) {} }
    }

    fun topUpKeysIfNeeded() {
        if (!registered.value) return
        try {
            val c = api.keyCounts()
            if (min(c.prekeys, c.kyber_prekeys) < Config.PREKEY_LOW_WATER) api.uploadKeys(proto.makeTopUp())
        } catch (e: Exception) { lastError.value = e.message }
    }

    // --- kontakty ---
    fun addContact(link: ContactLink, name: String?): Contact {
        db.contact(link.accountId)?.let { existing ->
            if (!existing.identityKey.contentEquals(link.identityKey)) throw SzeptException("Klucz tożsamości kontaktu nie zgadza się z zapisanym.")
            if (!name.isNullOrBlank()) { existing.name = name; db.save(existing) }
            return existing
        }
        val c = Contact(link.accountId, name?.takeIf { it.isNotBlank() } ?: link.name ?: "Kontakt ${link.accountId.take(6)}", link.identityKey)
        store.pinIdentity(IdentityKey(link.identityKey), SzeptProtocol.address(link.accountId))
        db.save(c)
        return c
    }

    fun acceptNewIdentity(c: Contact) {
        store.forgetIdentityAndSession(SzeptProtocol.address(c.id))
        store.pinIdentity(IdentityKey(c.identityKey), SzeptProtocol.address(c.id))
        c.identityChanged = false; c.verified = false; db.save(c)
    }

    fun delete(c: Contact) {
        db.messages(c.id).forEach { removeMedia(it) }
        store.forgetIdentityAndSession(SzeptProtocol.address(c.id))
        db.deleteContact(c.id)
    }

    // --- wysyłanie ---
    private fun ensureSession(c: Contact) { if (!proto.hasSession(c.id)) proto.processBundle(api.bundle(c.id), c.identityKey) }

    private fun deliver(content: MessageContent, c: Contact) {
        ensureSession(c)
        val withName = if (displayName.isNotEmpty()) content.copy(name = displayName) else content
        api.deliver(listOf(proto.seal(json.encodeToString(MessageContent.serializer(), withName).toByteArray(), c.id, c.identityKey)), c.id)
    }

    suspend fun sendText(text: String, c: Contact) = withContext(Dispatchers.IO) {
        val t = text.trim(); if (t.isEmpty()) return@withContext
        val m = Message(UUID.randomUUID().toString(), c.id, t, true, System.currentTimeMillis())
        if (c.disappearAfter > 0) m.expiresAt = m.sentAt + c.disappearAfter * 1000L
        c.lastActivity = m.sentAt; db.save(c); db.save(m)
        send(m, MessageContent(m.id, m.sentAt / 1000.0, "text", text = t, expire = c.disappearAfter.takeIf { it > 0 }), c)
    }

    suspend fun sendImage(bitmap: Bitmap, c: Contact) = withContext(Dispatchers.IO) {
        val (jpeg, w, h) = prepareImage(bitmap)
        val m = Message(UUID.randomUUID().toString(), c.id, "", true, System.currentTimeMillis(), attW = w, attH = h, attachmentFile = saveMedia(jpeg))
        if (c.disappearAfter > 0) m.expiresAt = m.sentAt + c.disappearAfter * 1000L
        c.lastActivity = m.sentAt; db.save(c); db.save(m)
        try {
            val (ct, kn) = SzeptProtocol.encryptAttachment(jpeg)
            val id = api.uploadAttachment(ct)
            send(m, MessageContent(m.id, m.sentAt / 1000.0, "image", att = Attachment(id, kn.first.b64(), kn.second.b64(), ct.size, w, h),
                expire = c.disappearAfter.takeIf { it > 0 }), c)
        } catch (e: Exception) { m.status = Message.FAILED; db.save(m); lastError.value = e.message }
    }

    suspend fun retry(m: Message) = withContext(Dispatchers.IO) {
        m.status = Message.PENDING; db.save(m)
        val file = m.attachmentFile?.let { File(mediaDir, it) }
        if (m.groupId != null) {
            val g = db.group(m.groupId) ?: return@withContext
            if (file != null && file.exists()) { db.deleteMessage(m.id); sendImage(BitmapFactory.decodeFile(file.path), g) }
            else sendGroup(m, MessageContent(m.id, m.sentAt / 1000.0, "text", text = m.text, expire = g.disappearAfter.takeIf { it > 0 }, g = g.id), g)
            return@withContext
        }
        val c = db.contact(m.contactId ?: return@withContext) ?: return@withContext
        if (file != null && file.exists()) { db.deleteMessage(m.id); sendImage(BitmapFactory.decodeFile(file.path), c) }
        else send(m, MessageContent(m.id, m.sentAt / 1000.0, "text", text = m.text, expire = c.disappearAfter.takeIf { it > 0 }), c)
    }

    private fun send(m: Message, content: MessageContent, c: Contact) {
        try { deliver(content, c); m.status = Message.SENT } catch (e: Exception) { m.status = Message.FAILED; lastError.value = e.message }
        db.save(m)
    }

    suspend fun setDisappear(seconds: Int, c: Contact) = withContext(Dispatchers.IO) {
        c.disappearAfter = seconds; db.save(c)
        db.save(Message(UUID.randomUUID().toString(), c.id, if (seconds > 0) "Znikające wiadomości: ${Durations.label(seconds)}" else "Znikające wiadomości wyłączone", true, System.currentTimeMillis(), Message.SENT))
        try { deliver(MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "timer", expire = seconds), c) } catch (e: Exception) { lastError.value = e.message }
    }

    private fun sendReceipt(id: String, c: Contact) = scope.launch {
        try { deliver(MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "receipt", ref = id), c) } catch (_: Exception) {}
    }

    // --- grupy (docs/GRUPY.md: rozsyłanie pairwise, serwer nic nie wie o grupie) ---
    private fun note(g: Group, text: String, mine: Boolean) =
        db.save(Message(UUID.randomUUID().toString(), null, text, mine, System.currentTimeMillis(), if (mine) Message.SENT else Message.DELIVERED, groupId = g.id))

    /** Pełny stan grupy do `op: set` – lista członków ŁĄCZNIE ze mną. */
    private fun setContent(g: Group): MessageContent {
        val me = Member(accountId ?: throw SzeptException("Najpierw utwórz tożsamość."), proto.identityKey.serialize().b64(), displayName.ifEmpty { null })
        val others = g.members.mapNotNull { id -> db.contact(id)?.let { Member(it.id, it.identityKey.b64(), it.name) } }
        return MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "group", g = g.id, gname = g.name, members = others + me, op = "set")
    }

    /** Wysyłka do każdego członka osobno; true gdy wszystkie doszły. */
    private fun deliverToGroup(content: MessageContent, g: Group, recipients: List<String> = g.members): Boolean {
        var ok = true
        for (id in recipients) {
            val c = db.contact(id)
            if (c == null) { ok = false; continue }
            try { deliver(content, c) } catch (e: Exception) { ok = false; lastError.value = e.message }
        }
        return ok
    }

    private fun sendGroup(m: Message, content: MessageContent, g: Group) {
        m.status = if (deliverToGroup(content, g)) Message.SENT else Message.FAILED
        db.save(m)
    }

    suspend fun createGroup(name: String, members: List<Contact>): Group = withContext(Dispatchers.IO) {
        val g = Group(UUID.randomUUID().toString().uppercase(), name.trim().ifEmpty { "Grupa" }, members.map { it.id }.distinct())
        db.save(g)
        note(g, "Utworzyłeś(aś) grupę „${g.name}”", true)
        deliverToGroup(setContent(g), g)
        g
    }

    suspend fun renameGroup(g: Group, name: String) = withContext(Dispatchers.IO) {
        val n = name.trim(); if (n.isEmpty() || n == g.name) return@withContext
        g.name = n; g.lastActivity = System.currentTimeMillis(); db.save(g)
        note(g, "Zmieniłeś(aś) nazwę grupy na „$n”", true)
        deliverToGroup(setContent(g), g)
    }

    suspend fun addMembers(g: Group, contacts: List<Contact>) = withContext(Dispatchers.IO) {
        val new = contacts.map { it.id }.filter { it !in g.members && it != accountId }.distinct()
        if (new.isEmpty()) return@withContext
        g.members = g.members + new; g.lastActivity = System.currentTimeMillis(); db.save(g)
        note(g, "Dodałeś(aś): " + contacts.filter { it.id in new }.joinToString { it.name }, true)
        deliverToGroup(setContent(g), g)
    }

    suspend fun leaveGroup(g: Group) = withContext(Dispatchers.IO) {
        deliverToGroup(MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "group", g = g.id, op = "leave"), g)
        db.messagesForGroup(g.id).forEach { removeMedia(it) }
        db.deleteGroup(g.id)
    }

    suspend fun sendText(text: String, g: Group) = withContext(Dispatchers.IO) {
        val t = text.trim(); if (t.isEmpty()) return@withContext
        val m = Message(UUID.randomUUID().toString(), null, t, true, System.currentTimeMillis(), groupId = g.id)
        if (g.disappearAfter > 0) m.expiresAt = m.sentAt + g.disappearAfter * 1000L
        g.lastActivity = m.sentAt; db.save(g); db.save(m)
        sendGroup(m, MessageContent(m.id, m.sentAt / 1000.0, "text", text = t, expire = g.disappearAfter.takeIf { it > 0 }, g = g.id), g)
    }

    suspend fun sendImage(bitmap: Bitmap, g: Group) = withContext(Dispatchers.IO) {
        val (jpeg, w, h) = prepareImage(bitmap)
        val m = Message(UUID.randomUUID().toString(), null, "", true, System.currentTimeMillis(), attW = w, attH = h, attachmentFile = saveMedia(jpeg), groupId = g.id)
        if (g.disappearAfter > 0) m.expiresAt = m.sentAt + g.disappearAfter * 1000L
        g.lastActivity = m.sentAt; db.save(g); db.save(m)
        try {
            val (ct, kn) = SzeptProtocol.encryptAttachment(jpeg)
            val id = api.uploadAttachment(ct)
            sendGroup(m, MessageContent(m.id, m.sentAt / 1000.0, "image", att = Attachment(id, kn.first.b64(), kn.second.b64(), ct.size, w, h),
                expire = g.disappearAfter.takeIf { it > 0 }, g = g.id), g)
        } catch (e: Exception) { m.status = Message.FAILED; db.save(m); lastError.value = e.message }
    }

    suspend fun setDisappear(seconds: Int, g: Group) = withContext(Dispatchers.IO) {
        g.disappearAfter = seconds; db.save(g)
        note(g, if (seconds > 0) "Znikające wiadomości: ${Durations.label(seconds)}" else "Znikające wiadomości wyłączone", true)
        deliverToGroup(MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "timer", expire = seconds, g = g.id), g)
    }

    fun markRead(g: Group) { if (g.unread != 0) { g.unread = 0; db.save(g) } }

    /** Nowi członkowie z `op: set`: nieznani trafiają do kontaktów z kluczem z wiadomości (TOFU); istniejącym klucza NIE ruszamy. */
    private fun absorbMembers(members: List<Member>) {
        for (m in members) {
            if (m.i == accountId || db.contact(m.i) != null) continue
            val key = try { m.k.unb64().also { IdentityKey(it) } } catch (e: Exception) { continue }
            val c = Contact(m.i, m.n?.takeIf { it.isNotBlank() } ?: "Kontakt ${m.i.take(6)}", key, fromUnknown = true)
            try { store.pinIdentity(IdentityKey(key), SzeptProtocol.address(m.i)) } catch (_: Exception) {}
            db.save(c)
        }
    }

    private fun handleGroupContent(content: MessageContent, c: Contact, now: Long) {
        val gid = content.g ?: return
        val existing = db.group(gid)
        when (content.kind) {
            "group" -> when (content.op) {
                "set" -> {
                    val members = content.members ?: return
                    if (members.none { it.i == c.id }) return                         // nadawca musi być na liście
                    if (existing != null && c.id !in existing.members) return          // nie-członek nie zmienia znanej grupy
                    absorbMembers(members)
                    val others = members.map { it.i }.filter { it != accountId }.distinct()
                    val g = existing ?: Group(gid, content.gname ?: "Grupa", others)
                    val wasNew = existing == null
                    g.members = others; content.gname?.let { g.name = it }; g.lastActivity = now; if (wasNew) g.unread++
                    db.save(g)
                    note(g, if (wasNew) "${c.name} dodał(a) Cię do grupy „${g.name}”" else "${c.name} zmienił(a) grupę („${g.name}”, ${others.size + 1} os.)", false)
                    if (wasNew) onIncomingGroup?.invoke(g)
                }
                "leave" -> {
                    val g = existing ?: return
                    if (c.id !in g.members) return
                    g.members = g.members - c.id; g.lastActivity = now; db.save(g)
                    note(g, "${c.name} opuścił(a) grupę", false)
                }
            }
            "text" -> {
                val g = existing ?: return; if (c.id !in g.members) return
                if (db.message(content.id) != null) return
                val m = Message(content.id, null, content.text ?: "", false, (content.ts * 1000).toLong(), Message.DELIVERED, groupId = g.id, senderId = c.id)
                content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
                db.save(m); g.unread++; g.lastActivity = now; db.save(g)
                onIncomingGroup?.invoke(g)                                             // bez potwierdzeń odbioru dla grup
            }
            "image" -> content.att?.let { att ->
                val g = existing ?: return; if (c.id !in g.members) return
                if (db.message(content.id) != null) return
                val m = Message(content.id, null, "", false, (content.ts * 1000).toLong(), Message.DELIVERED, attW = att.w, attH = att.h, groupId = g.id, senderId = c.id)
                content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
                db.save(m); g.unread++; g.lastActivity = now; db.save(g)
                onIncomingGroup?.invoke(g)
                scope.launch {
                    try { m.attachmentFile = saveMedia(SzeptProtocol.decryptAttachment(api.downloadAttachment(att.id), att.key.unb64(), att.nonce.unb64())) }
                    catch (e: Exception) { m.text = "⚠️ Nie udało się pobrać zdjęcia" }
                    db.save(m)
                }
            }
            "timer" -> {
                val g = existing ?: return; if (c.id !in g.members) return
                g.disappearAfter = content.expire ?: 0; g.lastActivity = now; db.save(g)
                note(g, if (g.disappearAfter > 0) "${c.name}: znikające wiadomości ${Durations.label(g.disappearAfter)}" else "${c.name} wyłączył(a) znikające wiadomości", false)
            }
        }
    }

    // --- odbiór ---
    @Synchronized
    fun handleIncoming(sealed: ByteArray) {
        val opened = try { proto.open(sealed) } catch (e: Exception) {
            lastError.value = if (e is UntrustedIdentityException) "Klucz kontaktu się zmienił – zaakceptuj go w informacjach o kontakcie." else "Nie udało się odszyfrować wiadomości: ${e.message}"
            return
        }
        val content = try { json.decodeFromString(MessageContent.serializer(), String(opened.plaintext)) } catch (e: Exception) { return }
        val identity = opened.senderIdentity.serialize()
        val c = db.contact(opened.sender)?.also { known ->
            if (!known.identityKey.contentEquals(identity)) { known.identityKey = identity; known.identityChanged = true; known.verified = false }
        } ?: Contact(opened.sender, content.name ?: "Nieznany ${opened.sender.take(6)}", identity, fromUnknown = true)
        if (!content.name.isNullOrEmpty() && c.fromUnknown && (c.name.startsWith("Nieznany") || c.name.startsWith("Kontakt "))) c.name = content.name
        val now = System.currentTimeMillis()
        if (content.g != null) { db.save(c); handleGroupContent(content, c, now); return }
        when (content.kind) {
            "text" -> if (db.message(content.id) == null) {
                val m = Message(content.id, c.id, content.text ?: "", false, (content.ts * 1000).toLong(), Message.DELIVERED)
                content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
                db.save(m); c.unread++; c.lastActivity = now
                sendReceipt(content.id, c); onIncoming?.invoke(c)
            }
            "image" -> content.att?.let { att ->
                if (db.message(content.id) != null) return@let
                val m = Message(content.id, c.id, "", false, (content.ts * 1000).toLong(), Message.DELIVERED, attW = att.w, attH = att.h)
                content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
                db.save(m); c.unread++; c.lastActivity = now
                onIncoming?.invoke(c)
                scope.launch {
                    try { m.attachmentFile = saveMedia(SzeptProtocol.decryptAttachment(api.downloadAttachment(att.id), att.key.unb64(), att.nonce.unb64())) }
                    catch (e: Exception) { m.text = "⚠️ Nie udało się pobrać zdjęcia" }
                    db.save(m); sendReceipt(content.id, c)
                }
            }
            "receipt" -> content.ref?.let { db.message(it) }?.let { it.status = Message.DELIVERED; db.save(it) }
            "timer" -> {
                c.disappearAfter = content.expire ?: 0
                db.save(Message(UUID.randomUUID().toString(), c.id, if (c.disappearAfter > 0) "${c.name}: znikające wiadomości ${Durations.label(c.disappearAfter)}" else "${c.name} wyłączył(a) znikające wiadomości", false, now, Message.DELIVERED))
            }
        }
        db.save(c)
    }

    fun markRead(c: Contact) { if (c.unread != 0) { c.unread = 0; db.save(c) } }

    fun purgeExpired() {
        val ex = db.expired(System.currentTimeMillis()); if (ex.isEmpty()) return
        ex.forEach { removeMedia(it); db.deleteMessage(it.id) }
    }

    // --- media ---
    private fun saveMedia(data: ByteArray): String { val name = UUID.randomUUID().toString() + ".jpg"; File(mediaDir, name).writeBytes(data); return name }
    private fun removeMedia(m: Message) { m.attachmentFile?.let { File(mediaDir, it).delete() } }

    private fun prepareImage(bmp: Bitmap): Triple<ByteArray, Int, Int> {
        val side = max(bmp.width, bmp.height)
        val scale = min(1f, Config.MAX_IMAGE_SIDE.toFloat() / side)
        val w = (bmp.width * scale).toInt(); val h = (bmp.height * scale).toInt()
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val out = ByteArrayOutputStream(); scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)   // bez EXIF/GPS
        return Triple(out.toByteArray(), w, h)
    }
}
