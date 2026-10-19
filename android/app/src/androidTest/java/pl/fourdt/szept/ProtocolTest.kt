package pl.fourdt.szept

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Protokół w jednym procesie (dwie tożsamości, „serwer” w pamięci) + pełny obieg przez prawdziwy serwer. */
@RunWith(AndroidJUnit4::class)
class ProtocolTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tmp(n: String) = File(ctx.cacheDir, "t-$n-${UUID.randomUUID()}").also { it.mkdirs() }
    private fun bundle(r: RegistrationPayload, id: String, oneTime: Boolean = true) = BundleJSON(id, 1, r.identity_key, r.registration_id, r.signed_prekey,
        if (oneTime) r.prekeys[0] else null, if (oneTime) r.kyber_prekeys[0] else r.kyber_last_resort)

    @Test fun roundTripBothDirections() {
        val a = SzeptProtocol(SignalStore(tmp("a")), "ALICE"); val b = SzeptProtocol(SignalStore(tmp("b")), "BOB")
        val ra = a.makeRegistration(); val rb = b.makeRegistration()
        a.processBundle(bundle(rb, "BOB"), rb.identity_key.unb64())
        assertTrue(a.hasSession("BOB")); assertFalse(b.hasSession("ALICE"))
        val sealed = a.seal("cześć Bob".toByteArray(), "BOB", rb.identity_key.unb64())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("ALICE"))
        val o = b.open(sealed)
        assertEquals("cześć Bob", String(o.plaintext)); assertEquals("ALICE", o.sender); assertTrue(o.isPreKey)
        assertEquals(ra.identity_key, o.senderIdentity.serialize().b64())
        val reply = b.seal("hej Alice".toByteArray(), "ALICE", ra.identity_key.unb64())
        val o2 = a.open(reply); assertEquals("hej Alice", String(o2.plaintext)); assertFalse(o2.isPreKey)
        for (i in 0 until 20) {
            val (from, to, toId, toKey) = if (i % 2 == 0) listOf(a, b, "BOB", rb.identity_key) else listOf(b, a, "ALICE", ra.identity_key)
            val s = (from as SzeptProtocol).seal("m$i".toByteArray(), toId as String, (toKey as String).unb64())
            assertEquals("m$i", String((to as SzeptProtocol).open(s).plaintext))
        }
        assertEquals(a.safetyNumber("BOB", rb.identity_key.unb64()), b.safetyNumber("ALICE", ra.identity_key.unb64()))
        assertEquals(60, a.safetyNumber("BOB", rb.identity_key.unb64()).count { it.isDigit() })
    }

    @Test fun pinnedMismatchAndTamperingFail() {
        val a = SzeptProtocol(SignalStore(tmp("a")), "ALICE"); val b = SzeptProtocol(SignalStore(tmp("b")), "BOB")
        val ra = a.makeRegistration(); val rb = b.makeRegistration()
        assertThrows(SzeptException::class.java) { a.processBundle(bundle(rb, "BOB"), ra.identity_key.unb64()) }
        a.processBundle(bundle(rb, "BOB", oneTime = false), null)
        val sealed = a.seal("tajne".toByteArray(), "BOB", rb.identity_key.unb64())
        b.open(sealed)
        assertThrows(Exception::class.java) { b.open(sealed) }            // powtórka
        sealed[sealed.size - 1] = (sealed.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { b.open(sealed) }            // manipulacja
        val (ct, kn) = SzeptProtocol.encryptAttachment(ByteArray(5000) { (it % 251).toByte() })
        assertArrayEquals(ByteArray(5000) { (it % 251).toByte() }, SzeptProtocol.decryptAttachment(ct, kn.first, kn.second))
        val link = ContactLink("ALICE", ra.identity_key.unb64(), "Ala Ż")
        assertEquals(link.accountId, ContactLink.parse(link.url)!!.accountId); assertEquals("Ala Ż", ContactLink.parse(link.url)!!.name)
        assertNull(ContactLink.parse("https://example.com/add?i=ALICE"))
    }

    /** Dwa „telefony” przez prawdziwy serwer (emulator → host: 10.0.2.2). Pomijany, gdy serwer nie działa. */
    @Test fun twoPhonesThroughServer() = runBlocking {
        val server = System.getProperty("szept.server") ?: InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:11437"
        val probe = SzeptApi(server, null)
        assumeTrue("serwer niedostępny", try { probe.health() } catch (e: Exception) { false })
        val id = UUID.randomUUID().toString().take(8)
        val alice = Engine(ctx, tmp("ka"), prefsName = "t-a-$id", dbName = "t-a-$id.db"); val bob = Engine(ctx, tmp("kb"), prefsName = "t-b-$id", dbName = "t-b-$id.db")
        alice.db.wipe(); bob.db.wipe()
        alice.serverUrl = server; bob.serverUrl = server; alice.displayName = "Alice"
        alice.register(); bob.register(); alice.stop(); bob.stop()
        assertNotEquals(alice.accountId, bob.accountId)
        val bobC = alice.addContact(bob.myLink!!, "Bob")
        alice.sendText("Cześć Bob, to Szept", bobC)
        assertEquals(Message.SENT, alice.db.messages(bobC.id).first().status)
        bob.drainPending()
        val aliceAtBob = bob.db.contact(alice.accountId!!)!!
        assertEquals("Alice", aliceAtBob.name); assertTrue(aliceAtBob.fromUnknown); assertEquals(1, aliceAtBob.unread)
        assertEquals("Cześć Bob, to Szept", bob.db.messages(aliceAtBob.id).first().text)
        bob.sendText("Hej Alice!", aliceAtBob)
        Thread.sleep(700); alice.drainPending()
        assertEquals(listOf("Cześć Bob, to Szept", "Hej Alice!"), alice.db.messages(bobC.id).map { it.text }.sorted())
        assertEquals(Message.DELIVERED, alice.db.messages(bobC.id).first { it.isMine }.status)
        val bmp = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        alice.sendImage(bmp, bobC)
        bob.drainPending(); Thread.sleep(1500)
        val photo = bob.db.messages(aliceAtBob.id).first { it.attW > 0 }
        assertEquals(300, photo.attW); assertNotNull("załącznik pobrany i odszyfrowany", photo.attachmentFile)
        alice.setDisappear(300, bobC); bob.drainPending()
        assertEquals(300, bob.db.contact(alice.accountId!!)!!.disappearAfter)
        val c = bob.api.keyCounts(); assertEquals(SzeptProtocol.PREKEY_BATCH - 1, c.prekeys); assertEquals(SzeptProtocol.PREKEY_BATCH - 1, c.kyber_prekeys)
        alice.api.deleteAccount(); bob.api.deleteAccount()
    }
}
