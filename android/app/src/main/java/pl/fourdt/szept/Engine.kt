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
    const val MAX_FILE = 32 * 1024 * 1024          // limit załącznika (serwer: MAX_ATT)
    const val MAX_EMOJI = 8
}

enum class Connection { OFFLINE, CONNECTING, ONLINE }

/** Teksty dla kodu poza Compose (Engine, protokół, API, powiadomienia). Context ustawia Engine / SzeptApp. */
object Strings {
    @Volatile var ctx: Context? = null
    fun get(id: Int, vararg args: Any): String = ctx?.getString(id, *args) ?: "[$id]"
    fun plural(id: Int, n: Int): String = ctx?.resources?.getQuantityString(id, n, n) ?: "$n"
}

object Durations {
    /** sekundy → id zasobu z etykietą */
    val options = listOf(0 to R.string.dur_off, 30 to R.string.dur_30s, 300 to R.string.dur_5m, 3600 to R.string.dur_1h, 86400 to R.string.dur_1d, 604800 to R.string.dur_1w)
    fun label(s: Int) = options.firstOrNull { it.first == s }?.let { Strings.get(it.second) } ?: Strings.get(R.string.dur_seconds, s)
}

/** Serce apki (odpowiednik Engine z iOS): rejestracja, wysyłka/odbiór, WebSocket, znikające wiadomości. */
class Engine(private val app: Context, keysDir: File = File(app.noBackupFilesDir, "keys"), val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
             private val prefsName: String = "szept", dbName: String = "szept.db") {
    init { Strings.ctx = app.applicationContext }
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
            if (!existing.identityKey.contentEquals(link.identityKey)) throw SzeptException(Strings.get(R.string.key_mismatch))
            if (!name.isNullOrBlank()) { existing.name = name; db.save(existing) }
            return existing
        }
        val c = Contact(link.accountId, name?.takeIf { it.isNotBlank() } ?: link.name ?: Strings.get(R.string.contact_short, link.accountId.take(6)), link.identityKey)
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

    // --- cel wysyłki: rozmowa 1:1 albo grupa (ten sam kod dla obu) ---
    private inner class Target(val c: Contact?, val g: Group?) {
        val disappear get() = c?.disappearAfter ?: g?.disappearAfter ?: 0
        fun touch(t: Long) { if (c != null) { c.lastActivity = t; db.save(c) } else if (g != null) { g.lastActivity = t; db.save(g) } }
        fun newMessage(id: String, text: String, kind: String, ts: Long, replyTo: String?) = Message(id, c?.id, text, true, ts, kind = kind, groupId = g?.id, replyTo = replyTo).also {
            if (disappear > 0) it.expiresAt = ts + disappear * 1000L
        }
        fun content(id: String, ts: Long, kind: String, text: String? = null, att: Attachment? = null, replyTo: String? = null, fn: String? = null, mime: String? = null, dur: Double? = null) =
            MessageContent(id, ts / 1000.0, kind, text = text, att = att, expire = disappear.takeIf { it > 0 }, g = g?.id, r = replyTo, fn = fn, mime = mime, dur = dur)
        /** Wysyłka wiadomości z zapisem statusu. */
        fun send(m: Message, content: MessageContent) { if (g != null) sendGroup(m, content, g) else send(m, content, c!!) }
        /** Wysyłka sygnału bez własnej wiadomości (reakcja, edycja, usunięcie, timer). */
        fun fire(content: MessageContent): Boolean = if (g != null) deliverToGroup(content, g) else try { deliver(content, c!!); true } catch (e: Exception) { lastError.value = e.message; false }
    }
    private fun target(m: Message): Target? = when {
        m.groupId != null -> db.group(m.groupId)?.let { Target(null, it) }
        m.contactId != null -> db.contact(m.contactId)?.let { Target(it, null) }
        else -> null
    }

    // --- wysyłanie ---
    private fun ensureSession(c: Contact) { if (!proto.hasSession(c.id)) proto.processBundle(api.bundle(c.id), c.identityKey) }

    private fun deliver(content: MessageContent, c: Contact) {
        ensureSession(c)
        val withName = if (displayName.isNotEmpty()) content.copy(name = displayName) else content
        api.deliver(listOf(proto.seal(json.encodeToString(MessageContent.serializer(), withName).toByteArray(), c.id, c.identityKey)), c.id)
    }

    private suspend fun sendTextTo(text: String, t: Target, replyTo: String?) = withContext(Dispatchers.IO) {
        val tx = text.trim(); if (tx.isEmpty()) return@withContext
        val m = t.newMessage(UUID.randomUUID().toString(), tx, Message.TEXT, System.currentTimeMillis(), replyTo)
        t.touch(m.sentAt); db.save(m)
        t.send(m, t.content(m.id, m.sentAt, Message.TEXT, text = tx, replyTo = replyTo))
    }

    /** Załącznik dowolnego rodzaju: zapis lokalny, szyfrowanie, upload, wysyłka. `data` to gotowe bajty (JPEG / plik / m4a). */
    private suspend fun sendBlob(data: ByteArray, kind: String, t: Target, w: Int = 0, h: Int = 0, fn: String? = null, mime: String? = null, dur: Double = 0.0,
                                 replyTo: String? = null, id: String = UUID.randomUUID().toString(), localFile: String? = null) = withContext(Dispatchers.IO) {
        if (data.size > Config.MAX_FILE) throw SzeptException(Strings.get(R.string.file_too_big, Config.MAX_FILE / 1024 / 1024))
        val m = t.newMessage(id, "", kind, System.currentTimeMillis(), replyTo).apply {
            attW = w; attH = h; fileName = fn; this.mime = mime; duration = dur
            attachmentFile = localFile ?: saveMedia(data, extensionFor(kind, fn))
        }
        t.touch(m.sentAt); db.save(m)
        try {
            val (ct, kn) = SzeptProtocol.encryptAttachment(data)
            val attId = api.uploadAttachment(ct)
            t.send(m, t.content(m.id, m.sentAt, kind, att = Attachment(attId, kn.first.b64(), kn.second.b64(), ct.size, w, h), replyTo = replyTo, fn = fn, mime = mime, dur = dur.takeIf { kind == Message.AUDIO }))
        } catch (e: Exception) { m.status = Message.FAILED; db.save(m); lastError.value = e.message }
    }

    suspend fun sendText(text: String, c: Contact, replyTo: String? = null) = sendTextTo(text, Target(c, null), replyTo)
    suspend fun sendText(text: String, g: Group, replyTo: String? = null) = sendTextTo(text, Target(null, g), replyTo)
    suspend fun sendImage(bitmap: Bitmap, c: Contact, replyTo: String? = null) = prepareImage(bitmap).let { (j, w, h) -> sendBlob(j, Message.IMAGE, Target(c, null), w, h, replyTo = replyTo) }
    suspend fun sendImage(bitmap: Bitmap, g: Group, replyTo: String? = null) = prepareImage(bitmap).let { (j, w, h) -> sendBlob(j, Message.IMAGE, Target(null, g), w, h, replyTo = replyTo) }
    suspend fun sendFile(data: ByteArray, name: String, mime: String?, c: Contact, replyTo: String? = null) = sendBlob(data, Message.FILE, Target(c, null), fn = name, mime = mime ?: "application/octet-stream", replyTo = replyTo)
    suspend fun sendFile(data: ByteArray, name: String, mime: String?, g: Group, replyTo: String? = null) = sendBlob(data, Message.FILE, Target(null, g), fn = name, mime = mime ?: "application/octet-stream", replyTo = replyTo)
    /** Głosówka: AAC w m4a (MediaRecorder MPEG_4), `dur` w sekundach. */
    suspend fun sendAudio(data: ByteArray, dur: Double, c: Contact, replyTo: String? = null) = sendBlob(data, Message.AUDIO, Target(c, null), mime = "audio/mp4", dur = dur, replyTo = replyTo)
    suspend fun sendAudio(data: ByteArray, dur: Double, g: Group, replyTo: String? = null) = sendBlob(data, Message.AUDIO, Target(null, g), mime = "audio/mp4", dur = dur, replyTo = replyTo)

    suspend fun retry(m: Message) = withContext(Dispatchers.IO) {
        val t = target(m) ?: return@withContext
        m.status = Message.PENDING; db.save(m)
        val file = m.attachmentFile?.let { File(mediaDir, it) }
        if (m.hasAttachment && file != null && file.exists()) {
            db.deleteMessage(m.id)
            sendBlob(file.readBytes(), m.kind, t, m.attW, m.attH, m.fileName, m.mime, m.duration, m.replyTo, id = m.id, localFile = m.attachmentFile)
        } else t.send(m, t.content(m.id, m.sentAt, Message.TEXT, text = m.text, replyTo = m.replyTo))
    }

    private fun send(m: Message, content: MessageContent, c: Contact) {
        try { deliver(content, c); m.status = Message.SENT } catch (e: Exception) { m.status = Message.FAILED; lastError.value = e.message }
        db.save(m)
    }

    // --- reakcje, edycja, usuwanie (docs/WERSJA-0.3.md) ---
    /** `emoji == null` cofa moją reakcję. Reakcja nie zmienia lastActivity ani licznika nieprzeczytanych. */
    suspend fun react(m: Message, emoji: String?) = withContext(Dispatchers.IO) {
        val t = target(m) ?: return@withContext
        val me = accountId ?: return@withContext
        val e = emoji?.take(Config.MAX_EMOJI)?.takeIf { it.isNotBlank() }
        m.withReaction(me, e); db.save(m)
        t.fire(t.content(UUID.randomUUID().toString(), System.currentTimeMillis(), "reaction", text = e ?: m.reactionMap()[me] ?: "")
            .copy(ref = m.id, op = if (e == null) "remove" else "set", expire = null))
    }

    /** Edycja tylko własnej wiadomości tekstowej. */
    suspend fun edit(m: Message, newText: String) = withContext(Dispatchers.IO) {
        val tx = newText.trim(); if (tx.isEmpty() || !m.isMine || m.deleted || m.kind != Message.TEXT || tx == m.text) return@withContext
        val t = target(m) ?: return@withContext
        m.text = tx; m.editedAt = System.currentTimeMillis(); db.save(m)
        t.fire(t.content(UUID.randomUUID().toString(), m.editedAt!!, "edit", text = tx).copy(ref = m.id, expire = null))
    }

    /** Usunięcie u wszystkich: tylko moje; lokalnie zostaje placeholder. */
    suspend fun deleteForAll(m: Message) = withContext(Dispatchers.IO) {
        if (!m.isMine || m.deleted) return@withContext
        val t = target(m) ?: return@withContext
        markDeleted(m)
        t.fire(t.content(UUID.randomUUID().toString(), System.currentTimeMillis(), "delete").copy(ref = m.id, expire = null))
    }

    fun deleteLocally(m: Message) { removeMedia(m); db.deleteMessage(m.id) }

    private fun markDeleted(m: Message) {
        removeMedia(m); m.attachmentFile = null; m.text = ""; m.deleted = true; m.reactions = "{}"; m.replyTo = null; m.fileName = null
        db.save(m)
    }

    suspend fun setDisappear(seconds: Int, c: Contact) = withContext(Dispatchers.IO) {
        c.disappearAfter = seconds; db.save(c)
        db.save(Message(UUID.randomUUID().toString(), c.id, if (seconds > 0) Strings.get(R.string.disappearing_set, Durations.label(seconds)) else Strings.get(R.string.disappearing_off), true, System.currentTimeMillis(), Message.SENT))
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
        val me = Member(accountId ?: throw SzeptException(Strings.get(R.string.not_registered)), proto.identityKey.serialize().b64(), displayName.ifEmpty { null })
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
        val g = Group(UUID.randomUUID().toString().uppercase(), name.trim().ifEmpty { Strings.get(R.string.group) }, members.map { it.id }.distinct())
        db.save(g)
        note(g, Strings.get(R.string.note_created_group, g.name), true)
        deliverToGroup(setContent(g), g)
        g
    }

    suspend fun renameGroup(g: Group, name: String) = withContext(Dispatchers.IO) {
        val n = name.trim(); if (n.isEmpty() || n == g.name) return@withContext
        g.name = n; g.lastActivity = System.currentTimeMillis(); db.save(g)
        note(g, Strings.get(R.string.note_renamed_group, n), true)
        deliverToGroup(setContent(g), g)
    }

    suspend fun addMembers(g: Group, contacts: List<Contact>) = withContext(Dispatchers.IO) {
        val new = contacts.map { it.id }.filter { it !in g.members && it != accountId }.distinct()
        if (new.isEmpty()) return@withContext
        g.members = g.members + new; g.lastActivity = System.currentTimeMillis(); db.save(g)
        note(g, Strings.get(R.string.note_added, contacts.filter { it.id in new }.joinToString { it.name }), true)
        deliverToGroup(setContent(g), g)
    }

    suspend fun leaveGroup(g: Group) = withContext(Dispatchers.IO) {
        deliverToGroup(MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "group", g = g.id, op = "leave"), g)
        db.messagesForGroup(g.id).forEach { removeMedia(it) }
        db.deleteGroup(g.id)
    }

    suspend fun setDisappear(seconds: Int, g: Group) = withContext(Dispatchers.IO) {
        g.disappearAfter = seconds; db.save(g)
        note(g, if (seconds > 0) Strings.get(R.string.disappearing_set, Durations.label(seconds)) else Strings.get(R.string.disappearing_off), true)
        deliverToGroup(MessageContent(UUID.randomUUID().toString(), System.currentTimeMillis() / 1000.0, "timer", expire = seconds, g = g.id), g)
    }

    fun markRead(g: Group) { if (g.unread != 0) { g.unread = 0; db.save(g) } }

    /** Nowi członkowie z `op: set`: nieznani trafiają do kontaktów z kluczem z wiadomości (TOFU); istniejącym klucza NIE ruszamy. */
    private fun absorbMembers(members: List<Member>) {
        for (m in members) {
            if (m.i == accountId || db.contact(m.i) != null) continue
            val key = try { m.k.unb64().also { IdentityKey(it) } } catch (e: Exception) { continue }
            val c = Contact(m.i, m.n?.takeIf { it.isNotBlank() } ?: Strings.get(R.string.contact_short, m.i.take(6)), key, fromUnknown = true)
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
                    val g = existing ?: Group(gid, content.gname ?: Strings.get(R.string.group), others)
                    val wasNew = existing == null
                    g.members = others; content.gname?.let { g.name = it }; g.lastActivity = now; if (wasNew) g.unread++
                    db.save(g)
                    note(g, if (wasNew) Strings.get(R.string.note_added_you, c.name, g.name) else Strings.get(R.string.note_changed_group, c.name, g.name, Strings.plural(R.plurals.members, others.size + 1)), false)
                    if (wasNew) onIncomingGroup?.invoke(g)
                }
                "leave" -> {
                    val g = existing ?: return
                    if (c.id !in g.members) return
                    g.members = g.members - c.id; g.lastActivity = now; db.save(g)
                    note(g, Strings.get(R.string.note_left, c.name), false)
                }
            }
            "text" -> {
                val g = existing ?: return; if (c.id !in g.members) return
                if (db.message(content.id) != null) return
                val m = Message(content.id, null, content.text ?: "", false, (content.ts * 1000).toLong(), Message.DELIVERED, groupId = g.id, senderId = c.id, replyTo = content.r)
                content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
                db.save(m); g.unread++; g.lastActivity = now; db.save(g)
                onIncomingGroup?.invoke(g)                                             // bez potwierdzeń odbioru dla grup
            }
            Message.IMAGE, Message.FILE, Message.AUDIO -> content.att?.let { att ->
                val g = existing ?: return; if (c.id !in g.members) return
                if (db.message(content.id) != null) return
                val m = incomingBlob(content, att, null, g.id, c.id, now)
                db.save(m); g.unread++; g.lastActivity = now; db.save(g)
                onIncomingGroup?.invoke(g)
                scope.launch { download(m, att) }
            }
            "reaction", "edit", "delete" -> {
                val g = existing ?: return; if (c.id !in g.members) return
                val m = content.ref?.let { db.message(it) } ?: return
                if (m.groupId != gid) return
                applySignal(content, m, isOwnerSender = m.senderId == c.id, sender = c.id)
            }
            "timer" -> {
                val g = existing ?: return; if (c.id !in g.members) return
                g.disappearAfter = content.expire ?: 0; g.lastActivity = now; db.save(g)
                note(g, if (g.disappearAfter > 0) Strings.get(R.string.note_timer_set, c.name, Durations.label(g.disappearAfter)) else Strings.get(R.string.note_timer_off, c.name), false)
            }
        }
    }

    /** Reakcja (na dowolną wiadomość rozmowy) albo edycja/usunięcie – tylko gdy `ref` jest wiadomością nadawcy. */
    private fun applySignal(content: MessageContent, m: Message, isOwnerSender: Boolean, sender: String) {
        when (content.kind) {
            "reaction" -> {
                if (m.deleted) return
                val e = content.text?.take(Config.MAX_EMOJI)?.takeIf { it.isNotBlank() }
                m.withReaction(sender, if (content.op == "remove") null else e ?: return); db.save(m)
            }
            "edit" -> {
                if (!isOwnerSender || m.isMine || m.deleted || m.kind != Message.TEXT) return
                val tx = content.text?.trim()?.takeIf { it.isNotEmpty() } ?: return
                m.text = tx; m.editedAt = (content.ts * 1000).toLong(); db.save(m)
            }
            "delete" -> { if (!isOwnerSender || m.isMine || m.deleted) return; markDeleted(m) }
        }
    }

    private fun incomingBlob(content: MessageContent, att: Attachment, contactId: String?, groupId: String?, senderId: String?, now: Long): Message {
        val m = Message(content.id, contactId, "", false, (content.ts * 1000).toLong(), Message.DELIVERED, attW = att.w, attH = att.h, groupId = groupId, senderId = senderId,
            kind = content.kind, replyTo = content.r, fileName = content.fn?.take(200)?.replace('/', '_'), mime = content.mime, duration = content.dur ?: 0.0)
        content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
        return m
    }

    private fun download(m: Message, att: Attachment) {
        try { m.attachmentFile = saveMedia(SzeptProtocol.decryptAttachment(api.downloadAttachment(att.id), att.key.unb64(), att.nonce.unb64()), extensionFor(m.kind, m.fileName)) }
        catch (e: Exception) { m.text = Strings.get(R.string.attachment_failed) }
        db.save(m)
    }

    // --- odbiór ---
    @Synchronized
    fun handleIncoming(sealed: ByteArray) {
        val opened = try { proto.open(sealed) } catch (e: Exception) {
            lastError.value = if (e is UntrustedIdentityException) Strings.get(R.string.key_changed_accept) else Strings.get(R.string.decrypt_failed, e.message ?: "")
            return
        }
        val content = try { json.decodeFromString(MessageContent.serializer(), String(opened.plaintext)) } catch (e: Exception) { return }
        val identity = opened.senderIdentity.serialize()
        val c = db.contact(opened.sender)?.also { known ->
            if (!known.identityKey.contentEquals(identity)) { known.identityKey = identity; known.identityChanged = true; known.verified = false }
        } ?: Contact(opened.sender, content.name ?: Strings.get(R.string.unknown_short, opened.sender.take(6)), identity, fromUnknown = true)
        if (!content.name.isNullOrEmpty() && c.fromUnknown && c.name in setOf(Strings.get(R.string.unknown_short, c.id.take(6)), Strings.get(R.string.contact_short, c.id.take(6)))) c.name = content.name
        val now = System.currentTimeMillis()
        if (content.g != null) { db.save(c); handleGroupContent(content, c, now); return }
        when (content.kind) {
            "text" -> if (db.message(content.id) == null) {
                val m = Message(content.id, c.id, content.text ?: "", false, (content.ts * 1000).toLong(), Message.DELIVERED, replyTo = content.r)
                content.expire?.takeIf { it > 0 }?.let { m.expiresAt = now + it * 1000L }
                db.save(m); c.unread++; c.lastActivity = now
                sendReceipt(content.id, c); onIncoming?.invoke(c)
            }
            Message.IMAGE, Message.FILE, Message.AUDIO -> content.att?.let { att ->
                if (db.message(content.id) != null) return@let
                val m = incomingBlob(content, att, c.id, null, null, now)
                db.save(m); c.unread++; c.lastActivity = now
                onIncoming?.invoke(c)
                scope.launch { download(m, att); sendReceipt(content.id, c) }
            }
            "reaction", "edit", "delete" -> run {
                val m = content.ref?.let { db.message(it) } ?: return@run
                if (m.groupId != null || m.contactId != c.id) return@run
                applySignal(content, m, isOwnerSender = !m.isMine, sender = c.id)
            }
            "receipt" -> content.ref?.let { db.message(it) }?.let { it.status = Message.DELIVERED; db.save(it) }
            "timer" -> {
                c.disappearAfter = content.expire ?: 0
                db.save(Message(UUID.randomUUID().toString(), c.id, if (c.disappearAfter > 0) Strings.get(R.string.note_timer_set, c.name, Durations.label(c.disappearAfter)) else Strings.get(R.string.note_timer_off, c.name), false, now, Message.DELIVERED))
            }
            // nieznany rodzaj (nowsza wersja apki po drugiej stronie) – ignorujemy
        }
        db.save(c)
    }
    fun markRead(c: Contact) { if (c.unread != 0) { c.unread = 0; db.save(c) } }

    fun purgeExpired() {
        val ex = db.expired(System.currentTimeMillis()); if (ex.isEmpty()) return
        ex.forEach { removeMedia(it); db.deleteMessage(it.id) }
    }

    // --- media ---
    private fun extensionFor(kind: String, fn: String?): String = when (kind) {
        Message.IMAGE -> "jpg"
        Message.AUDIO -> "m4a"
        else -> fn?.substringAfterLast('.', "")?.takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } } ?: "bin"
    }
    private fun saveMedia(data: ByteArray, ext: String = "jpg"): String { val name = UUID.randomUUID().toString() + "." + ext; File(mediaDir, name).writeBytes(data); return name }
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
