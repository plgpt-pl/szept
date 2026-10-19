import Foundation
import LibSignalClient

/// Trwały magazyn protokołu Signal (tożsamość, klucze wstępne, sesje, klucze kontaktów).
/// Pliki w katalogu chronionym Data Protection (szyfrowane kluczem urządzenia) i wyłączonym z kopii zapasowych.
/// Jedna instancja na konto; dostęp serializowany blokadą.
final class SignalStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {
    struct Meta: Codable {
        var registrationId: UInt32
        var nextPreKeyId: UInt32 = 1
        var nextKyberId: UInt32 = 1
        var signedPreKeyId: UInt32 = 0
        var lastResortKyberId: UInt32 = 0
    }

    let root: URL
    private let lock = NSLock()
    private var identity: IdentityKeyPair
    private(set) var meta: Meta

    static func exists(at root: URL) -> Bool {
        FileManager.default.fileExists(atPath: root.appendingPathComponent("identity.bin").path)
    }

    /// Otwiera istniejący magazyn albo tworzy nową tożsamość.
    init(root: URL) throws {
        self.root = root
        for sub in ["", "identities", "prekeys", "signed", "kyber", "sessions"] {
            let dir = root.appendingPathComponent(sub)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true,
                                                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        var r = root
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? r.setResourceValues(rv)

        let idURL = root.appendingPathComponent("identity.bin")
        let metaURL = root.appendingPathComponent("meta.json")
        if let d = try? Data(contentsOf: idURL), let m = try? Data(contentsOf: metaURL) {
            identity = try IdentityKeyPair(bytes: d)
            meta = try JSONDecoder().decode(Meta.self, from: m)
        } else {
            identity = IdentityKeyPair.generate()
            meta = Meta(registrationId: UInt32.random(in: 1...16380))
            try Self.write(identity.serialize(), to: idURL)
            try Self.write(JSONEncoder().encode(meta), to: metaURL)
        }
    }

    var identityKeyPair: IdentityKeyPair { identity }
    var registrationId: UInt32 { meta.registrationId }

    func updateMeta(_ f: (inout Meta) -> Void) throws {
        lock.lock(); defer { lock.unlock() }
        f(&meta)
        try Self.write(JSONEncoder().encode(meta), to: root.appendingPathComponent("meta.json"))
    }

    static func write(_ data: Data, to url: URL) throws {
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func file(_ dir: String, _ name: String) -> URL {
        root.appendingPathComponent(dir).appendingPathComponent(name)
    }

    private static func safe(_ address: ProtocolAddress) -> String {
        let n = address.name.map { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" ? $0 : "." }
        return "\(String(n))_\(address.deviceId)"
    }

    // MARK: IdentityKeyStore
    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair { identity }
    func localRegistrationId(context: StoreContext) throws -> UInt32 { meta.registrationId }

    func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
        lock.lock(); defer { lock.unlock() }
        let url = file("identities", Self.safe(address))
        let old = (try? Data(contentsOf: url)).flatMap { try? IdentityKey(bytes: $0) }
        try Self.write(identity.serialize(), to: url)
        return (old == nil || old == identity) ? .newOrUnchanged : .replacedExisting
    }

    func isTrustedIdentity(_ identity: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext) throws -> Bool {
        guard let known = try self.identity(for: address, context: context) else { return true } // TOFU
        return known == identity
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        guard let d = try? Data(contentsOf: file("identities", Self.safe(address))) else { return nil }
        return try IdentityKey(bytes: d)
    }

    /// Przypięcie klucza kontaktu z kodu QR/linku – zanim przyjdzie pierwsza wiadomość.
    func pinIdentity(_ key: IdentityKey, for address: ProtocolAddress) throws {
        _ = try saveIdentity(key, for: address, context: NullContext())
    }

    func forgetIdentityAndSession(for address: ProtocolAddress) {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: file("identities", Self.safe(address)))
        try? FileManager.default.removeItem(at: file("sessions", Self.safe(address)))
    }

    // MARK: PreKeyStore
    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let d = try? Data(contentsOf: file("prekeys", "\(id)")) else {
            throw SignalError.invalidKeyIdentifier("brak prekey \(id)")
        }
        return try PreKeyRecord(bytes: d)
    }
    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        lock.lock(); defer { lock.unlock() }
        try Self.write(record.serialize(), to: file("prekeys", "\(id)"))
    }
    func removePreKey(id: UInt32, context: StoreContext) throws {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: file("prekeys", "\(id)"))
    }
    var preKeyCount: Int { (try? FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("prekeys").path).count) ?? 0 }
    var kyberPreKeyCount: Int {
        let all = (try? FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent("kyber").path)) ?? []
        return all.filter { $0 != "\(meta.lastResortKyberId)" }.count
    }

    // MARK: SignedPreKeyStore
    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let d = try? Data(contentsOf: file("signed", "\(id)")) else {
            throw SignalError.invalidKeyIdentifier("brak signed prekey \(id)")
        }
        return try SignedPreKeyRecord(bytes: d)
    }
    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        lock.lock(); defer { lock.unlock() }
        try Self.write(record.serialize(), to: file("signed", "\(id)"))
    }

    // MARK: KyberPreKeyStore
    func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        guard let d = try? Data(contentsOf: file("kyber", "\(id)")) else {
            throw SignalError.invalidKeyIdentifier("brak kyber prekey \(id)")
        }
        return try KyberPreKeyRecord(bytes: d)
    }
    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        lock.lock(); defer { lock.unlock() }
        try Self.write(record.serialize(), to: file("kyber", "\(id)"))
    }
    func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext) throws {
        // Klucz jednorazowy znika po użyciu; klucz „ostatniej szansy” zostaje.
        guard id != meta.lastResortKyberId else { return }
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: file("kyber", "\(id)"))
    }

    // MARK: SessionStore
    func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        guard let d = try? Data(contentsOf: file("sessions", Self.safe(address))) else { return nil }
        return try SessionRecord(bytes: d)
    }
    func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] {
        try addresses.compactMap { try loadSession(for: $0, context: context) }
    }
    func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        lock.lock(); defer { lock.unlock() }
        try Self.write(record.serialize(), to: file("sessions", Self.safe(address)))
    }
    func hasSession(for address: ProtocolAddress) -> Bool {
        (try? loadSession(for: address, context: NullContext()))?.hasCurrentState ?? false
    }

    /// Kasuje cały magazyn (usunięcie konta).
    func destroy() {
        lock.lock(); defer { lock.unlock() }
        try? FileManager.default.removeItem(at: root)
    }
}
