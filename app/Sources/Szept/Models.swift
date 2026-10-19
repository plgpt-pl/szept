import Foundation
import SwiftData

@Model
final class Contact {
    @Attribute(.unique) var id: String          // account_id na serwerze (losowe 128 bitów)
    var name: String
    var identityKey: Data                        // przypięty klucz tożsamości (z QR/linku albo z pierwszej wiadomości)
    var verified: Bool = false                   // numer bezpieczeństwa porównany osobiście
    var identityChanged: Bool = false            // klucz zmienił się po przypięciu – wymaga akceptacji
    var createdAt: Date = Date()
    var lastActivity: Date = Date()
    var unread: Int = 0
    var disappearAfter: Int = 0                  // sekundy; 0 = wiadomości nie znikają
    var fromUnknown: Bool = false                // pierwsza wiadomość przyszła bez wcześniejszego dodania
    @Relationship(deleteRule: .cascade, inverse: \Message.contact) var messages: [Message] = []

    init(id: String, name: String, identityKey: Data) {
        self.id = id; self.name = name; self.identityKey = identityKey
    }
}

@Model
final class Message {
    enum Status: Int { case pending = 0, sent = 1, delivered = 2, failed = 3 }

    @Attribute(.unique) var id: String
    var contact: Contact?
    var text: String
    var isMine: Bool
    var sentAt: Date
    var statusRaw: Int = 0
    var attachmentFile: String?                  // plik w katalogu media (odszyfrowany, chroniony Data Protection)
    var attachmentW: Int = 0
    var attachmentH: Int = 0
    var expiresAt: Date?

    var status: Status { get { Status(rawValue: statusRaw) ?? .pending } set { statusRaw = newValue.rawValue } }

    init(id: String = UUID().uuidString, contact: Contact, text: String, isMine: Bool, sentAt: Date = Date()) {
        self.id = id; self.contact = contact; self.text = text; self.isMine = isMine; self.sentAt = sentAt
    }
}

enum Storage {
    /// Katalog chroniony (Data Protection) i wyłączony z kopii zapasowych iCloud/iTunes.
    static var root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Szept")
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var u = base
        var rv = URLResourceValues(); rv.isExcludedFromBackup = true
        try? u.setResourceValues(rv)
        return base
    }()
    static var keys: URL { root.appendingPathComponent("keys") }
    static var media: URL {
        let m = root.appendingPathComponent("media")
        try? FileManager.default.createDirectory(at: m, withIntermediateDirectories: true,
                                                 attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        return m
    }

    static func makeContainer(inMemory: Bool = false) throws -> ModelContainer {
        let schema = Schema([Contact.self, Message.self])
        let cfg = inMemory
            ? ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
            : ModelConfiguration(schema: schema, url: root.appendingPathComponent("szept.store"), cloudKitDatabase: .none)
        return try ModelContainer(for: schema, configurations: [cfg])
    }

    static func wipeAll() {
        try? FileManager.default.removeItem(at: root)
    }
}
