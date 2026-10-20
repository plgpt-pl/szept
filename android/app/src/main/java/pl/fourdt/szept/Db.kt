package pl.fourdt.szept

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Contact(val id: String, var name: String, var identityKey: ByteArray, var verified: Boolean = false, var identityChanged: Boolean = false,
                   val createdAt: Long = System.currentTimeMillis(), var lastActivity: Long = System.currentTimeMillis(), var unread: Int = 0,
                   var disappearAfter: Int = 0, var fromUnknown: Boolean = false)

/** Grupa istnieje tylko w telefonach członków (docs/GRUPY.md). `members` = account_id pozostałych członków (bez mnie). */
data class Group(val id: String, var name: String, var members: List<String>, val createdAt: Long = System.currentTimeMillis(),
                 var lastActivity: Long = System.currentTimeMillis(), var unread: Int = 0, var disappearAfter: Int = 0, var left: Boolean = false)

/** `contactId` = rozmowa 1:1 (null dla wiadomości grupowych); `groupId` = grupa; `senderId` = nadawca cudzej wiadomości grupowej. */
data class Message(val id: String, val contactId: String?, var text: String, val isMine: Boolean, val sentAt: Long, var status: Int = 0,
                   var attachmentFile: String? = null, var attW: Int = 0, var attH: Int = 0, var expiresAt: Long? = null,
                   val groupId: String? = null, val senderId: String? = null,
                   // 0.3: rodzaj (text/image/file/audio), odpowiedź, reakcje (JSON senderId→emoji), edycja, usunięcie, plik, głosówka
                   var kind: String = TEXT, var replyTo: String? = null, var reactions: String = "{}", var editedAt: Long? = null, var deleted: Boolean = false,
                   var fileName: String? = null, var mime: String? = null, var duration: Double = 0.0) {
    companion object {
        const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val FAILED = 3
        const val TEXT = "text"; const val IMAGE = "image"; const val FILE = "file"; const val AUDIO = "audio"
    }
    val hasAttachment get() = kind == IMAGE || kind == FILE || kind == AUDIO
    /** Notatka systemowa (ktoś dodał, opuścił, zmienił timer) – bez nadawcy, nie moja, zwykły tekst. */
    val isNote get() = groupId != null && senderId == null && !isMine && kind == TEXT
    fun reactionMap(): Map<String, String> = try { JSONObject(reactions).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } catch (e: Exception) { emptyMap() }
    fun withReaction(sender: String, emoji: String?) { val o = JSONObject(reactions); if (emoji == null) o.remove(sender) else o.put(sender, emoji); reactions = o.toString() }
    /** Jednolinijkowy opis do listy czatów i cytatów. */
    fun summary(): String = when {
        deleted -> Strings.get(R.string.message_deleted)
        kind == IMAGE -> Strings.get(R.string.summary_photo)
        kind == FILE -> Strings.get(R.string.summary_file, fileName ?: Strings.get(R.string.file_lower))
        kind == AUDIO -> Strings.get(R.string.summary_voice, durationLabel(duration))
        else -> text
    }
}

fun durationLabel(sec: Double): String { val s = sec.toInt(); return "%d:%02d".format(s / 60, s % 60) }

/** Lokalna baza (katalog poza kopią zapasową). Zmiany sygnalizowane przez `version` – ekrany Compose odświeżają się same. */
class Db(context: Context, name: String = "szept.db") : SQLiteOpenHelper(context, File(context.noBackupFilesDir, name).path, null, 3) {
    val version = MutableStateFlow(0)
    private fun bump() { version.value++ }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT, identity_key BLOB, verified INT, identity_changed INT, created_at INT,
            last_activity INT, unread INT, disappear_after INT, from_unknown INT)""")
        db.execSQL("""CREATE TABLE messages(id TEXT PRIMARY KEY, contact_id TEXT, text TEXT, is_mine INT, sent_at INT, status INT,
            attachment_file TEXT, att_w INT, att_h INT, expires_at INT, group_id TEXT, sender_id TEXT)""")
        db.execSQL("CREATE INDEX msg_contact ON messages(contact_id, sent_at)")
        createGroups(db)
        addV3Columns(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN group_id TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN sender_id TEXT")
            createGroups(db)
        }
        if (oldVersion < 3) addV3Columns(db)
    }
    /** 0.3: rodzaj, odpowiedzi, reakcje, edycja, usunięcie, plik, głosówka. Stare zdjęcia (att_w>0) dostają kind=image. */
    private fun addV3Columns(db: SQLiteDatabase) {
        for (c in listOf("kind TEXT DEFAULT 'text'", "reply_to TEXT", "reactions TEXT DEFAULT '{}'", "edited_at INT", "deleted INT DEFAULT 0",
                         "file_name TEXT", "mime TEXT", "duration REAL DEFAULT 0")) {
            try { db.execSQL("ALTER TABLE messages ADD COLUMN $c") } catch (_: Exception) {}
        }
        db.execSQL("UPDATE messages SET kind='image' WHERE att_w>0 AND (kind IS NULL OR kind='text')")
    }
    private fun createGroups(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS groups(id TEXT PRIMARY KEY, name TEXT, members TEXT, created_at INT, last_activity INT, unread INT,
            disappear_after INT, left INT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS msg_group ON messages(group_id, sent_at)")
    }

    private fun Cursor.contact() = Contact(getString(0), getString(1), getBlob(2), getInt(3) == 1, getInt(4) == 1, getLong(5), getLong(6), getInt(7), getInt(8), getInt(9) == 1)
    private fun Cursor.message() = Message(getString(0), if (isNull(1)) null else getString(1), getString(2), getInt(3) == 1, getLong(4), getInt(5), getString(6), getInt(7), getInt(8),
        if (isNull(9)) null else getLong(9), if (isNull(10)) null else getString(10), if (isNull(11)) null else getString(11),
        kind = getString(12) ?: Message.TEXT, replyTo = if (isNull(13)) null else getString(13), reactions = getString(14) ?: "{}", editedAt = if (isNull(15)) null else getLong(15),
        deleted = getInt(16) == 1, fileName = if (isNull(17)) null else getString(17), mime = if (isNull(18)) null else getString(18), duration = getDouble(19))
    private fun Cursor.group() = Group(getString(0), getString(1), membersFromJson(getString(2)), getLong(3), getLong(4), getInt(5), getInt(6), getInt(7) == 1)
    private val CCOLS = "id,name,identity_key,verified,identity_changed,created_at,last_activity,unread,disappear_after,from_unknown"
    private val MCOLS = "id,contact_id,text,is_mine,sent_at,status,attachment_file,att_w,att_h,expires_at,group_id,sender_id,kind,reply_to,reactions,edited_at,deleted,file_name,mime,duration"
    private val GCOLS = "id,name,members,created_at,last_activity,unread,disappear_after,left"

    private fun membersFromJson(s: String?): List<String> = if (s.isNullOrEmpty()) emptyList() else JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun membersToJson(m: List<String>): String = JSONArray(m).toString()

    private fun <T> Cursor.all(f: Cursor.() -> T): List<T> = generateSequence { if (moveToNext()) f() else null }.toList()

    // --- kontakty ---
    fun contacts(): List<Contact> = readableDatabase.rawQuery("SELECT $CCOLS FROM contacts ORDER BY last_activity DESC", null).use { c -> c.all { contact() } }
    fun contact(id: String): Contact? = readableDatabase.rawQuery("SELECT $CCOLS FROM contacts WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.contact() else null }
    fun save(c: Contact) {
        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
            put("id", c.id); put("name", c.name); put("identity_key", c.identityKey); put("verified", c.verified); put("identity_changed", c.identityChanged)
            put("created_at", c.createdAt); put("last_activity", c.lastActivity); put("unread", c.unread); put("disappear_after", c.disappearAfter); put("from_unknown", c.fromUnknown)
        }, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteContact(id: String) { writableDatabase.delete("messages", "contact_id=? AND group_id IS NULL", arrayOf(id)); writableDatabase.delete("contacts", "id=?", arrayOf(id)); bump() }

    // --- grupy ---
    fun groups(): List<Group> = readableDatabase.rawQuery("SELECT $GCOLS FROM groups ORDER BY last_activity DESC", null).use { c -> c.all { group() } }
    fun group(id: String): Group? = readableDatabase.rawQuery("SELECT $GCOLS FROM groups WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.group() else null }
    fun save(g: Group) {
        writableDatabase.insertWithOnConflict("groups", null, ContentValues().apply {
            put("id", g.id); put("name", g.name); put("members", membersToJson(g.members)); put("created_at", g.createdAt); put("last_activity", g.lastActivity)
            put("unread", g.unread); put("disappear_after", g.disappearAfter); put("left", g.left)
        }, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteGroup(id: String) { writableDatabase.delete("messages", "group_id=?", arrayOf(id)); writableDatabase.delete("groups", "id=?", arrayOf(id)); bump() }

    // --- wiadomości ---
    fun messages(contactId: String): List<Message> = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE contact_id=? AND group_id IS NULL ORDER BY sent_at", arrayOf(contactId)).use { c -> c.all { message() } }
    fun lastMessage(contactId: String): Message? = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE contact_id=? AND group_id IS NULL ORDER BY sent_at DESC LIMIT 1", arrayOf(contactId)).use { if (it.moveToFirst()) it.message() else null }
    fun messagesForGroup(groupId: String): List<Message> = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE group_id=? ORDER BY sent_at", arrayOf(groupId)).use { c -> c.all { message() } }
    fun lastMessageForGroup(groupId: String): Message? = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE group_id=? ORDER BY sent_at DESC LIMIT 1", arrayOf(groupId)).use { if (it.moveToFirst()) it.message() else null }
    fun message(id: String): Message? = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.message() else null }
    fun save(m: Message) {
        writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", m.id); if (m.contactId == null) putNull("contact_id") else put("contact_id", m.contactId); put("text", m.text); put("is_mine", m.isMine); put("sent_at", m.sentAt); put("status", m.status)
            put("attachment_file", m.attachmentFile); put("att_w", m.attW); put("att_h", m.attH); if (m.expiresAt == null) putNull("expires_at") else put("expires_at", m.expiresAt)
            if (m.groupId == null) putNull("group_id") else put("group_id", m.groupId); if (m.senderId == null) putNull("sender_id") else put("sender_id", m.senderId)
            put("kind", m.kind); put("reply_to", m.replyTo); put("reactions", m.reactions); if (m.editedAt == null) putNull("edited_at") else put("edited_at", m.editedAt)
            put("deleted", m.deleted); put("file_name", m.fileName); put("mime", m.mime); put("duration", m.duration)
        }, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteMessage(id: String) { writableDatabase.delete("messages", "id=?", arrayOf(id)); bump() }
    fun expired(now: Long): List<Message> = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE expires_at IS NOT NULL AND expires_at<?", arrayOf("$now")).use { c -> c.all { message() } }
    fun wipe() { writableDatabase.delete("messages", null, null); writableDatabase.delete("contacts", null, null); writableDatabase.delete("groups", null, null); bump() }
}
