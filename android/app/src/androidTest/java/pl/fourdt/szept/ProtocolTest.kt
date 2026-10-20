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

    /** Pola grupowe w JSON: nazwy 1:1 z iOS, brak pól gdy null (docs/GRUPY.md). */
    @Test fun groupContentJson() {
        val c = MessageContent("ID1", 1.5, "group", g = "G-1", gname = "Ekipa", op = "set",
            members = listOf(Member("A", "a2V5QQ==", "Ala"), Member("B", "a2V5Qg==")))
        val js = json.encodeToString(MessageContent.serializer(), c)
        assertTrue(js.contains("\"g\":\"G-1\"")); assertTrue(js.contains("\"gname\":\"Ekipa\"")); assertTrue(js.contains("\"op\":\"set\""))
        assertTrue(js.contains("\"members\":[{\"i\":\"A\",\"k\":\"a2V5QQ==\",\"n\":\"Ala\"},{\"i\":\"B\",\"k\":\"a2V5Qg==\"}]"))
        assertEquals(c, json.decodeFromString(MessageContent.serializer(), js))
        val plain = json.encodeToString(MessageContent.serializer(), MessageContent("ID2", 2.0, "text", text = "hej"))
        assertFalse(plain.contains("\"g\"")); assertFalse(plain.contains("members")); assertFalse(plain.contains("\"op\""))
        // JSON tak, jak wysyła go iOS (inna kolejność pól, nieznane pole)
        val fromIos = json.decodeFromString(MessageContent.serializer(), """{"kind":"text","id":"X","ts":3,"text":"grupowa","g":"G-1","przyszle":1}""")
        assertEquals("G-1", fromIos.g); assertEquals("grupowa", fromIos.text); assertNull(fromIos.members)
    }

    /** Trzy „telefony” i grupa przez prawdziwy serwer (rozsyłanie pairwise). Pomijany, gdy serwer nie działa. */
    @Test fun threePhonesGroupThroughServer() = runBlocking {
        val server = System.getProperty("szept.server") ?: InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:11437"
        val probe = SzeptApi(server, null)
        assumeTrue("serwer niedostępny", try { probe.health() } catch (e: Exception) { false })
        val id = UUID.randomUUID().toString().take(8)
        fun eng(n: String) = Engine(ctx, tmp("k$n"), prefsName = "t-$n-$id", dbName = "t-$n-$id.db").also { it.db.wipe(); it.serverUrl = server }
        val a = eng("a"); val b = eng("b"); val c = eng("c")
        a.displayName = "Ala"; b.displayName = "Bartek"; c.displayName = "Celina"
        a.register(); b.register(); c.register(); a.stop(); b.stop(); c.stop()
        val bAtA = a.addContact(b.myLink!!, "Bartek"); val cAtA = a.addContact(c.myLink!!, "Celina")
        // B i C nie znają się nawzajem – poznają się przez `set` od A (TOFU z kluczem w wiadomości)
        val g = a.createGroup("Ekipa", listOf(bAtA, cAtA))
        assertEquals(setOf(b.accountId, c.accountId), g.members.toSet())
        a.sendText("Cześć ekipo", g)
        assertEquals(Message.SENT, a.db.messagesForGroup(g.id).first { it.isMine && it.text == "Cześć ekipo" }.status)
        b.drainPending(); c.drainPending()
        for (e in listOf(b, c)) {
            val gg = e.db.group(g.id); assertNotNull("grupa nie dotarła", gg); gg!!
            assertEquals("Ekipa", gg.name)
            assertEquals((setOf(a.accountId, b.accountId, c.accountId) - e.accountId!!), gg.members.toSet())
            val m = e.db.messagesForGroup(g.id).first { it.senderId != null }
            assertEquals("Cześć ekipo", m.text); assertEquals(a.accountId, m.senderId); assertNull(m.contactId)
            assertTrue(e.db.messages(a.accountId!!).isEmpty())                       // nie wpadła do rozmowy 1:1
            assertEquals("Ala", e.db.contact(a.accountId!!)!!.name)
            assertTrue(e.db.contact(if (e === b) c.accountId!! else b.accountId!!)!!.fromUnknown)
        }
        c.sendText("Hej!", c.db.group(g.id)!!)
        Thread.sleep(700); a.drainPending(); b.drainPending()
        assertEquals(c.accountId, a.db.messagesForGroup(g.id).first { it.text == "Hej!" }.senderId)
        assertEquals(c.accountId, b.db.messagesForGroup(g.id).first { it.text == "Hej!" }.senderId)
        assertEquals(Message.SENT, a.db.messagesForGroup(g.id).first { it.isMine && it.text == "Cześć ekipo" }.status)  // brak potwierdzeń w grupach
        b.leaveGroup(b.db.group(g.id)!!)
        assertNull(b.db.group(g.id))
        Thread.sleep(700); a.drainPending(); c.drainPending()
        assertEquals(listOf(c.accountId), a.db.group(g.id)!!.members)
        assertEquals(listOf(a.accountId), c.db.group(g.id)!!.members)
        assertTrue(a.db.messagesForGroup(g.id).any { it.text.contains("opuścił") })
        a.api.deleteAccount(); b.api.deleteAccount(); c.api.deleteAccount()
    }

    // ---------- 0.3 (docs/WERSJA-0.3.md): odpowiedzi, reakcje, edycja, usuwanie, pliki, głosówki ----------

    private fun craft(from: Engine, to: Engine, c: MessageContent): ByteArray =
        from.proto.seal(json.encodeToString(MessageContent.serializer(), c).toByteArray(), to.accountId!!, to.proto.identityKey.serialize())

    @Test fun v03ContentJson() {
        val reply = MessageContent("M2", 2.0, "text", text = "a ja nie", r = "M1")
        val js = json.encodeToString(MessageContent.serializer(), reply)
        assertTrue(js.contains("\"r\":\"M1\"")); assertFalse(js.contains("\"fn\"")); assertFalse(js.contains("\"dur\""))
        assertEquals(reply, json.decodeFromString(MessageContent.serializer(), js))
        val file = MessageContent("M3", 3.0, "file", att = Attachment("A", "a2V5", "bm9uY2U=", 10, 0, 0), fn = "raport.pdf", mime = "application/pdf", r = "M1")
        assertEquals(file, json.decodeFromString(MessageContent.serializer(), json.encodeToString(MessageContent.serializer(), file)))
        val audio = MessageContent("M4", 4.0, "audio", att = Attachment("B", "a2V5", "bm9uY2U=", 10, 0, 0), mime = "audio/mp4", dur = 12.5)
        val ajs = json.encodeToString(MessageContent.serializer(), audio)
        assertTrue(ajs.contains("\"dur\":12.5")); assertEquals(audio, json.decodeFromString(MessageContent.serializer(), ajs))
        val react = MessageContent("M5", 5.0, "reaction", text = "👍", ref = "M1", op = "set", g = "G-1")
        val rjs = json.encodeToString(MessageContent.serializer(), react)
        assertTrue(rjs.contains("\"op\":\"set\"") && rjs.contains("\"ref\":\"M1\"") && rjs.contains("\"g\":\"G-1\""))
        // nieznany rodzaj z nowszej wersji: dekoduje się, apka go ignoruje (test niżej)
        val future = json.decodeFromString(MessageContent.serializer(), """{"id":"X","ts":1,"kind":"hologram","beam":true}""")
        assertEquals("hologram", future.kind)
        // model lokalny: reakcje jako mapa w JSON
        val m = Message("M1", "C", "hej", false, 0L)
        m.withReaction("A", "👍"); m.withReaction("B", "❤️"); assertEquals(mapOf("A" to "👍", "B" to "❤️"), m.reactionMap())
        m.withReaction("A", null); assertEquals(mapOf("B" to "❤️"), m.reactionMap())
        assertEquals("📎 raport.pdf", Message("M3", "C", "", false, 0L, kind = Message.FILE, fileName = "raport.pdf").summary())
        assertEquals("🎤 Głosówka 0:12", Message("M4", "C", "", false, 0L, kind = Message.AUDIO, duration = 12.5).summary())
    }

    @Test fun v03TwoPhonesThroughServer() = runBlocking {
        val server = System.getProperty("szept.server") ?: InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:11437"
        assumeTrue("serwer niedostępny", try { SzeptApi(server, null).health() } catch (e: Exception) { false })
        val id = UUID.randomUUID().toString().take(8)
        val alice = Engine(ctx, tmp("ka"), prefsName = "t3-a-$id", dbName = "t3-a-$id.db"); val bob = Engine(ctx, tmp("kb"), prefsName = "t3-b-$id", dbName = "t3-b-$id.db")
        alice.db.wipe(); bob.db.wipe(); alice.serverUrl = server; bob.serverUrl = server; alice.displayName = "Alice"; bob.displayName = "Bob"
        alice.register(); bob.register(); alice.stop(); bob.stop()
        val bobC = alice.addContact(bob.myLink!!, "Bob")
        alice.sendText("Idziemy jutro?", bobC)
        bob.drainPending()
        val aliceC = bob.db.contact(alice.accountId!!)!!
        val q = bob.db.messages(aliceC.id).first()
        // odpowiedź
        bob.sendText("Tak, o 10", aliceC, replyTo = q.id)
        Thread.sleep(500); alice.drainPending()
        val reply = alice.db.messages(bobC.id).first { it.text == "Tak, o 10" }
        assertEquals(q.id, reply.replyTo); assertFalse(reply.isMine)
        // reakcja: set, potem remove
        bob.react(q, "👍"); Thread.sleep(500); alice.drainPending()
        assertEquals(mapOf(bob.accountId!! to "👍"), alice.db.message(q.id)!!.reactionMap())
        assertEquals(mapOf(bob.accountId!! to "👍"), bob.db.message(q.id)!!.reactionMap())
        bob.react(q, null); Thread.sleep(500); alice.drainPending()
        assertTrue(alice.db.message(q.id)!!.reactionMap().isEmpty())
        // edycja własnej – przyjęta
        alice.edit(alice.db.message(q.id)!!, "Idziemy jutro o 10?"); Thread.sleep(500); bob.drainPending()
        val edited = bob.db.message(q.id)!!
        assertEquals("Idziemy jutro o 10?", edited.text); assertNotNull(edited.editedAt)
        // edycja / usunięcie CUDZEJ wiadomości (spreparowane przez Boba) – ignorowane
        alice.handleIncoming(craft(bob, alice, MessageContent(UUID.randomUUID().toString(), 9.0, "edit", text = "hack", ref = q.id)))
        alice.handleIncoming(craft(bob, alice, MessageContent(UUID.randomUUID().toString(), 9.0, "delete", ref = q.id)))
        assertEquals("Idziemy jutro o 10?", alice.db.message(q.id)!!.text); assertFalse(alice.db.message(q.id)!!.deleted)
        // Bob nie może też edytować własnej wiadomości u Alice jako "mojej" – jego edycja własnej jest OK
        bob.edit(bob.db.message(reply.id)!!, "Tak, o 10:30"); Thread.sleep(500); alice.drainPending()
        assertEquals("Tak, o 10:30", alice.db.message(reply.id)!!.text)
        // nieznany rodzaj – bez crasha, nic nie dochodzi
        val before = alice.db.messages(bobC.id).size
        alice.handleIncoming(craft(bob, alice, MessageContent("FUT-$id", 9.0, "hologram", text = "z przyszłości")))
        assertEquals(before, alice.db.messages(bobC.id).size); assertNull(alice.db.message("FUT-$id"))
        // usunięcie u wszystkich
        alice.deleteForAll(alice.db.message(q.id)!!); Thread.sleep(500); bob.drainPending()
        val gone = bob.db.message(q.id)!!
        assertTrue(gone.deleted); assertEquals("", gone.text); assertTrue(alice.db.message(q.id)!!.deleted)
        // plik 100 KB
        val pdf = ByteArray(100_000) { (it * 7 % 251).toByte() }
        alice.sendFile(pdf, "raport.pdf", "application/pdf", bobC)
        assertEquals(Message.SENT, alice.db.messages(bobC.id).first { it.kind == Message.FILE }.status)
        bob.drainPending(); Thread.sleep(1500)
        val f = bob.db.messages(aliceC.id).first { it.kind == Message.FILE }
        assertEquals("raport.pdf", f.fileName); assertEquals("application/pdf", f.mime); assertNotNull("plik pobrany", f.attachmentFile)
        assertArrayEquals(pdf, File(bob.mediaDir, f.attachmentFile!!).readBytes())
        assertTrue(f.attachmentFile!!.endsWith(".pdf"))
        // głosówka
        val m4a = ByteArray(3000) { it.toByte() }
        bob.sendAudio(m4a, 4.2, aliceC, replyTo = f.id)
        Thread.sleep(500); alice.drainPending(); Thread.sleep(1500)
        val a = alice.db.messages(bobC.id).first { it.kind == Message.AUDIO }
        assertEquals(4.2, a.duration, 0.001); assertEquals("audio/mp4", a.mime); assertEquals(f.id, a.replyTo)
        assertArrayEquals(m4a, File(alice.mediaDir, a.attachmentFile!!).readBytes()); assertTrue(a.attachmentFile!!.endsWith(".m4a"))
        // za duży plik odrzucony lokalnie
        assertThrows(SzeptException::class.java) { runBlocking { alice.sendFile(ByteArray(Config.MAX_FILE + 1), "duzy.bin", null, bobC) } }
        alice.api.deleteAccount(); bob.api.deleteAccount()
    }

    @Test fun v03GroupReactionAndEdit() = runBlocking {
        val server = System.getProperty("szept.server") ?: InstrumentationRegistry.getArguments().getString("server") ?: "http://10.0.2.2:11437"
        assumeTrue("serwer niedostępny", try { SzeptApi(server, null).health() } catch (e: Exception) { false })
        val id = UUID.randomUUID().toString().take(8)
        fun eng(n: String) = Engine(ctx, tmp("k$n"), prefsName = "t3-$n-$id", dbName = "t3-$n-$id.db").also { it.db.wipe(); it.serverUrl = server }
        val a = eng("a"); val b = eng("b"); val c = eng("c")
        a.displayName = "Ala"; b.displayName = "Bartek"; c.displayName = "Celina"
        a.register(); b.register(); c.register(); a.stop(); b.stop(); c.stop()
        val g = a.createGroup("Ekipa", listOf(a.addContact(b.myLink!!, "Bartek"), a.addContact(c.myLink!!, "Celina")))
        a.sendText("Kto bierze namiot?", g)
        b.drainPending(); c.drainPending()
        val atC = c.db.messagesForGroup(g.id).first { it.senderId == a.accountId }
        c.react(atC, "🙏"); Thread.sleep(500); a.drainPending(); b.drainPending()
        assertEquals(mapOf(c.accountId!! to "🙏"), a.db.message(atC.id)!!.reactionMap())
        assertEquals(mapOf(c.accountId!! to "🙏"), b.db.message(atC.id)!!.reactionMap())
        // odpowiedź w grupie
        b.sendText("Ja", b.db.group(g.id)!!, replyTo = atC.id); Thread.sleep(500); a.drainPending(); c.drainPending()
        assertEquals(atC.id, a.db.messagesForGroup(g.id).first { it.text == "Ja" }.replyTo)
        // Bartek preparuje edycję wiadomości Ali w grupie – ignorowane
        a.handleIncoming(craft(b, a, MessageContent(UUID.randomUUID().toString(), 9.0, "edit", text = "hack", ref = atC.id, g = g.id)))
        assertEquals("Kto bierze namiot?", a.db.message(atC.id)!!.text)
        // Ala edytuje własną – dociera do obu
        a.edit(a.db.message(atC.id)!!, "Kto bierze namiot i grill?"); Thread.sleep(500); b.drainPending(); c.drainPending()
        assertEquals("Kto bierze namiot i grill?", b.db.message(atC.id)!!.text); assertEquals("Kto bierze namiot i grill?", c.db.message(atC.id)!!.text)
        // reakcja nie zmienia licznika nieprzeczytanych
        val unreadBefore = a.db.group(g.id)!!.unread
        b.react(b.db.message(atC.id)!!, "👍"); Thread.sleep(500); a.drainPending()
        assertEquals(unreadBefore, a.db.group(g.id)!!.unread); assertEquals(2, a.db.message(atC.id)!!.reactionMap().size)
        a.api.deleteAccount(); b.api.deleteAccount(); c.api.deleteAccount()
    }
}
