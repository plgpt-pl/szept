package pl.fourdt.szept

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Interop Android ↔ iOS przez prawdziwy serwer. Uruchamiany tylko z argumentem `-e interop 1`:
 * 1) rejestruje konto i loguje swój link (tag SZEPT_INTEROP), 2) czeka do 180 s na wiadomość z iPhone'a,
 * 3) odpowiada i kończy. Druga strona: test `InteropTests` w apce iOS z TEST_RUNNER_SZEPT_PEER_LINK=<ten link>.
 */
@RunWith(AndroidJUnit4::class)
class InteropTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun talkToIPhone() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("tylko na żądanie (-e interop 1)", args.getString("interop") == "1")
        val server = args.getString("server") ?: "http://10.0.2.2:11437"
        val id = UUID.randomUUID().toString().take(8)
        val me = Engine(ctx, File(ctx.cacheDir, "interop-$id").also { it.mkdirs() }, prefsName = "interop-$id", dbName = "interop-$id.db")
        me.serverUrl = server; me.displayName = "Android"
        me.register(); me.stop()
        Log.i("SZEPT_INTEROP", "LINK " + me.myLink!!.url)
        val deadline = System.currentTimeMillis() + 300_000
        var from: Contact? = null
        while (System.currentTimeMillis() < deadline && from == null) {
            me.drainPending()
            from = me.db.contacts().firstOrNull { me.db.messages(it.id).any { m -> !m.isMine } }
            if (from == null) Thread.sleep(1000)
        }
        assertNotNull("iPhone nie napisał w 300 s", from)
        val got = me.db.messages(from!!.id).first { !it.isMine }
        Log.i("SZEPT_INTEROP", "GOT ${from.name}: ${got.text}")
        assertEquals("iPhone", from.name)
        assertTrue(got.text.contains("iPhone"))
        me.sendText("Cześć z Androida 🤖, dostałem: „${got.text}”", from)
        assertEquals(Message.SENT, me.db.messages(from.id).first { it.isMine }.status)
        // poczekaj na potwierdzenie odbioru od iPhone'a
        val d2 = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < d2 && me.db.messages(from.id).first { it.isMine }.status != Message.DELIVERED) { me.drainPending(); Thread.sleep(1000) }
        assertEquals("brak potwierdzenia odbioru z iPhone'a", Message.DELIVERED, me.db.messages(from.id).first { it.isMine }.status)
        Log.i("SZEPT_INTEROP", "DONE")
        me.api.deleteAccount()
    }
}
