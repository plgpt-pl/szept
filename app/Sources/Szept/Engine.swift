import Foundation
import SwiftData
import SwiftUI
import UserNotifications
import LibSignalClient

/// Serce apki: rejestracja, wysyłka/odbiór, WebSocket, znikające wiadomości.
@MainActor
@Observable
final class Engine {
    enum Connection { case offline, connecting, online }

    let container: ModelContainer
    let store: SignalStore
    let proto: SzeptProtocol
    var api: SzeptAPI
    var isRegistered: Bool
    var connection: Connection = .offline
    var lastError: String?
    var accountId: String? { proto.accountId }

    private var socket: URLSessionWebSocketTask?
    private var socketTask: Task<Void, Never>?
    private var expiryTimer: Timer?
    private var started = false
    var context: ModelContext { container.mainContext }

    static let shared: Engine = {
        if CommandLine.arguments.contains("-demo") { Demo.prepare() }
        do {
            let e = try Engine(container: try Storage.makeContainer(inMemory: CommandLine.arguments.contains("-demo")))
            if CommandLine.arguments.contains("-demo") { Demo.seed(e) }
            return e
        } catch { fatalError("Szept: nie można otworzyć bazy: \(error)") }
    }()

    let scope: String   // prefiks wpisów w Keychain (testy tworzą kilka silników w jednym procesie)

    init(container: ModelContainer, storeRoot: URL = Storage.keys, scope: String = "") throws {
        self.container = container
        self.scope = scope
        store = try SignalStore(root: storeRoot)
        let accountId = Keychain.string(scope + "account_id")
        proto = SzeptProtocol(store: store, accountId: accountId)
        let url = URL(string: UserDefaults.standard.string(forKey: "server") ?? Config.defaultServer) ?? URL(string: Config.defaultServer)!
        let token = Keychain.string(scope + "token")
        api = SzeptAPI(baseURL: url, token: token)
        isRegistered = accountId != nil && token != nil
    }

    var displayName: String {
        get { UserDefaults.standard.string(forKey: "display_name") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "display_name") }
    }

    var serverURL: String {
        get { api.baseURL.absoluteString }
        set {
            guard let u = URL(string: newValue), u.scheme != nil else { return }
            UserDefaults.standard.set(newValue, forKey: "server"); api.baseURL = u
        }
    }

    var myLink: ContactLink? {
        guard let id = accountId else { return nil }
        return ContactLink(accountId: id, identityKey: proto.identityKey.serialize(), name: displayName.isEmpty ? nil : displayName)
    }

    // MARK: rejestracja

    func register() async throws {
        let payload = try proto.makeRegistration()
        let r = try await api.register(payload)
        Keychain.set(r.token, account: scope + "token")
        Keychain.set(r.account_id, account: scope + "account_id")
        api.token = r.token
        proto.setAccountId(r.account_id)
        isRegistered = true
        start()
    }

    /// Usuwa konto na serwerze i wszystko lokalnie.
    func deleteEverything() async {
        stop()
        try? await api.deleteAccount()
        Keychain.wipe()
        UserDefaults.standard.removeObject(forKey: "display_name")
        try? context.delete(model: Message.self)
        try? context.delete(model: Contact.self)
        try? context.save()
        store.destroy()
        Storage.wipeAll()
        isRegistered = false
        exit(0) // najprościej i najbezpieczniej: zimny start bez starych uchwytów
    }

    // MARK: cykl życia

    func start() {
        guard isRegistered, !started else { return }
        started = true
        connect()
        expiryTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.purgeExpired() }
        }
        purgeExpired()
        Task { await topUpKeysIfNeeded() }
    }

    func stop() {
        started = false
        socketTask?.cancel(); socketTask = nil
        socket?.cancel(with: .goingAway, reason: nil); socket = nil
        expiryTimer?.invalidate(); expiryTimer = nil
        connection = .offline
    }

    func connect() {
        socketTask?.cancel()
        socketTask = Task { [weak self] in
            var backoff: Double = 1
            while let self, !Task.isCancelled {
                connection = .connecting
                do {
                    try await drainPending()
                    let ws = try api.webSocket()
                    socket = ws
                    ws.resume()
                    connection = .online
                    backoff = 1
                    while !Task.isCancelled {
                        let msg = try await ws.receive()
                        guard case .string(let text) = msg, let d = text.data(using: .utf8),
                              let p = try? JSONDecoder().decode(SocketMessage.self, from: d), p.type == "message",
                              let id = p.id, let content = p.content, let bytes = Data(base64Encoded: content) else { continue }
                        await handleIncoming(bytes)
                        try? await ws.send(.string(#"{"type":"ack","id":\#(id)}"#))
                    }
                } catch {
                    if Task.isCancelled { break }
                    connection = .offline
                    try? await Task.sleep(for: .seconds(backoff))
                    backoff = min(backoff * 2, 30)
                }
            }
        }
    }

    private struct SocketMessage: Decodable { var type: String; var id: Int?; var content: String? }

    /// Odbiera zaległe koperty przez REST (np. po starcie) i potwierdza każdą po zapisaniu.
    func drainPending() async throws {
        for m in try await api.pending() {
            if let bytes = Data(base64Encoded: m.content) { await handleIncoming(bytes) }
            try? await api.ack(m.id)
        }
    }

    func topUpKeysIfNeeded() async {
        guard isRegistered else { return }
        do {
            let (n, k) = try await api.keyCounts()
            if min(n, k) < Config.preKeyLowWater {
                _ = try await api.uploadKeys(try proto.makeTopUp())
            }
        } catch { lastError = error.localizedDescription }
    }

    // MARK: kontakty

    @discardableResult
    func addContact(_ link: ContactLink, name: String?) throws -> Contact {
        if let existing = contact(id: link.accountId) {
            if existing.identityKey != link.identityKey { throw SzeptError.identityMismatch }
            if let name, !name.isEmpty { existing.name = name }
            try context.save()
            return existing
        }
        let c = Contact(id: link.accountId, name: (name?.isEmpty == false ? name : link.name) ?? "Kontakt \(link.accountId.prefix(6))",
                        identityKey: link.identityKey)
        context.insert(c)
        try store.pinIdentity(try IdentityKey(bytes: link.identityKey), for: try SzeptProtocol.address(link.accountId))
        try context.save()
        return c
    }

    func contact(id: String) -> Contact? {
        var d = FetchDescriptor<Contact>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    func acceptNewIdentity(_ c: Contact, key: Data) {
        store.forgetIdentityAndSession(for: (try? SzeptProtocol.address(c.id)) ?? ProtocolAddressDummy.value)
        c.identityKey = key; c.identityChanged = false; c.verified = false
        try? store.pinIdentity(try IdentityKey(bytes: key), for: try SzeptProtocol.address(c.id))
        try? context.save()
    }

    func delete(_ c: Contact) {
        for m in c.messages { removeMedia(m) }
        store.forgetIdentityAndSession(for: (try? SzeptProtocol.address(c.id)) ?? ProtocolAddressDummy.value)
        context.delete(c)
        try? context.save()
    }

    // MARK: wysyłanie

    private func ensureSession(_ c: Contact) async throws {
        guard !proto.hasSession(with: c.id) else { return }
        let b = try await api.bundle(for: c.id)
        try proto.processBundle(b, pinnedIdentity: c.identityKey)
    }

    private func deliver(_ content: MessageContent, to c: Contact) async throws {
        try await ensureSession(c)
        var content = content
        if !displayName.isEmpty { content.name = displayName }
        let sealed = try proto.seal(try JSONEncoder().encode(content), to: c.id, recipientIdentity: c.identityKey)
        try await api.deliver([sealed], to: c.id)
    }

    func sendText(_ text: String, to c: Contact) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let m = Message(contact: c, text: trimmed, isMine: true)
        if c.disappearAfter > 0 { m.expiresAt = Date().addingTimeInterval(TimeInterval(c.disappearAfter)) }
        c.lastActivity = Date()
        context.insert(m); try? context.save()
        await send(message: m, content: MessageContent(id: m.id, ts: m.sentAt.timeIntervalSince1970, kind: .text, text: trimmed,
                                                 expire: c.disappearAfter > 0 ? c.disappearAfter : nil), to: c)
    }

    func sendImage(_ image: UIImage, to c: Contact) async {
        guard let (jpeg, w, h) = ImageTools.prepare(image) else { return }
        let m = Message(contact: c, text: "", isMine: true)
        m.attachmentW = w; m.attachmentH = h
        m.attachmentFile = saveMedia(jpeg)
        if c.disappearAfter > 0 { m.expiresAt = Date().addingTimeInterval(TimeInterval(c.disappearAfter)) }
        c.lastActivity = Date()
        context.insert(m); try? context.save()
        do {
            let (ct, key) = try SzeptProtocol.encryptAttachment(jpeg)
            let id = try await api.uploadAttachment(ct)
            let att = MessageContent.Attachment(id: id, key: key.key, nonce: key.nonce, size: ct.count, w: w, h: h)
            await send(message: m, content: MessageContent(id: m.id, ts: m.sentAt.timeIntervalSince1970, kind: .image, att: att,
                                                     expire: c.disappearAfter > 0 ? c.disappearAfter : nil), to: c)
        } catch {
            m.status = .failed; lastError = error.localizedDescription; try? context.save()
        }
    }

    func retry(_ m: Message) async {
        guard let c = m.contact else { return }
        m.status = .pending; try? context.save()
        if let f = m.attachmentFile, let data = try? Data(contentsOf: Storage.media.appendingPathComponent(f)), let img = UIImage(data: data) {
            context.delete(m); try? context.save()
            await sendImage(img, to: c)
        } else {
            await send(message: m, content: MessageContent(id: m.id, ts: m.sentAt.timeIntervalSince1970, kind: .text, text: m.text,
                                                     expire: c.disappearAfter > 0 ? c.disappearAfter : nil), to: c)
        }
    }

    private func send(message m: Message, content: MessageContent, to c: Contact) async {
        do {
            try await deliver(content, to: c)
            m.status = .sent
        } catch {
            m.status = .failed
            lastError = error.localizedDescription
        }
        try? context.save()
    }

    func setDisappear(_ seconds: Int, for c: Contact) async {
        c.disappearAfter = seconds
        let note = Message(contact: c, text: seconds > 0 ? "Znikające wiadomości: \(Durations.label(seconds))" : "Znikające wiadomości wyłączone", isMine: true)
        note.status = .sent
        context.insert(note); try? context.save()
        try? await deliver(MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .timer, expire: seconds), to: c)
    }

    private func sendReceipt(for id: String, to c: Contact) async {
        try? await deliver(MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .receipt, ref: id), to: c)
    }

    // MARK: odbiór

    func handleIncoming(_ sealed: Data) async {
        let opened: SzeptProtocol.Opened
        do { opened = try proto.open(sealed) } catch {
            if case SignalError.untrustedIdentity = error, let c = unknownSenderContact(sealed) { c.identityChanged = true; try? context.save() }
            lastError = "Nie udało się odszyfrować wiadomości: \(error.localizedDescription)"
            return
        }
        guard let content = try? JSONDecoder().decode(MessageContent.self, from: opened.plaintext) else { return }
        let identity = opened.senderIdentity.serialize()
        let c: Contact
        if let known = contact(id: opened.sender) {
            c = known
            if known.identityKey != identity { known.identityKey = identity; known.identityChanged = true; known.verified = false }
        } else {
            c = Contact(id: opened.sender, name: content.name ?? "Nieznany \(opened.sender.prefix(6))", identityKey: identity)
            c.fromUnknown = true
            context.insert(c)
        }
        if let n = content.name, !n.isEmpty, c.fromUnknown, c.name.hasPrefix("Nieznany") { c.name = n }

        switch content.kind {
        case .text:
            guard contact(id: c.id)?.messages.contains(where: { $0.id == content.id }) != true else { break }
            let m = Message(id: content.id, contact: c, text: content.text ?? "", isMine: false, sentAt: Date(timeIntervalSince1970: content.ts))
            m.status = .delivered
            applyExpiry(m, content.expire, contact: c)
            context.insert(m)
            c.unread += 1; c.lastActivity = Date()
            notifyLocally()
            Task { await sendReceipt(for: content.id, to: c) }
        case .image:
            guard let att = content.att else { break }
            let m = Message(id: content.id, contact: c, text: "", isMine: false, sentAt: Date(timeIntervalSince1970: content.ts))
            m.status = .delivered; m.attachmentW = att.w; m.attachmentH = att.h
            applyExpiry(m, content.expire, contact: c)
            context.insert(m)
            c.unread += 1; c.lastActivity = Date()
            notifyLocally()
            Task {
                if let ct = try? await api.downloadAttachment(att.id),
                   let plain = try? SzeptProtocol.decryptAttachment(ct, key: .init(key: att.key, nonce: att.nonce)) {
                    m.attachmentFile = saveMedia(plain)
                } else { m.text = "⚠️ Nie udało się pobrać zdjęcia" }
                try? context.save()
                await sendReceipt(for: content.id, to: c)
            }
        case .receipt:
            if let ref = content.ref, let m = c.messages.first(where: { $0.id == ref }) { m.status = .delivered }
        case .timer:
            c.disappearAfter = content.expire ?? 0
            let note = Message(contact: c, text: (content.expire ?? 0) > 0 ? "\(c.name): znikające wiadomości \(Durations.label(content.expire!))" : "\(c.name) wyłączył(a) znikające wiadomości", isMine: false)
            note.status = .delivered
            context.insert(note)
        }
        try? context.save()
    }

    private func unknownSenderContact(_ sealed: Data) -> Contact? { nil }

    private func applyExpiry(_ m: Message, _ expire: Int?, contact: Contact) {
        if let e = expire, e > 0 { m.expiresAt = Date().addingTimeInterval(TimeInterval(e)) }
    }

    func markRead(_ c: Contact) { if c.unread != 0 { c.unread = 0; try? context.save() } }

    func purgeExpired() {
        let now = Date()
        let d = FetchDescriptor<Message>(predicate: #Predicate { $0.expiresAt != nil && $0.expiresAt! < now })
        guard let expired = try? context.fetch(d), !expired.isEmpty else { return }
        for m in expired { removeMedia(m); context.delete(m) }
        try? context.save()
    }

    // MARK: media / powiadomienia

    private func saveMedia(_ data: Data) -> String? {
        let name = UUID().uuidString + ".jpg"
        return (try? SignalStore.write(data, to: Storage.media.appendingPathComponent(name))) != nil ? name : nil
    }

    private func removeMedia(_ m: Message) {
        if let f = m.attachmentFile { try? FileManager.default.removeItem(at: Storage.media.appendingPathComponent(f)) }
    }

    /// Powiadomienie lokalne bez treści i bez nadawcy (gdy apka w tle, a gniazdo jeszcze żyje).
    private func notifyLocally() {
        guard UIApplication.shared.applicationState != .active else { return }
        let content = UNMutableNotificationContent()
        content.title = "Szept"
        content.body = "Nowa wiadomość"
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    static func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }
}

enum ProtocolAddressDummy { static let value = try! ProtocolAddress(name: "-", deviceId: 1) }

enum Durations {
    static let options: [(Int, String)] = [(0, "Wyłączone"), (30, "30 sekund"), (300, "5 minut"), (3600, "1 godzina"),
                                           (86400, "1 dzień"), (604800, "1 tydzień")]
    static func label(_ s: Int) -> String { options.first { $0.0 == s }?.1 ?? "\(s) s" }
}

enum ImageTools {
    static func prepare(_ image: UIImage) -> (Data, Int, Int)? {
        let side = max(image.size.width, image.size.height)
        let scale = min(1, Config.maxImageSide / side)
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1
        let out = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let jpeg = out.jpegData(compressionQuality: 0.8) else { return nil }   // bez EXIF/GPS
        return (jpeg, Int(size.width), Int(size.height))
    }
}
