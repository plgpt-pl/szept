package pl.fourdt.szept

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Szept 0.4 (docs/WERSJA-0.4.md §4): „pisze…”, przeczytane, notatka dla siebie, przekazywanie, wyciszenie, wyszukiwanie. */
@RunWith(AndroidJUnit4::class)
class V04Test {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun tmp(n: String) = File(ctx.cacheDir, "t4-$n-${UUID.randomUUID()}").also { it.mkdirs() }
    private val server get() = InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:11437"
    private fun eng(n: String, id: String) = Engine(ctx, tmp("k$n"), prefsName = "t4-$n-$id", dbName = "t4-$n-$id.db").also { it.db.wipe(); it.serverUrl = server }
    /** Spreparowana koperta od `from` do `to` (wymaga istniejącej sesji from→to). */
    private fun craft(from: Engine, to: Engine, c: MessageContent): ByteArray =
        from.proto.seal(json.encodeToString(MessageContent.serializer(), c).toByteArray(), to.accountId!!, to.proto.identityKey.serialize())
    private fun up() = assumeTrue("serwer niedostępny", try { SzeptApi(server, null).health() } catch (e: Exception) { false })

    @Test fun v04ContentJson() {
        val t = json.decodeFromString(MessageContent.serializer(), """{"id":"a","ts":1,"kind":"typing","op":"start","g":"G1","nowe":5}""")
        assertEquals("typing", t.kind); assertEquals("start", t.op); assertEquals("G1", t.g)
        val r = json.decodeFromString(MessageContent.serializer(), """{"id":"b","ts":1,"kind":"receipt","ref":"M1","op":"read"}""")
        assertEquals("read", r.op); assertEquals("M1", r.ref)
        assertTrue(Engine.NON_URGENT.containsAll(listOf("receipt", "typing", "reaction", "edit", "delete")))
        assertFalse("text" in Engine.NON_URGENT || "image" in Engine.NON_URGENT || "group" in Engine.NON_URGENT || "timer" in Engine.NON_URGENT)
    }

    @Test fun readReceiptsAndTypingThroughServer() = runBlocking {
        up()
        val id = UUID.randomUUID().toString().take(8)
        val alice = eng("a", id); val bob = eng("b", id)
        alice.displayName = "Alice"; bob.displayName = "Bob"
        alice.register(); bob.register(); alice.stop(); bob.stop()
        val bobC = alice.addContact(bob.myLink!!, "Bob")
        alice.sendText("pierwsza", bobC); alice.sendText("druga", bobC)
        bob.drainPending()
        val aliceC = bob.db.contact(alice.accountId!!)!!
        Thread.sleep(500); alice.drainPending()
        assertTrue(alice.db.messages(bobC.id).filter { it.isMine }.all { it.status == Message.DELIVERED })

        // przeczytane: obie wcześniejsze wiadomości Alice → READ
        bob.chatVisible(aliceC); Thread.sleep(Engine.READ_DEBOUNCE_MS + 1500); alice.drainPending()
        assertTrue(alice.db.messages(bobC.id).filter { it.isMine }.all { it.status == Message.READ })
        // drugie chatVisible bez nowej wiadomości – nic nie wysyła (brak nowej koperty)
        bob.chatVisible(aliceC); Thread.sleep(Engine.READ_DEBOUNCE_MS + 1000)
        assertTrue(alice.api.pending().isEmpty())

        // „pisze…”: Bob dostał wiadomość < 2 min temu → wysyła start
        bob.composing(aliceC, null, "pi"); Thread.sleep(800); alice.drainPending()
        assertEquals(listOf(bob.accountId), alice.typingIn("c:${bobC.id}"))
        assertTrue("wygasa po 7 s", alice.typingIn("c:${bobC.id}", System.currentTimeMillis() + Engine.TYPING_SHOW_MS + 100).isEmpty())
        bob.sendText("piszę!", aliceC); Thread.sleep(800); alice.drainPending()
        assertTrue("wiadomość czyści „pisze…”", alice.typingIn("c:${bobC.id}").isEmpty())
        // typing/receipt NIE tworzą wiadomości ani nie ruszają licznika
        val n = alice.db.messages(bobC.id).size; val unread = alice.db.contact(bobC.id)!!.unread
        bob.composing(aliceC, null, "x"); bob.composing(aliceC, null, ""); Thread.sleep(800); alice.drainPending()
        assertEquals(n, alice.db.messages(bobC.id).size); assertEquals(unread, alice.db.contact(bobC.id)!!.unread)

        // wyłączone potwierdzenia u Alice: cudze `read` ignorowane (zostaje DELIVERED), własnych nie wysyła
        alice.readReceipts = false
        alice.sendText("trzecia", bobC); bob.drainPending(); Thread.sleep(500); alice.drainPending()
        bob.chatVisible(aliceC); Thread.sleep(Engine.READ_DEBOUNCE_MS + 1500); alice.drainPending()
        assertEquals(Message.DELIVERED, alice.db.messages(bobC.id).first { it.text == "trzecia" }.status)
        alice.chatVisible(bobC); Thread.sleep(Engine.READ_DEBOUNCE_MS + 1000); bob.drainPending()
        assertNotEquals(Message.READ, bob.db.messages(aliceC.id).first { it.text == "piszę!" }.status)
        // `read` dla wiadomości z innej rozmowy – ignorowany (Bob nie może oznaczyć wiadomości do kogoś innego)
        alice.readReceipts = true
        val carol = eng("c", id); carol.register(); carol.stop()
        val carolC = alice.addContact(carol.myLink!!, "Carol")
        alice.sendText("do Carol", carolC)
        val toCarol = alice.db.messages(carolC.id).first { it.isMine }
        val before = toCarol.status
        alice.handleIncoming(craft(bob, alice, MessageContent(UUID.randomUUID().toString(), 9.0, "receipt", ref = toCarol.id, op = "read")))
        assertEquals(before, alice.db.message(toCarol.id)!!.status)
        // wyłączony wskaźnik pisania u Alice – nie pokazuje cudzego
        alice.typingIndicators = false
        alice.handleIncoming(craft(bob, alice, MessageContent(UUID.randomUUID().toString(), 9.0, "typing", op = "start")))
        assertTrue(alice.typingIn("c:${bobC.id}").isEmpty())
        alice.api.deleteAccount(); bob.api.deleteAccount(); carol.api.deleteAccount()
    }

    @Test fun typingNotSentWhenConversationNotLive() = runBlocking {
        up()
        val id = UUID.randomUUID().toString().take(8)
        val alice = eng("a", id); val bob = eng("b", id)
        alice.register(); bob.register(); alice.stop(); bob.stop()
        val bobC = alice.addContact(bob.myLink!!, "Bob")
        alice.composing(bobC, null, "hej")                // Bob jeszcze nic nie napisał → brak koperty
        Thread.sleep(800)
        assertTrue(bob.api.pending().isEmpty())
        alice.api.deleteAccount(); bob.api.deleteAccount()
    }

    @Test fun noteToSelfIsLocalOnly() = runBlocking {
        up()
        val id = UUID.randomUUID().toString().take(8)
        val alice = eng("a", id)
        alice.register(); alice.stop()
        val note = alice.ensureNoteToSelf()!!
        assertTrue(alice.isSelf(note)); assertEquals(note.id, alice.ensureNoteToSelf()!!.id)     // jedna
        alice.serverUrl = "http://127.0.0.1:9"                                                 // każde żądanie do serwera by padło
        alice.sendText("kupić mleko", note)
        val pdf = ByteArray(5000) { it.toByte() }
        alice.sendFile(pdf, "lista.pdf", "application/pdf", note)
        val ms = alice.db.messages(note.id)
        assertEquals(2, ms.size); assertTrue(ms.all { it.status == Message.SENT })
        assertArrayEquals(pdf, File(alice.mediaDir, ms.first { it.kind == Message.FILE }.attachmentFile!!).readBytes())
        alice.composing(note, null, "x")                                                       // bez wyjątku, nic nie robi
        alice.chatVisible(note)
        // czyszczenie notatki zostawia sam kontakt
        alice.delete(note)
        assertNotNull(alice.db.contact(note.id)); assertTrue(alice.db.messages(note.id).isEmpty())
        alice.serverUrl = server; alice.api.deleteAccount()
    }

    @Test fun forwardMuteAndSearch() = runBlocking {
        up()
        val id = UUID.randomUUID().toString().take(8)
        val alice = eng("a", id); val bob = eng("b", id)
        alice.register(); bob.register(); alice.stop(); bob.stop()
        val bobC = alice.addContact(bob.myLink!!, "Bob")
        val img = ByteArray(20_000) { (it % 251).toByte() }
        alice.sendFile(img, "zdjęcie.bin", "application/octet-stream", bobC)
        alice.sendText("Żółta Łódź", bobC)
        bob.drainPending(); Thread.sleep(1500)
        val aliceC = bob.db.contact(alice.accountId!!)!!
        val gotFile = bob.db.messages(aliceC.id).first { it.kind == Message.FILE }
        val gotText = bob.db.messages(aliceC.id).first { it.kind == Message.TEXT }
        // przekazanie do notatki (lokalnie) i z powrotem do Alice (nowa wiadomość, bez odpowiedzi)
        val note = bob.ensureNoteToSelf()!!
        bob.forward(gotFile, note)
        val fwd = bob.db.messages(note.id).single()
        assertEquals(Message.FILE, fwd.kind); assertEquals("zdjęcie.bin", fwd.fileName); assertNotEquals(gotFile.id, fwd.id)
        assertArrayEquals(img, File(bob.mediaDir, fwd.attachmentFile!!).readBytes())
        bob.forward(gotText, aliceC)
        Thread.sleep(500); alice.drainPending()
        val back = alice.db.messages(bobC.id).filter { !it.isMine && it.text == "Żółta Łódź" }.single()
        assertNull(back.replyTo); assertNotEquals(gotText.id, back.id)
        // wyszukiwanie bez względu na wielkość polskich liter
        assertTrue(bob.db.search("żÓŁTA łódź").any { it.id == gotText.id })
        assertTrue(bob.db.search("nie ma takiego").isEmpty())
        // wyciszenie: brak powiadomień lokalnych, licznik dalej rośnie
        var notified = 0; alice.onIncoming = { notified++ }
        alice.mute(alice.db.contact(bobC.id)!!, 3_600_000)
        assertTrue(alice.db.contact(bobC.id)!!.isMuted())
        bob.sendText("cicho", aliceC); Thread.sleep(500); alice.drainPending()
        assertEquals(0, notified); assertTrue(alice.db.contact(bobC.id)!!.unread > 0)
        alice.mute(alice.db.contact(bobC.id)!!, 0)
        bob.sendText("głośno", aliceC); Thread.sleep(500); alice.drainPending()
        assertEquals(1, notified)
        // przypięcie przetrwa zapis/odczyt
        alice.setPinned(alice.db.contact(bobC.id)!!, true)
        assertTrue(alice.db.contact(bobC.id)!!.pinned)
        alice.api.deleteAccount(); bob.api.deleteAccount()
    }
}
