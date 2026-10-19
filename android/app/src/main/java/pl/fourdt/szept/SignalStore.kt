package pl.fourdt.szept

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Trwały magazyn protokołu Signal: pliki w katalogu poza kopią zapasową (noBackupFilesDir), jak w wersji iOS.
 * Tożsamość, klucze wstępne, sesje i przypięte klucze kontaktów (TOFU).
 */
class SignalStore(val root: File) : IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {
    private val lock = Any()
    private val identity: IdentityKeyPair
    private val metaFile = File(root, "meta.json")
    var registrationId = 0; private set
    var nextPreKeyId = 1; private set
    var nextKyberId = 1; private set
    var signedPreKeyId = 0; private set
    var lastResortKyberId = 0; private set

    init {
        for (d in listOf("", "identities", "prekeys", "signed", "kyber", "sessions")) File(root, d).mkdirs()
        val idFile = File(root, "identity.bin")
        if (idFile.exists() && metaFile.exists()) {
            identity = IdentityKeyPair(idFile.readBytes())
            val m = JSONObject(metaFile.readText())
            registrationId = m.getInt("registrationId"); nextPreKeyId = m.getInt("nextPreKeyId")
            nextKyberId = m.getInt("nextKyberId"); signedPreKeyId = m.getInt("signedPreKeyId"); lastResortKyberId = m.getInt("lastResortKyberId")
        } else {
            identity = IdentityKeyPair.generate()
            registrationId = Random.nextInt(1, 16381)
            idFile.writeBytes(identity.serialize())
            saveMeta()
        }
    }

    private fun saveMeta() = metaFile.writeText(JSONObject().put("registrationId", registrationId).put("nextPreKeyId", nextPreKeyId)
        .put("nextKyberId", nextKyberId).put("signedPreKeyId", signedPreKeyId).put("lastResortKyberId", lastResortKyberId).toString())

    fun takePreKeyId() = synchronized(lock) { nextPreKeyId++.also { saveMeta() } }
    fun takeKyberId(lastResort: Boolean) = synchronized(lock) { nextKyberId++.also { if (lastResort) lastResortKyberId = it; saveMeta() } }
    fun takeSignedPreKeyId() = synchronized(lock) { (++signedPreKeyId).also { saveMeta() } }

    private fun f(dir: String, name: String) = File(File(root, dir), name)
    private fun safe(a: SignalProtocolAddress) = a.name.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '.' }.joinToString("") + "_" + a.deviceId

    // IdentityKeyStore
    override fun getIdentityKeyPair() = identity
    override fun getLocalRegistrationId() = registrationId
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange = synchronized(lock) {
        val file = f("identities", safe(address))
        val old = if (file.exists()) IdentityKey(file.readBytes()) else null
        file.writeBytes(identityKey.serialize())
        if (old == null || old == identityKey) IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED else IdentityKeyStore.IdentityChange.REPLACED_EXISTING
    }
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val known = getIdentity(address) ?: return true  // TOFU
        return known == identityKey
    }
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val file = f("identities", safe(address)); return if (file.exists()) IdentityKey(file.readBytes()) else null
    }
    fun pinIdentity(key: IdentityKey, address: SignalProtocolAddress) { saveIdentity(address, key) }
    fun forgetIdentityAndSession(address: SignalProtocolAddress) = synchronized(lock) {
        f("identities", safe(address)).delete(); f("sessions", safe(address)).delete()
    }

    // PreKeyStore
    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val file = f("prekeys", "$preKeyId"); if (!file.exists()) throw InvalidKeyIdException("brak prekey $preKeyId")
        return PreKeyRecord(file.readBytes())
    }
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) = synchronized(lock) { f("prekeys", "$preKeyId").writeBytes(record.serialize()) }
    override fun containsPreKey(preKeyId: Int) = f("prekeys", "$preKeyId").exists()
    override fun removePreKey(preKeyId: Int) { synchronized(lock) { f("prekeys", "$preKeyId").delete() } }
    val preKeyCount get() = File(root, "prekeys").list()?.size ?: 0
    val kyberPreKeyCount get() = File(root, "kyber").list()?.count { it != "$lastResortKyberId" } ?: 0

    // SignedPreKeyStore
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val file = f("signed", "$signedPreKeyId"); if (!file.exists()) throw InvalidKeyIdException("brak signed prekey $signedPreKeyId")
        return SignedPreKeyRecord(file.readBytes())
    }
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = File(root, "signed").listFiles()?.map { SignedPreKeyRecord(it.readBytes()) } ?: emptyList()
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) = synchronized(lock) { f("signed", "$signedPreKeyId").writeBytes(record.serialize()) }
    override fun containsSignedPreKey(signedPreKeyId: Int) = f("signed", "$signedPreKeyId").exists()
    override fun removeSignedPreKey(signedPreKeyId: Int) { synchronized(lock) { f("signed", "$signedPreKeyId").delete() } }

    // KyberPreKeyStore
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val file = f("kyber", "$kyberPreKeyId"); if (!file.exists()) throw InvalidKeyIdException("brak kyber prekey $kyberPreKeyId")
        return KyberPreKeyRecord(file.readBytes())
    }
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = File(root, "kyber").listFiles()?.map { KyberPreKeyRecord(it.readBytes()) } ?: emptyList()
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = synchronized(lock) { f("kyber", "$kyberPreKeyId").writeBytes(record.serialize()) }
    override fun containsKyberPreKey(kyberPreKeyId: Int) = f("kyber", "$kyberPreKeyId").exists()
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        if (kyberPreKeyId == lastResortKyberId) return   // klucz „ostatniej szansy” zostaje
        synchronized(lock) { f("kyber", "$kyberPreKeyId").delete() }
    }

    // SessionStore
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? {
        val file = f("sessions", safe(address)); return if (file.exists()) SessionRecord(file.readBytes()) else null
    }
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { loadSession(it) ?: throw NoSessionException(it, "brak sesji") }
    override fun getSubDeviceSessions(name: String): List<Int> =
        File(root, "sessions").list()?.filter { it.startsWith("${name}_") }?.mapNotNull { it.substringAfterLast('_').toIntOrNull() }?.filter { it != 1 } ?: emptyList()
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) = synchronized(lock) { f("sessions", safe(address)).writeBytes(record.serialize()) }
    override fun containsSession(address: SignalProtocolAddress) = loadSession(address)?.hasSenderChain() == true
    override fun deleteSession(address: SignalProtocolAddress) { synchronized(lock) { f("sessions", safe(address)).delete() } }
    override fun deleteAllSessions(name: String) { synchronized(lock) { File(root, "sessions").listFiles()?.filter { it.name.startsWith("${name}_") }?.forEach { it.delete() } } }

    fun destroy() = synchronized(lock) { root.deleteRecursively() }
}
