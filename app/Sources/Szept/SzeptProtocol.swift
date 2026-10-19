import Foundation
import LibSignalClient

// MARK: - JSON wymieniany z serwerem

struct KeyJSON: Codable { var id: UInt32; var key: String }
struct SignedKeyJSON: Codable { var id: UInt32; var key: String; var signature: String }

struct RegistrationPayload: Encodable {
    var identity_key: String
    var registration_id: UInt32
    var signed_prekey: SignedKeyJSON
    var kyber_last_resort: SignedKeyJSON
    var prekeys: [KeyJSON]
    var kyber_prekeys: [SignedKeyJSON]
}

struct KeyUploadPayload: Encodable {
    var signed_prekey: SignedKeyJSON?
    var kyber_last_resort: SignedKeyJSON?
    var prekeys: [KeyJSON]
    var kyber_prekeys: [SignedKeyJSON]
}

struct BundleJSON: Decodable {
    var account_id: String
    var device_id: UInt32
    var identity_key: String
    var registration_id: UInt32
    var signed_prekey: SignedKeyJSON
    var prekey: KeyJSON?
    var kyber_prekey: SignedKeyJSON
}

/// Koperta wewnątrz zewnętrznej warstwy HPKE (serwer widzi wyłącznie szyfrogram HPKE do klucza odbiorcy).
struct Envelope: Codable {
    var v: Int = 1
    var t: UInt8          // CiphertextMessage.MessageType.rawValue (2 = whisper, 3 = preKey)
    var s: String         // account_id nadawcy
    var c: Data           // wiadomość protokołu Signal
}

enum SzeptError: LocalizedError {
    case identityMismatch, badLink, noSession, badEnvelope, server(String), notRegistered
    var errorDescription: String? {
        switch self {
        case .identityMismatch: return "Klucz tożsamości kontaktu nie zgadza się z zapisanym. Zweryfikuj kontakt osobiście."
        case .badLink: return "To nie jest poprawny link/kod Szept."
        case .noSession: return "Brak sesji z tym kontaktem."
        case .badEnvelope: return "Nieczytelna koperta."
        case .server(let s): return "Serwer: \(s)"
        case .notRegistered: return "Najpierw utwórz tożsamość."
        }
    }
}

// MARK: - Warstwa protokołu

/// Całe szyfrowanie Szeptu. Kryptografia pochodzi z libsignal (PQXDH + Double Ratchet + HPKE);
/// ta klasa tylko spina magazyn, adresy i format koperty.
final class SzeptProtocol {
    static let envelopeInfo = "szept-envelope-v1"
    static let attachmentInfo = Data("szept-attachment-v1".utf8)

    let store: SignalStore
    private(set) var accountId: String?
    private let ctx = NullContext()

    init(store: SignalStore, accountId: String?) {
        self.store = store
        self.accountId = accountId
    }

    func setAccountId(_ id: String) { accountId = id }

    var identityKey: IdentityKey { store.identityKeyPair.identityKey }
    var identityKeyBase64: String { identityKey.serialize().base64EncodedString() }

    static func address(_ accountId: String) throws -> ProtocolAddress {
        try ProtocolAddress(name: accountId, deviceId: 1)
    }

    private func myAddress() throws -> ProtocolAddress {
        guard let accountId else { throw SzeptError.notRegistered }
        return try Self.address(accountId)
    }

    // MARK: klucze dla serwera

    /// Pierwsza rejestracja: nowy signed prekey, kyber „ostatniej szansy” i paczka kluczy jednorazowych.
    func makeRegistration() throws -> RegistrationPayload {
        let spk = try rotateSignedPreKey()
        let klr = try makeKyber(count: 1, lastResort: true)[0]
        let (pre, kyb) = try makeOneTimeKeys(count: Config.preKeyBatch)
        return RegistrationPayload(identity_key: identityKeyBase64, registration_id: store.registrationId,
                                   signed_prekey: spk, kyber_last_resort: klr, prekeys: pre, kyber_prekeys: kyb)
    }

    func makeTopUp(count: Int = Config.preKeyBatch) throws -> KeyUploadPayload {
        let (pre, kyb) = try makeOneTimeKeys(count: count)
        return KeyUploadPayload(signed_prekey: nil, kyber_last_resort: nil, prekeys: pre, kyber_prekeys: kyb)
    }

    func rotateSignedPreKey() throws -> SignedKeyJSON {
        var id: UInt32 = 0
        try store.updateMeta { m in m.signedPreKeyId += 1; id = m.signedPreKeyId }
        let priv = PrivateKey.generate()
        let sig = store.identityKeyPair.privateKey.generateSignature(message: priv.publicKey.serialize())
        let rec = try SignedPreKeyRecord(id: id, timestamp: UInt64(Date().timeIntervalSince1970 * 1000), privateKey: priv, signature: sig)
        try store.storeSignedPreKey(rec, id: id, context: ctx)
        return SignedKeyJSON(id: id, key: priv.publicKey.serialize().base64EncodedString(), signature: sig.base64EncodedString())
    }

    private func makeKyber(count: Int, lastResort: Bool) throws -> [SignedKeyJSON] {
        var out: [SignedKeyJSON] = []
        for _ in 0..<count {
            var id: UInt32 = 0
            try store.updateMeta { m in id = m.nextKyberId; m.nextKyberId += 1; if lastResort { m.lastResortKyberId = id } }
            let kp = KEMKeyPair.generate()
            let sig = store.identityKeyPair.privateKey.generateSignature(message: kp.publicKey.serialize())
            let rec = try KyberPreKeyRecord(id: id, timestamp: UInt64(Date().timeIntervalSince1970 * 1000), keyPair: kp, signature: sig)
            try store.storeKyberPreKey(rec, id: id, context: ctx)
            out.append(SignedKeyJSON(id: id, key: kp.publicKey.serialize().base64EncodedString(), signature: sig.base64EncodedString()))
        }
        return out
    }

    private func makeOneTimeKeys(count: Int) throws -> ([KeyJSON], [SignedKeyJSON]) {
        var pre: [KeyJSON] = []
        for _ in 0..<count {
            var id: UInt32 = 0
            try store.updateMeta { m in id = m.nextPreKeyId; m.nextPreKeyId += 1 }
            let priv = PrivateKey.generate()
            try store.storePreKey(try PreKeyRecord(id: id, privateKey: priv), id: id, context: ctx)
            pre.append(KeyJSON(id: id, key: priv.publicKey.serialize().base64EncodedString()))
        }
        return (pre, try makeKyber(count: count, lastResort: false))
    }

    // MARK: sesje

    func hasSession(with accountId: String) -> Bool {
        (try? Self.address(accountId)).map { store.hasSession(for: $0) } ?? false
    }

    /// Zakłada sesję z paczki kluczy odbiorcy. `pinnedIdentity` = klucz z QR/linku; musi się zgadzać.
    func processBundle(_ b: BundleJSON, pinnedIdentity: Data?) throws {
        guard let idData = Data(base64Encoded: b.identity_key),
              let spkData = Data(base64Encoded: b.signed_prekey.key),
              let spkSig = Data(base64Encoded: b.signed_prekey.signature),
              let kyData = Data(base64Encoded: b.kyber_prekey.key),
              let kySig = Data(base64Encoded: b.kyber_prekey.signature) else { throw SzeptError.badEnvelope }
        if let pinnedIdentity, pinnedIdentity != idData { throw SzeptError.identityMismatch }
        let identity = try IdentityKey(bytes: idData)
        let spk = try PublicKey(spkData)
        let kyber = try KEMPublicKey(kyData)
        let bundle: PreKeyBundle
        if let pk = b.prekey, let pkData = Data(base64Encoded: pk.key) {
            bundle = try PreKeyBundle(registrationId: b.registration_id, deviceId: b.device_id, prekeyId: pk.id,
                                      prekey: try PublicKey(pkData), signedPrekeyId: b.signed_prekey.id, signedPrekey: spk,
                                      signedPrekeySignature: spkSig, identity: identity, kyberPrekeyId: b.kyber_prekey.id,
                                      kyberPrekey: kyber, kyberPrekeySignature: kySig)
        } else {
            bundle = try PreKeyBundle(registrationId: b.registration_id, deviceId: b.device_id,
                                      signedPrekeyId: b.signed_prekey.id, signedPrekey: spk, signedPrekeySignature: spkSig,
                                      identity: identity, kyberPrekeyId: b.kyber_prekey.id, kyberPrekey: kyber,
                                      kyberPrekeySignature: kySig)
        }
        try processPreKeyBundle(bundle, for: try Self.address(b.account_id), ourAddress: try myAddress(),
                                sessionStore: store, identityStore: store, context: ctx)
    }

    // MARK: szyfrowanie wiadomości

    /// Szyfruje treść dla kontaktu i pakuje w kopertę HPKE do jego klucza tożsamości (serwer nie widzi nadawcy).
    func seal(_ plaintext: Data, to accountId: String, recipientIdentity: Data) throws -> Data {
        let addr = try Self.address(accountId)
        let msg = try signalEncrypt(message: plaintext, for: addr, localAddress: try myAddress(),
                                    sessionStore: store, identityStore: store, context: ctx)
        guard let me = self.accountId else { throw SzeptError.notRegistered }
        let env = Envelope(t: msg.messageType.rawValue, s: me, c: msg.serialize())
        let body = try JSONEncoder().encode(env)
        return try IdentityKey(bytes: recipientIdentity).publicKey.seal(body, info: Self.envelopeInfo)
    }

    struct Opened { var sender: String; var plaintext: Data; var senderIdentity: IdentityKey; var isPreKey: Bool }

    func open(_ sealed: Data) throws -> Opened {
        let body = try store.identityKeyPair.privateKey.open(sealed, info: Self.envelopeInfo)
        let env = try JSONDecoder().decode(Envelope.self, from: body)
        guard env.v == 1 else { throw SzeptError.badEnvelope }
        let from = try Self.address(env.s)
        let plaintext: Data
        let isPreKey: Bool
        switch CiphertextMessage.MessageType(rawValue: env.t) {
        case .preKey:
            plaintext = try signalDecryptPreKey(message: try PreKeySignalMessage(bytes: env.c), from: from,
                                                localAddress: try myAddress(), sessionStore: store, identityStore: store,
                                                preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: ctx)
            isPreKey = true
        case .whisper:
            plaintext = try signalDecrypt(message: try SignalMessage(bytes: env.c), from: from, to: try myAddress(),
                                          sessionStore: store, identityStore: store, context: ctx)
            isPreKey = false
        default:
            throw SzeptError.badEnvelope
        }
        guard let ident = try store.identity(for: from, context: ctx) else { throw SzeptError.badEnvelope }
        return Opened(sender: env.s, plaintext: plaintext, senderIdentity: ident, isPreKey: isPreKey)
    }

    // MARK: załączniki (AES-256-GCM-SIV, klucz i nonce jadą w zaszyfrowanej wiadomości)

    struct AttachmentKey: Codable { var key: Data; var nonce: Data }

    static func encryptAttachment(_ data: Data) throws -> (Data, AttachmentKey) {
        var key = Data(count: 32); _ = key.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        var nonce = Data(count: 12); _ = nonce.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }
        let ct = try Aes256GcmSiv(key: key).encrypt(data, nonce: nonce, associatedData: attachmentInfo)
        return (ct, AttachmentKey(key: key, nonce: nonce))
    }

    static func decryptAttachment(_ data: Data, key: AttachmentKey) throws -> Data {
        try Aes256GcmSiv(key: key.key).decrypt(data, nonce: key.nonce, associatedData: attachmentInfo)
    }

    // MARK: numer bezpieczeństwa (jak w Signalu: 60 cyfr z obu kluczy tożsamości)

    func safetyNumber(with accountId: String, identity: Data) throws -> String {
        guard let me = self.accountId else { throw SzeptError.notRegistered }
        let other = try IdentityKey(bytes: identity)
        let fp = try NumericFingerprintGenerator(iterations: 5200).create(
            version: 2, localIdentifier: Data(me.utf8), localKey: identityKey.publicKey,
            remoteIdentifier: Data(accountId.utf8), remoteKey: other.publicKey)
        return fp.displayable.formatted
    }
}

// MARK: - Treść wiadomości (po odszyfrowaniu)

struct MessageContent: Codable {
    enum Kind: String, Codable { case text, image, receipt, timer, group }
    struct Attachment: Codable { var id: String; var key: Data; var nonce: Data; var size: Int; var w: Int; var h: Int }
    /// Członek grupy w wiadomości sterującej: id konta, klucz tożsamości (base64), nazwa.
    struct Member: Codable, Equatable { var i: String; var k: String; var n: String? }
    var id: String
    var ts: Double
    var kind: Kind
    var text: String?
    var att: Attachment?
    var expire: Int?       // sekundy do zniknięcia (0/nil = nie znika)
    var ref: String?       // id wiadomości, której dotyczy potwierdzenie
    var name: String?      // opcjonalna nazwa nadawcy (ustawiana w apce)
    // grupy (docs/GRUPY.md)
    var g: String?         // id grupy – w każdej wiadomości dotyczącej grupy
    var gname: String?     // nazwa grupy (kind: group, op: set)
    var members: [Member]? // pełna lista członków łącznie z nadawcą (kind: group, op: set)
    var op: String?        // "set" | "leave" (kind: group)
}

// MARK: - Link / kod QR kontaktu

struct ContactLink: Equatable {
    var accountId: String
    var identityKey: Data
    var name: String?

    var url: URL {
        var c = URLComponents()
        c.scheme = Config.linkScheme; c.host = "add"
        // '+' i '/' z base64 kodowane procentowo, żeby parsery zapytań (np. Android) nie zamieniały '+' na spację
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s }
        var items = [URLQueryItem(name: "i", value: enc(accountId)), URLQueryItem(name: "k", value: enc(identityKey.base64EncodedString()))]
        if let name, !name.isEmpty { items.append(URLQueryItem(name: "n", value: enc(name))) }
        c.percentEncodedQueryItems = items
        return c.url!
    }

    init(accountId: String, identityKey: Data, name: String?) {
        self.accountId = accountId; self.identityKey = identityKey; self.name = name
    }

    init?(string: String) {
        guard let c = URLComponents(string: string.trimmingCharacters(in: .whitespacesAndNewlines)),
              c.scheme == Config.linkScheme, c.host == "add",
              let i = c.queryItems?.first(where: { $0.name == "i" })?.value, !i.isEmpty, i.count <= 64,
              let k = c.queryItems?.first(where: { $0.name == "k" })?.value, let kd = Data(base64Encoded: k),
              (try? IdentityKey(bytes: kd)) != nil else { return nil }
        accountId = i; identityKey = kd
        name = c.queryItems?.first(where: { $0.name == "n" })?.value
    }
}
