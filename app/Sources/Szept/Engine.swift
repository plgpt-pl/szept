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
        UserDefaults.standard.removeObject(forKey: "push_token")
        try? context.delete(model: Message.self)
        try? context.delete(model: Contact.self)
        try? context.delete(model: ChatGroup.self)
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
        let c = Contact(id: link.accountId, name: (name?.isEmpty == false ? name : link.name) ?? String(localized: "Kontakt \(String(link.accountId.prefix(6)))"),
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

    func deliver(_ content: MessageContent, to c: Contact) async throws {
        try await ensureSession(c)
        var content = content
        if !displayName.isEmpty { content.name = displayName }
        let sealed = try proto.seal(try JSONEncoder().encode(content), to: c.id, recipientIdentity: c.identityKey)
        try await api.deliver([sealed], to: c.id)
    }

    // MARK: wysyłanie (wspólne dla 1:1 i grup – docs/WERSJA-0.3.md)

    func target(of m: Message) -> ChatTarget? {
        if let g = m.group { return .group(g) }
        if let c = m.contact { return .contact(c) }
        return nil
    }

    private func touch(_ t: ChatTarget) {
        switch t { case .contact(let c): c.lastActivity = Date(); case .group(let g): g.lastActivity = Date() }
    }

    private func newMessage(for t: ChatTarget, text: String, replyTo: String?) -> Message {
        let m: Message
        switch t {
        case .contact(let c): m = Message(contact: c, text: text, isMine: true)
        case .group(let g): m = Message(group: g, text: text, isMine: true)
        }
        m.replyToId = replyTo
        if t.disappearAfter > 0 { m.expiresAt = Date().addingTimeInterval(TimeInterval(t.disappearAfter)) }
        touch(t)
        context.insert(m)
        return m
    }

    private func baseContent(for m: Message, kind: MessageContent.Kind, in t: ChatTarget) -> MessageContent {
        var c = MessageContent(id: m.id, ts: m.sentAt.timeIntervalSince1970, kind: kind, expire: t.disappearAfter > 0 ? t.disappearAfter : nil)
        if case .group(let g) = t { c.g = g.id }
        c.r = m.replyToId
        return c
    }

    /// Dostarcza treść do celu: do kontaktu albo do każdego członka grupy.
    func deliver(_ content: MessageContent, to t: ChatTarget) async throws {
        switch t {
        case .contact(let c): try await deliver(content, to: c)
        case .group(let g): try await deliverGroup(content, to: g)
        }
    }

    private func send(message m: Message, content: MessageContent, to t: ChatTarget) async {
        do { try await deliver(content, to: t); m.status = .sent }
        catch { m.status = .failed; lastError = error.localizedDescription }
        try? context.save()
    }

    func sendText(_ text: String, to t: ChatTarget, replyTo: String? = nil) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let m = newMessage(for: t, text: trimmed, replyTo: replyTo); try? context.save()
        var c = baseContent(for: m, kind: .text, in: t); c.text = trimmed
        await send(message: m, content: c, to: t)
    }

    func sendImage(_ image: UIImage, to t: ChatTarget, replyTo: String? = nil) async {
        guard let (jpeg, w, h) = ImageTools.prepare(image) else { return }
        let m = newMessage(for: t, text: "", replyTo: replyTo)
        m.kind = .image; m.attachmentW = w; m.attachmentH = h; m.attachmentFile = saveMediaFile(jpeg, ext: "jpg")
        try? context.save()
        await uploadAndSend(m, data: jpeg, to: t)
    }

    /// Dowolny plik (≤ Config.maxFileBytes). `url` może pochodzić z fileImporter (zasób z ograniczonym dostępem).
    func sendFile(_ url: URL, to t: ChatTarget, replyTo: String? = nil) async {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { lastError = String(localized: "Nie udało się odczytać pliku"); return }
        guard data.count <= Config.maxFileBytes else { lastError = String(localized: "Plik jest za duży (limit \(Config.maxFileBytes / 1_048_576) MB)"); return }
        let m = newMessage(for: t, text: "", replyTo: replyTo)
        m.kind = .file; m.fileName = url.lastPathComponent; m.mimeType = MimeTypes.mime(for: url.pathExtension)
        m.attachmentFile = saveMediaFile(data, ext: url.pathExtension)
        try? context.save()
        await uploadAndSend(m, data: data, to: t)
    }

    /// Wiadomość głosowa: plik m4a (AAC) nagrany w apce.
    func sendAudio(_ url: URL, duration: Double, to t: ChatTarget, replyTo: String? = nil) async {
        guard let data = try? Data(contentsOf: url), !data.isEmpty else { return }
        let m = newMessage(for: t, text: "", replyTo: replyTo)
        m.kind = .audio; m.duration = duration; m.mimeType = "audio/mp4"; m.attachmentFile = saveMediaFile(data, ext: "m4a")
        try? context.save()
        await uploadAndSend(m, data: data, to: t)
    }

    /// Szyfruje, wgrywa i wysyła treść z załącznikiem (image/file/audio); używane też przy ponawianiu.
    private func uploadAndSend(_ m: Message, data: Data, to t: ChatTarget) async {
        do {
            let (ct, key) = try SzeptProtocol.encryptAttachment(data)
            let id = try await api.uploadAttachment(ct)
            var c = baseContent(for: m, kind: m.kind, in: t)
            c.att = .init(id: id, key: key.key, nonce: key.nonce, size: ct.count, w: m.attachmentW, h: m.attachmentH)
            if m.kind == .file { c.fn = m.fileName; c.mime = m.mimeType }
            if m.kind == .audio { c.dur = m.duration; c.mime = "audio/mp4" }
            await send(message: m, content: c, to: t)
        } catch {
            m.status = .failed; lastError = error.localizedDescription; try? context.save()
        }
    }

    func retry(_ m: Message) async {
        guard let t = target(of: m) else { return }
        m.status = .pending; try? context.save()
        if m.hasAttachment, let f = m.attachmentFile, let data = try? Data(contentsOf: Storage.media.appendingPathComponent(f)) {
            if m.kind == .text { m.kind = .image }   // zdjęcie zapisane przed 0.3
            await uploadAndSend(m, data: data, to: t)
        } else {
            var c = baseContent(for: m, kind: .text, in: t); c.text = m.text
            await send(message: m, content: c, to: t)
        }
    }

    // zgodność ze starszymi wywołaniami
    func sendText(_ text: String, to c: Contact) async { await sendText(text, to: .contact(c)) }
    func sendImage(_ image: UIImage, to c: Contact) async { await sendImage(image, to: .contact(c)) }
    func sendText(_ text: String, to g: ChatGroup) async { await sendText(text, to: .group(g)) }
    func sendImage(_ image: UIImage, to g: ChatGroup) async { await sendImage(image, to: .group(g)) }

    // MARK: reakcje, edycja, usuwanie (docs/WERSJA-0.3.md)

    private func control(_ kind: MessageContent.Kind, ref: String, text: String? = nil, op: String? = nil, in t: ChatTarget) -> MessageContent {
        var c = MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: kind, text: text, ref: ref, op: op)
        if case .group(let g) = t { c.g = g.id }
        return c
    }

    /// Ustawia (emoji) albo zdejmuje (nil) moją reakcję na wiadomość i rozsyła ją.
    func react(_ emoji: String?, to m: Message) async {
        guard let t = target(of: m), let me = accountId, !m.wasDeleted else { return }
        var r = m.reactions
        if let emoji, !emoji.isEmpty { r[me] = String(emoji.prefix(8)) } else { r.removeValue(forKey: me) }
        m.reactions = r; try? context.save()
        try? await deliver(control(.reaction, ref: m.id, text: emoji, op: emoji == nil ? "remove" : "set", in: t), to: t)
    }

    func myReaction(on m: Message) -> String? { accountId.flatMap { m.reactions[$0] } }

    /// Edycja własnej wiadomości tekstowej.
    func edit(_ m: Message, text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard m.isMine, m.kind == .text, !m.wasDeleted, !trimmed.isEmpty, trimmed != m.text, let t = target(of: m) else { return }
        m.text = trimmed; m.editedAt = Date(); try? context.save()
        do { try await deliver(control(.edit, ref: m.id, text: trimmed, in: t), to: t) } catch { lastError = error.localizedDescription }
    }

    /// Usunięcie własnej wiadomości u wszystkich: lokalnie zostaje placeholder, odbiorcy robią to samo.
    func deleteForEveryone(_ m: Message) async {
        guard m.isMine, let t = target(of: m) else { return }
        markDeleted(m); try? context.save()
        try? await deliver(control(.delete, ref: m.id, in: t), to: t)
    }

    func deleteLocally(_ m: Message) { removeMedia(m); context.delete(m); try? context.save() }

    private func markDeleted(_ m: Message) {
        removeMedia(m)
        m.attachmentFile = nil; m.attachmentW = 0; m.attachmentH = 0; m.fileName = nil; m.mimeType = nil; m.duration = 0
        m.text = ""; m.kind = .text; m.reactions = [:]; m.replyToId = nil; m.wasDeleted = true
    }

    func messageBy(id: String, in t: ChatTarget) -> Message? { t.messages.first { $0.id == id } }

    /// Kopia załącznika pod oryginalną nazwą (do podglądu/udostępnienia); w katalogu tymczasowym.
    func exportURL(for m: Message) -> URL? {
        guard let f = m.attachmentFile else { return nil }
        let src = Storage.media.appendingPathComponent(f)
        let name = m.kind == .audio ? "glosowka-\(m.id.prefix(6)).m4a" : (m.fileName ?? f)
        let dst = FileManager.default.temporaryDirectory.appendingPathComponent("szept-export", isDirectory: true).appendingPathComponent(name)
        try? FileManager.default.createDirectory(at: dst.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: dst)
        return (try? FileManager.default.copyItem(at: src, to: dst)) != nil ? dst : nil
    }

    func setDisappear(_ seconds: Int, for c: Contact) async {
        c.disappearAfter = seconds
        let note = Message(contact: c, text: seconds > 0 ? String(localized: "Znikające wiadomości: \(Durations.label(seconds))") : String(localized: "Znikające wiadomości wyłączone"), isMine: true)
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
            lastError = String(localized: "Nie udało się odszyfrować wiadomości: \(error.localizedDescription)")
            return
        }
        guard let content = try? JSONDecoder().decode(MessageContent.self, from: opened.plaintext) else { return }
        let identity = opened.senderIdentity.serialize()
        let c: Contact
        if let known = contact(id: opened.sender) {
            c = known
            if known.identityKey != identity { known.identityKey = identity; known.identityChanged = true; known.verified = false }
        } else {
            c = Contact(id: opened.sender, name: content.name ?? String(localized: "Nieznany \(String(opened.sender.prefix(6)))"), identityKey: identity)
            c.fromUnknown = true
            context.insert(c)
        }
        if let n = content.name, !n.isEmpty, c.fromUnknown, c.name.hasPrefix(String(localized: "Nieznany")) || c.name.hasPrefix("Nieznany") { c.name = n }

        if let gid = content.g {                      // wiadomość grupowa – nie dotyka rozmowy 1:1 z nadawcą
            handleGroupContent(content, groupId: gid, sender: c)
            try? context.save()
            return
        }

        let t = ChatTarget.contact(c)
        if handleCommon(content, from: c, in: t) { try? context.save(); return }
        switch content.kind {
        case .text:
            guard !c.messages.contains(where: { $0.id == content.id }) else { break }
            let m = newIncoming(content, from: c, in: t)
            context.insert(m); bump(t); notifyLocally()
            Task { await sendReceipt(for: content.id, to: c) }
        case .image:
            guard let att = content.att, !c.messages.contains(where: { $0.id == content.id }) else { break }
            let m = newIncoming(content, from: c, in: t)
            m.kind = .image; m.attachmentW = att.w; m.attachmentH = att.h
            context.insert(m); bump(t); notifyLocally()
            download(att, into: m, ext: "jpg", receiptFor: content.id, to: c)
        case .receipt:
            if let ref = content.ref, let m = c.messages.first(where: { $0.id == ref }) { m.status = .delivered }
        case .timer:
            c.disappearAfter = content.expire ?? 0
            let note = Message(contact: c, text: (content.expire ?? 0) > 0 ? String(localized: "\(c.name): znikające wiadomości \(Durations.label(content.expire!))") : String(localized: "\(c.name) wyłączył(a) znikające wiadomości"), isMine: false)
            note.status = .delivered
            context.insert(note)
        default:
            break   // sterowanie grupą bez id grupy / nieznany rodzaj – ignorujemy
        }
        try? context.save()
    }

    // MARK: odbiór – części wspólne 1:1 i grup

    private func newIncoming(_ content: MessageContent, from c: Contact, in t: ChatTarget) -> Message {
        let m: Message
        switch t {
        case .contact(let cc): m = Message(id: content.id, contact: cc, text: content.text ?? "", isMine: false, sentAt: Date(timeIntervalSince1970: content.ts))
        case .group(let g): m = Message(id: content.id, group: g, text: content.text ?? "", isMine: false, sentAt: Date(timeIntervalSince1970: content.ts)); m.senderId = c.id
        }
        m.status = .delivered; m.replyToId = content.r
        if let e = content.expire, e > 0 { m.expiresAt = Date().addingTimeInterval(TimeInterval(e)) }
        return m
    }

    private func bump(_ t: ChatTarget) {
        switch t { case .contact(let c): c.unread += 1; c.lastActivity = Date(); case .group(let g): g.unread += 1; g.lastActivity = Date() }
    }

    /// Pobiera i odszyfrowuje załącznik w tle; w 1:1 potem potwierdza odbiór.
    private func download(_ att: MessageContent.Attachment, into m: Message, ext: String, receiptFor id: String, to c: Contact?) {
        Task {
            if let ct = try? await api.downloadAttachment(att.id),
               let plain = try? SzeptProtocol.decryptAttachment(ct, key: .init(key: att.key, nonce: att.nonce)) {
                m.attachmentFile = saveMediaFile(plain, ext: ext)
            } else { m.text = String(localized: "⚠️ Nie udało się pobrać załącznika") }
            try? context.save()
            if let c { await sendReceipt(for: id, to: c) }
        }
    }

    /// Czy wiadomość `m` w rozmowie `t` jest wiadomością nadawcy `c` (warunek dla edit/delete).
    private func ownedBySender(_ m: Message, _ c: Contact, _ t: ChatTarget) -> Bool {
        t.isGroup ? (m.senderId == c.id && !m.isMine) : (!m.isMine && m.contact?.id == c.id)
    }

    /// Rodzaje z 0.3 wspólne dla 1:1 i grup. Zwraca true, gdy rodzaj został obsłużony (także gdy odrzucony).
    private func handleCommon(_ content: MessageContent, from c: Contact, in t: ChatTarget) -> Bool {
        switch content.kind {
        case .file, .audio:
            guard let att = content.att, !t.messages.contains(where: { $0.id == content.id }) else { return true }
            let m = newIncoming(content, from: c, in: t)
            m.kind = content.kind; m.fileName = content.fn.map { String($0.prefix(120)) }; m.mimeType = content.mime; m.duration = content.dur ?? 0
            context.insert(m); bump(t); notifyLocally()
            let ext = content.kind == .audio ? "m4a" : ((content.fn as NSString?)?.pathExtension ?? "bin")
            download(att, into: m, ext: ext, receiptFor: content.id, to: t.isGroup ? nil : c)
            return true
        case .reaction:
            guard let ref = content.ref, let m = messageBy(id: ref, in: t), !m.wasDeleted else { return true }
            var r = m.reactions
            if content.op == "remove" { r.removeValue(forKey: c.id) }
            else if let e = content.text, !e.isEmpty, e.count <= 8 { r[c.id] = e }
            m.reactions = r
            return true
        case .edit:
            guard let ref = content.ref, let m = messageBy(id: ref, in: t), ownedBySender(m, c, t), m.kind == .text, !m.wasDeleted,
                  let text = content.text?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return true }
            m.text = text; m.editedAt = Date(timeIntervalSince1970: content.ts)
            return true
        case .delete:
            guard let ref = content.ref, let m = messageBy(id: ref, in: t), ownedBySender(m, c, t) else { return true }
            markDeleted(m)
            return true
        case .unknown:
            return true
        default:
            return false
        }
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

    func saveMediaFile(_ data: Data, ext: String = "jpg") -> String? {
        let clean = ext.lowercased().filter { $0.isLetter || $0.isNumber }.prefix(8)
        let name = UUID().uuidString + "." + (clean.isEmpty ? "bin" : String(clean))
        return (try? SignalStore.write(data, to: Storage.media.appendingPathComponent(name))) != nil ? name : nil
    }

    func removeMedia(_ m: Message) {
        if let f = m.attachmentFile { try? FileManager.default.removeItem(at: Storage.media.appendingPathComponent(f)) }
    }

    /// Powiadomienie lokalne bez treści i bez nadawcy (gdy apka w tle, a gniazdo jeszcze żyje).
    private func notifyLocally() {
        guard UIApplication.shared.applicationState != .active else { return }
        let content = UNMutableNotificationContent()
        content.title = "Szept"
        content.body = String(localized: "Nowa wiadomość")
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    static func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            if granted { Push.registerIfAllowed() }
        }
    }

    // MARK: push (APNs) – docs/WERSJA-0.3.md §3

    /// Token urządzenia z APNs → serwer (tylko gdy się zmienił). Serwer wyśle generyczne „Nowa wiadomość” bez treści i nadawcy.
    func registerPush(token: Data) async {
        guard isRegistered else { return }
        let hex = token.map { String(format: "%02x", $0) }.joined()
        let env = Push.environment
        let stamp = hex + "|" + env + "|" + api.baseURL.absoluteString
        guard UserDefaults.standard.string(forKey: "push_token") != stamp else { return }
        do { try await api.setPush(token: hex, env: env); UserDefaults.standard.set(stamp, forKey: "push_token") }
        catch { lastError = error.localizedDescription }
    }

    /// Odbiór zaległych kopert (po pushu / z tła); błędy połączenia ignorowane.
    func sync() async { try? await drainPending() }
}

enum ProtocolAddressDummy { static let value = try! ProtocolAddress(name: "-", deviceId: 1) }

enum Durations {
    static var options: [(Int, String)] { [(0, String(localized: "Wyłączone")), (30, String(localized: "30 sekund")), (300, String(localized: "5 minut")),
                                           (3600, String(localized: "1 godzina")), (86400, String(localized: "1 dzień")), (604800, String(localized: "1 tydzień"))] }
    static func label(_ s: Int) -> String { options.first { $0.0 == s }?.1 ?? "\(s) s" }
    /// 0:07, 1:30
    static func clock(_ seconds: Double) -> String { let t = Int(seconds.rounded()); return "\(t / 60):" + String(format: "%02d", t % 60) }
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


// MARK: - Grupy (rozsyłanie pairwise; docs/GRUPY.md)

/// Cel rozmowy: kontakt 1:1 albo grupa. Pozwala jednemu ekranowi czatu obsłużyć oba.
enum ChatTarget: Identifiable, Hashable {
    case contact(Contact), group(ChatGroup)
    var id: String { switch self { case .contact(let c): return c.id; case .group(let g): return "g:" + g.id } }
    var name: String { switch self { case .contact(let c): return c.name; case .group(let g): return g.name } }
    var disappearAfter: Int { switch self { case .contact(let c): return c.disappearAfter; case .group(let g): return g.disappearAfter } }
    var unread: Int { switch self { case .contact(let c): return c.unread; case .group(let g): return g.unread } }
    var messages: [Message] { switch self { case .contact(let c): return c.messages; case .group(let g): return g.messages } }
    var isGroup: Bool { if case .group = self { return true } else { return false } }
}

extension Engine {
    func group(id: String) -> ChatGroup? {
        var d = FetchDescriptor<ChatGroup>(predicate: #Predicate { $0.id == id })
        d.fetchLimit = 1
        return try? context.fetch(d).first
    }

    /// Kontakty członków grupy (pomija id bez kontaktu – nie powinno się zdarzyć, bo `set` zakłada kontakty).
    func memberContacts(_ g: ChatGroup) -> [Contact] { g.members.compactMap { contact(id: $0) } }

    func senderName(_ id: String?) -> String {
        guard let id else { return "" }
        if id == accountId { return String(localized: "Ty") }
        return contact(id: id)?.name ?? String(localized: "Nieznany \(String(id.prefix(6)))")
    }

    private func groupNote(_ g: ChatGroup, _ text: String, mine: Bool) {
        let note = Message(group: g, text: text, isMine: mine)
        note.status = mine ? .sent : .delivered
        context.insert(note)
    }

    /// Wysyła treść do każdego członka osobno; rzuca, gdy choć jedna wysyłka padła (nadawca widzi „ponów”).
    private func deliverGroup(_ content: MessageContent, to g: ChatGroup) async throws {
        var failures: [String] = []
        for c in memberContacts(g) {
            do { try await deliver(content, to: c) } catch { failures.append("\(c.name): \(error.localizedDescription)") }
        }
        if !failures.isEmpty { throw SzeptError.server(String(localized: "nie dostarczono do: \(failures.joined(separator: "; "))")) }
    }

    private func groupStateContent(_ g: ChatGroup) -> MessageContent {
        var members = memberContacts(g).map { MessageContent.Member(i: $0.id, k: $0.identityKey.base64EncodedString(), n: $0.name) }
        if let me = accountId {
            members.append(MessageContent.Member(i: me, k: proto.identityKeyBase64, n: displayName.isEmpty ? nil : displayName))
        }
        return MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .group, g: g.id, gname: g.name, members: members, op: "set")
    }

    /// Rozsyła pełny stan grupy (nazwa + członkowie) do wszystkich członków.
    func sendGroupState(_ g: ChatGroup) async {
        do { try await deliverGroup(groupStateContent(g), to: g) } catch { lastError = error.localizedDescription }
    }

    @discardableResult
    func createGroup(name: String, members: [Contact]) async -> ChatGroup {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let g = ChatGroup(id: UUID().uuidString.uppercased(), name: trimmed.isEmpty ? String(localized: "Grupa") : trimmed, members: members.map(\.id))
        context.insert(g)
        groupNote(g, String(localized: "Utworzyłeś(aś) grupę z: \(members.map(\.name).joined(separator: ", "))"), mine: true)
        try? context.save()
        await sendGroupState(g)
        return g
    }

    func renameGroup(_ g: ChatGroup, to name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed != g.name else { return }
        g.name = trimmed; g.lastActivity = Date()
        groupNote(g, String(localized: "Zmieniłeś(aś) nazwę grupy na „\(trimmed)”"), mine: true)
        try? context.save()
        await sendGroupState(g)
    }

    func addMembers(_ contacts: [Contact], to g: ChatGroup) async {
        let new = contacts.filter { !g.members.contains($0.id) }
        guard !new.isEmpty else { return }
        g.members.append(contentsOf: new.map(\.id)); g.lastActivity = Date()
        groupNote(g, String(localized: "Dodałeś(aś): \(new.map(\.name).joined(separator: ", "))"), mine: true)
        try? context.save()
        await sendGroupState(g)
    }

    /// Wychodzi z grupy: informuje członków i usuwa grupę lokalnie.
    func leaveGroup(_ g: ChatGroup) async {
        let content = MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .group, g: g.id, op: "leave")
        try? await deliverGroup(content, to: g)
        deleteGroupLocally(g)
    }

    func deleteGroupLocally(_ g: ChatGroup) {
        for m in g.messages { removeMedia(m) }
        context.delete(g)
        try? context.save()
    }

    func markRead(_ g: ChatGroup) { if g.unread != 0 { g.unread = 0; try? context.save() } }

    func markRead(_ t: ChatTarget) { switch t { case .contact(let c): markRead(c); case .group(let g): markRead(g) } }

    func setDisappear(_ seconds: Int, for t: ChatTarget) async {
        switch t { case .contact(let c): await setDisappear(seconds, for: c); case .group(let g): await setDisappear(seconds, for: g) }
    }

    func setDisappear(_ seconds: Int, for g: ChatGroup) async {
        g.disappearAfter = seconds
        groupNote(g, seconds > 0 ? String(localized: "Znikające wiadomości: \(Durations.label(seconds))") : String(localized: "Znikające wiadomości wyłączone"), mine: true)
        try? context.save()
        try? await deliverGroup(MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .timer, expire: seconds, g: g.id), to: g)
    }

    // MARK: odbiór wiadomości grupowych

    /// Zakłada kontakt dla członka z wiadomości `set` (TOFU: klucz z wiadomości). Istniejącego kontaktu i jego klucza nie rusza.
    private func ensureMemberContact(_ m: MessageContent.Member) -> Contact? {
        if let existing = contact(id: m.i) { return existing }
        guard let key = Data(base64Encoded: m.k), (try? IdentityKey(bytes: key)) != nil else { return nil }
        let c = Contact(id: m.i, name: (m.n?.isEmpty == false ? m.n! : String(localized: "Kontakt \(String(m.i.prefix(6)))")), identityKey: key)
        c.fromUnknown = true
        context.insert(c)
        try? store.pinIdentity(try IdentityKey(bytes: key), for: try SzeptProtocol.address(m.i))
        return c
    }

    fileprivate func handleGroupContent(_ content: MessageContent, groupId: String, sender c: Contact) {
        let existing = group(id: groupId)
        switch content.kind {
        case .group where content.op == "set":
            guard let list = content.members, list.contains(where: { $0.i == c.id }) else { return }
            let others = list.filter { $0.i != accountId }
            let ids = others.compactMap { ensureMemberContact($0)?.id }
            let name = (content.gname?.isEmpty == false) ? content.gname! : String(localized: "Grupa")
            if let g = existing {
                guard g.members.contains(c.id) else { return }        // nie-członek nie steruje grupą
                let added = Set(ids).subtracting(g.members), removed = Set(g.members).subtracting(ids)
                var notes: [String] = []
                if g.name != name { notes.append(String(localized: "zmienił(a) nazwę na „\(name)”")); g.name = name }
                if !added.isEmpty { notes.append(String(localized: "dodał(a): \(added.map { senderName($0) }.joined(separator: ", "))")) }
                if !removed.isEmpty { notes.append(String(localized: "usunął(ęła): \(removed.map { senderName($0) }.joined(separator: ", "))")) }
                g.members = ids
                if !notes.isEmpty { groupNote(g, "\(c.name) " + notes.joined(separator: "; "), mine: false); g.lastActivity = Date() }
            } else {
                let g = ChatGroup(id: groupId, name: name, members: ids)
                context.insert(g)
                groupNote(g, String(localized: "\(c.name) dodał(a) Cię do grupy"), mine: false)
                g.unread = 1
                notifyLocally()
            }
        case .group where content.op == "leave":
            guard let g = existing, g.members.contains(c.id) else { return }
            g.members.removeAll { $0 == c.id }
            groupNote(g, String(localized: "\(c.name) opuścił(a) grupę"), mine: false)
            g.lastActivity = Date()
        case .group:
            break
        case .text:
            guard let g = existing, g.members.contains(c.id), !g.messages.contains(where: { $0.id == content.id }) else { return }
            let m = newIncoming(content, from: c, in: .group(g))
            context.insert(m); bump(.group(g)); notifyLocally()
        case .image:
            guard let g = existing, g.members.contains(c.id), let att = content.att, !g.messages.contains(where: { $0.id == content.id }) else { return }
            let m = newIncoming(content, from: c, in: .group(g))
            m.kind = .image; m.attachmentW = att.w; m.attachmentH = att.h
            context.insert(m); bump(.group(g)); notifyLocally()
            download(att, into: m, ext: "jpg", receiptFor: content.id, to: nil)
        case .file, .audio, .reaction, .edit, .delete, .unknown:
            guard let g = existing, g.members.contains(c.id) else { return }
            _ = handleCommon(content, from: c, in: .group(g))
        case .timer:
            guard let g = existing, g.members.contains(c.id) else { return }
            g.disappearAfter = content.expire ?? 0
            groupNote(g, g.disappearAfter > 0 ? String(localized: "\(c.name): znikające wiadomości \(Durations.label(g.disappearAfter))") : String(localized: "\(c.name) wyłączył(a) znikające wiadomości"), mine: false)
        case .receipt:
            break   // dla grup nie ma potwierdzeń odbioru
        }
    }
}
