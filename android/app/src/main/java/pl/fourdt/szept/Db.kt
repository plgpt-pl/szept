package pl.fourdt.szept

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class Contact(val id: String, var name: String, var identityKey: ByteArray, var verified: Boolean = false, var identityChanged: Boolean = false,
                   val createdAt: Long = System.currentTimeMillis(), var lastActivity: Long = System.currentTimeMillis(), var unread: Int = 0,
                   var disappearAfter: Int = 0, var fromUnknown: Boolean = false)

data class Message(val id: String, val contactId: String, var text: String, val isMine: Boolean, val sentAt: Long, var status: Int = 0,
                   var attachmentFile: String? = null, var attW: Int = 0, var attH: Int = 0, var expiresAt: Long? = null) {
    companion object { const val PENDING = 0; const val SENT = 1; const val DELIVERED = 2; const val FAILED = 3 }
}

/** Lokalna baza (katalog poza kopią zapasową). Zmiany sygnalizowane przez `version` – ekrany Compose odświeżają się same. */
class Db(context: Context, name: String = "szept.db") : SQLiteOpenHelper(context, File(context.noBackupFilesDir, name).path, null, 1) {
    val version = MutableStateFlow(0)
    private fun bump() { version.value++ }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE contacts(id TEXT PRIMARY KEY, name TEXT, identity_key BLOB, verified INT, identity_changed INT, created_at INT,
            last_activity INT, unread INT, disappear_after INT, from_unknown INT)""")
        db.execSQL("""CREATE TABLE messages(id TEXT PRIMARY KEY, contact_id TEXT, text TEXT, is_mine INT, sent_at INT, status INT,
            attachment_file TEXT, att_w INT, att_h INT, expires_at INT)""")
        db.execSQL("CREATE INDEX msg_contact ON messages(contact_id, sent_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun Cursor.contact() = Contact(getString(0), getString(1), getBlob(2), getInt(3) == 1, getInt(4) == 1, getLong(5), getLong(6), getInt(7), getInt(8), getInt(9) == 1)
    private fun Cursor.message() = Message(getString(0), getString(1), getString(2), getInt(3) == 1, getLong(4), getInt(5), getString(6), getInt(7), getInt(8),
        if (isNull(9)) null else getLong(9))
    private val CCOLS = "id,name,identity_key,verified,identity_changed,created_at,last_activity,unread,disappear_after,from_unknown"
    private val MCOLS = "id,contact_id,text,is_mine,sent_at,status,attachment_file,att_w,att_h,expires_at"

    fun contacts(): List<Contact> = readableDatabase.rawQuery("SELECT $CCOLS FROM contacts ORDER BY last_activity DESC", null).use { c ->
        generateSequence { if (c.moveToNext()) c.contact() else null }.toList() }
    fun contact(id: String): Contact? = readableDatabase.rawQuery("SELECT $CCOLS FROM contacts WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.contact() else null }
    fun save(c: Contact) {
        writableDatabase.insertWithOnConflict("contacts", null, ContentValues().apply {
            put("id", c.id); put("name", c.name); put("identity_key", c.identityKey); put("verified", c.verified); put("identity_changed", c.identityChanged)
            put("created_at", c.createdAt); put("last_activity", c.lastActivity); put("unread", c.unread); put("disappear_after", c.disappearAfter); put("from_unknown", c.fromUnknown)
        }, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteContact(id: String) { writableDatabase.delete("messages", "contact_id=?", arrayOf(id)); writableDatabase.delete("contacts", "id=?", arrayOf(id)); bump() }

    fun messages(contactId: String): List<Message> = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE contact_id=? ORDER BY sent_at", arrayOf(contactId)).use { c ->
        generateSequence { if (c.moveToNext()) c.message() else null }.toList() }
    fun lastMessage(contactId: String): Message? = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE contact_id=? ORDER BY sent_at DESC LIMIT 1", arrayOf(contactId)).use { if (it.moveToFirst()) it.message() else null }
    fun message(id: String): Message? = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.message() else null }
    fun save(m: Message) {
        writableDatabase.insertWithOnConflict("messages", null, ContentValues().apply {
            put("id", m.id); put("contact_id", m.contactId); put("text", m.text); put("is_mine", m.isMine); put("sent_at", m.sentAt); put("status", m.status)
            put("attachment_file", m.attachmentFile); put("att_w", m.attW); put("att_h", m.attH); if (m.expiresAt == null) putNull("expires_at") else put("expires_at", m.expiresAt)
        }, SQLiteDatabase.CONFLICT_REPLACE); bump()
    }
    fun deleteMessage(id: String) { writableDatabase.delete("messages", "id=?", arrayOf(id)); bump() }
    fun expired(now: Long): List<Message> = readableDatabase.rawQuery("SELECT $MCOLS FROM messages WHERE expires_at IS NOT NULL AND expires_at<?", arrayOf("$now")).use { c ->
        generateSequence { if (c.moveToNext()) c.message() else null }.toList() }
    fun wipe() { writableDatabase.delete("messages", null, null); writableDatabase.delete("contacts", null, null); bump() }
}
