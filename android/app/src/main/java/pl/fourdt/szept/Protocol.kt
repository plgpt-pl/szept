package pl.fourdt.szept

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.signal.libsignal.crypto.Aes256GcmSiv
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.security.SecureRandom

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.unb64(): ByteArray = Base64.decode(this, Base64.DEFAULT)

@OptIn(ExperimentalSerializationApi::class)
val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

// --- JSON zgodny 1:1 z wersją iOS i serwerem ---
@Serializable data class KeyJSON(val id: Int, val key: String)
@Serializable data class SignedKeyJSON(val id: Int, val key: String, val signature: String)
@Serializable data class RegistrationPayload(val identity_key: String, val registration_id: Int, val signed_prekey: SignedKeyJSON,
                                             val kyber_last_resort: SignedKeyJSON, val prekeys: List<KeyJSON>, val kyber_prekeys: List<SignedKeyJSON>)
@Serializable data class KeyUploadPayload(val signed_prekey: SignedKeyJSON? = null, val kyber_last_resort: SignedKeyJSON? = null,
                                          val prekeys: List<KeyJSON>, val kyber_prekeys: List<SignedKeyJSON>)
@Serializable data class BundleJSON(val account_id: String, val device_id: Int, val identity_key: String, val registration_id: Int,
                                    val signed_prekey: SignedKeyJSON, val prekey: KeyJSON? = null, val kyber_prekey: SignedKeyJSON)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class Envelope(@EncodeDefault val v: Int = 1, val t: Int, val s: String, val c: String)

@Serializable data class Attachment(val id: String, val key: String, val nonce: String, val size: Int, val w: Int, val h: Int)
@Serializable data class MessageContent(val id: String, val ts: Double, val kind: String, val text: String? = null, val att: Attachment? = null,
                                        val expire: Int? = null, val ref: String? = null, val name: String? = null)

class SzeptException(message: String) : Exception(message)

/** Całe szyfrowanie: libsignal (PQXDH + Double Ratchet + HPKE); tu tylko spięcie magazynu, adresów i koperty. */
class SzeptProtocol(val store: SignalStore, var accountId: String?) {
    companion object {
        const val ENVELOPE_INFO = "szept-envelope-v1"
        val ATTACHMENT_INFO = "szept-attachment-v1".toByteArray()
        const val PREKEY_BATCH = 40
        fun address(id: String) = SignalProtocolAddress(id, 1)

        fun encryptAttachment(data: ByteArray): Pair<ByteArray, Pair<ByteArray, ByteArray>> {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            return Aes256GcmSiv(key).encrypt(data, nonce, ATTACHMENT_INFO) to (key to nonce)
        }
        fun decryptAttachment(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray = Aes256GcmSiv(key).decrypt(data, nonce, ATTACHMENT_INFO)
    }

    val identityKey: IdentityKey get() = store.identityKeyPair.publicKey
    private fun me() = address(accountId ?: throw SzeptException("Najpierw utwórz tożsamość."))
    private fun now() = System.currentTimeMillis()

    fun makeRegistration(): RegistrationPayload {
        val spk = rotateSignedPreKey()
        val klr = makeKyber(1, lastResort = true)[0]
        val (pre, kyb) = makeOneTimeKeys(PREKEY_BATCH)
        return RegistrationPayload(identityKey.serialize().b64(), store.registrationId, spk, klr, pre, kyb)
    }

    fun makeTopUp(count: Int = PREKEY_BATCH): KeyUploadPayload { val (p, k) = makeOneTimeKeys(count); return KeyUploadPayload(prekeys = p, kyber_prekeys = k) }

    private fun rotateSignedPreKey(): SignedKeyJSON {
        val id = store.takeSignedPreKeyId()
        val kp = ECKeyPair.generate()
        val sig = store.identityKeyPair.privateKey.calculateSignature(kp.publicKey.serialize())
        store.storeSignedPreKey(id, SignedPreKeyRecord(id, now(), kp, sig))
        return SignedKeyJSON(id, kp.publicKey.serialize().b64(), sig.b64())
    }

    private fun makeKyber(count: Int, lastResort: Boolean) = (0 until count).map {
        val id = store.takeKyberId(lastResort)
        val kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val sig = store.identityKeyPair.privateKey.calculateSignature(kp.publicKey.serialize())
        store.storeKyberPreKey(id, KyberPreKeyRecord(id, now(), kp, sig))
        SignedKeyJSON(id, kp.publicKey.serialize().b64(), sig.b64())
    }

    private fun makeOneTimeKeys(count: Int): Pair<List<KeyJSON>, List<SignedKeyJSON>> {
        val pre = (0 until count).map {
            val id = store.takePreKeyId(); val kp = ECKeyPair.generate()
            store.storePreKey(id, PreKeyRecord(id, kp)); KeyJSON(id, kp.publicKey.serialize().b64())
        }
        return pre to makeKyber(count, lastResort = false)
    }

    fun hasSession(id: String) = store.containsSession(address(id))

    /** Sesja z paczki odbiorcy; `pinnedIdentity` (z QR/linku) musi się zgadzać – ochrona przed podmianą kluczy przez serwer. */
    fun processBundle(b: BundleJSON, pinnedIdentity: ByteArray?) {
        val idBytes = b.identity_key.unb64()
        if (pinnedIdentity != null && !pinnedIdentity.contentEquals(idBytes)) throw SzeptException("Klucz tożsamości kontaktu nie zgadza się z zapisanym.")
        val bundle = PreKeyBundle(b.registration_id, b.device_id, b.prekey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
            b.prekey?.let { ECPublicKey(it.key.unb64()) }, b.signed_prekey.id, ECPublicKey(b.signed_prekey.key.unb64()),
            b.signed_prekey.signature.unb64(), IdentityKey(idBytes), b.kyber_prekey.id, KEMPublicKey(b.kyber_prekey.key.unb64()),
            b.kyber_prekey.signature.unb64())
        SessionBuilder(store, store, store, store, address(b.account_id), me()).process(bundle)
    }

    private fun cipher(remote: SignalProtocolAddress) = SessionCipher(store, store, store, store, store, me(), remote)

    /** Szyfruje treść i pakuje w kopertę HPKE do klucza odbiorcy – serwer nie widzi nadawcy. */
    fun seal(plaintext: ByteArray, to: String, recipientIdentity: ByteArray): ByteArray {
        val msg: CiphertextMessage = cipher(address(to)).encrypt(plaintext)
        val env = Envelope(t = msg.type, s = accountId ?: throw SzeptException("Najpierw utwórz tożsamość."), c = msg.serialize().b64())
        return IdentityKey(recipientIdentity).publicKey.seal(json.encodeToString(Envelope.serializer(), env).toByteArray(), ENVELOPE_INFO)
    }

    data class Opened(val sender: String, val plaintext: ByteArray, val senderIdentity: IdentityKey, val isPreKey: Boolean)

    fun open(sealed: ByteArray): Opened {
        val env = json.decodeFromString(Envelope.serializer(), String(store.identityKeyPair.privateKey.open(sealed, ENVELOPE_INFO)))
        if (env.v != 1) throw SzeptException("Nieczytelna koperta.")
        val from = address(env.s)
        val c = cipher(from)
        val (plain, isPre) = when (env.t) {
            CiphertextMessage.PREKEY_TYPE -> c.decrypt(PreKeySignalMessage(env.c.unb64())) to true
            CiphertextMessage.WHISPER_TYPE -> c.decrypt(SignalMessage(env.c.unb64())) to false
            else -> throw SzeptException("Nieczytelna koperta.")
        }
        return Opened(env.s, plain, store.getIdentity(from) ?: throw SzeptException("Nieczytelna koperta."), isPre)
    }

    fun safetyNumber(contactId: String, identity: ByteArray): String {
        val me = accountId ?: throw SzeptException("Najpierw utwórz tożsamość.")
        return NumericFingerprintGenerator(5200).createFor(2, me.toByteArray(), identityKey, contactId.toByteArray(), IdentityKey(identity))
            .displayableFingerprint.displayText
    }
}

/** Link / kod QR kontaktu: `szept://add?i=<id>&k=<klucz tożsamości>&n=<nazwa>` */
data class ContactLink(val accountId: String, val identityKey: ByteArray, val name: String?) {
    val url: String get() = Uri.Builder().scheme("szept").authority("add").appendQueryParameter("i", accountId)
        .appendQueryParameter("k", identityKey.b64()).apply { if (!name.isNullOrEmpty()) appendQueryParameter("n", name) }.build().toString()

    companion object {
        fun parse(s: String): ContactLink? {
            return try {
                val u = Uri.parse(s.trim())
                if (u.scheme != "szept" || u.host != "add") return null
                // ręczne parsowanie: getQueryParameter zamienia '+' na spację, a '+' występuje w base64 klucza (link z iOS)
                val q = (u.encodedQuery ?: return null).split("&").mapNotNull { kv -> kv.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to Uri.decode(it[1]) } }.toMap()
                val i = q["i"] ?: return null
                val k = q["k"]?.unb64() ?: return null
                if (i.isEmpty() || i.length > 64) return null
                IdentityKey(k)  // walidacja klucza
                ContactLink(i, k, q["n"])
            } catch (e: Exception) { null }
        }
    }
}
