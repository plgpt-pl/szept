import Foundation
import SwiftData
import UIKit

/// Szept 0.4 (docs/WERSJA-0.4.md): „pisze…”, przeczytane, przypinanie, wyciszanie, wyszukiwanie, notatka, przekazywanie.
extension Engine {

    // MARK: ustawienia prywatności (wzajemne, domyślnie włączone)

    var readReceiptsEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "read_receipts") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "read_receipts") }
    }

    var typingIndicatorsEnabled: Bool {
        get { UserDefaults.standard.object(forKey: "typing_indicators") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "typing_indicators"); if !newValue { typing = [:] } }
    }

    // MARK: notatka dla siebie

    func isNoteToSelf(_ c: Contact) -> Bool { c.id == accountId }
    func isNoteToSelf(_ t: ChatTarget) -> Bool { if case .contact(let c) = t { return isNoteToSelf(c) } else { return false } }

    /// Zakłada rozmowę „Notatka dla siebie” (kontakt o id własnego konta), jeśli jej nie ma.
    func ensureNoteToSelf() {
        guard isRegistered, let me = accountId, contact(id: me) == nil else { return }
        let c = Contact(id: me, name: String(localized: "Notatka dla siebie"), identityKey: proto.identityKey.serialize())
        c.verified = true
        c.lastActivity = .distantPast
        context.insert(c); try? context.save()
    }

    // MARK: „pisze…” – odbiór

    func setTyping(_ on: Bool, sender: String, in t: ChatTarget) {
        guard typingIndicatorsEnabled else { return }
        if on {
            typing[t.id, default: [:]][sender] = Date().addingTimeInterval(7)
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(7.2))
                self?.pruneTyping()
            }
        } else {
            typing[t.id]?.removeValue(forKey: sender)
        }
    }

    private func pruneTyping() {
        let now = Date()
        for (k, v) in typing {
            let alive = v.filter { $0.value > now }
            if alive.count != v.count { typing[k] = alive.isEmpty ? nil : alive }
        }
    }

    /// Imiona osób piszących teraz w rozmowie (puste, gdy nikt).
    func typingNames(in t: ChatTarget) -> [String] {
        let now = Date()
        return (typing[t.id] ?? [:]).filter { $0.value > now }.keys.map { senderName($0) }.sorted()
    }

    func typingLabel(in t: ChatTarget) -> String? {
        let names = typingNames(in: t)
        if names.isEmpty { return nil }
        if !t.isGroup { return String(localized: "pisze…") }
        if names.count == 1 { return String(localized: "\(names[0]) pisze…") }
        return String(localized: "\(names.joined(separator: ", ")) piszą…")
    }

    // MARK: „pisze…” – wysyłka

    /// Rozmowa „na żywo”: ostatnia cudza wiadomość < 2 min temu (wtedy „pisze…” nie budzi telefonu w kieszeni).
    private func isLive(_ t: ChatTarget) -> Bool {
        guard let last = t.messages.filter({ !$0.isMine }).map(\.sentAt).max() else { return false }
        return Date().timeIntervalSince(last) < 120
    }

    private func typingContent(_ op: String, in t: ChatTarget) -> MessageContent {
        var c = MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .typing, op: op)
        if case .group(let g) = t { c.g = g.id }
        return c
    }

    /// Wołane przy każdej zmianie pola tekstowego (nie w trybie edycji).
    func userTyping(in t: ChatTarget, text: String) {
        guard typingIndicatorsEnabled, !isNoteToSelf(t) else { return }
        if text.isEmpty { userStoppedTyping(in: t); return }
        guard isLive(t) else { return }
        let now = Date()
        if now.timeIntervalSince(typingSentAt[t.id] ?? .distantPast) >= 5 {
            typingSentAt[t.id] = now
            let content = typingContent("start", in: t)
            Task { try? await deliver(content, to: t) }
        }
        typingStop[t.id]?.cancel()
        typingStop[t.id] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            self?.userStoppedTyping(in: t)
        }
    }

    /// Po wysłaniu wiadomości, wyczyszczeniu pola albo 5 s bez zmian.
    func userStoppedTyping(in t: ChatTarget) {
        typingStop[t.id]?.cancel(); typingStop[t.id] = nil
        guard typingSentAt.removeValue(forKey: t.id) != nil, typingIndicatorsEnabled else { return }
        let content = typingContent("stop", in: t)
        Task { try? await deliver(content, to: t) }
    }

    // MARK: przeczytane

    /// Po wyświetleniu rozmowy 1:1 (apka aktywna): potwierdza najnowszą cudzą wiadomość, najwyżej raz na 2 s.
    func scheduleReadReceipt(_ c: Contact) {
        guard UIApplication.shared.applicationState == .active, pendingRead(c) != nil else { return }
        readDebounce[c.id]?.cancel()
        readDebounce[c.id] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            await self?.sendReadReceipt(c)
        }
    }

    /// Najnowsza cudza wiadomość, której jeszcze nie potwierdziłem jako przeczytanej (nil = nic do wysłania).
    private func pendingRead(_ c: Contact) -> Message? {
        guard readReceiptsEnabled, !isNoteToSelf(c), !c.fromUnknown,
              let last = c.messages.filter({ !$0.isMine && !$0.wasDeleted }).max(by: { $0.sentAt < $1.sentAt }),
              lastReadSent[c.id] != last.id else { return nil }
        return last
    }

    func sendReadReceipt(_ c: Contact) async {
        guard let last = pendingRead(c) else { return }
        lastReadSent[c.id] = last.id
        let content = MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .receipt, ref: last.id, op: "read")
        try? await deliver(content, to: c)
    }

    /// `read` od kontaktu: wszystkie moje wiadomości do `ref` włącznie są przeczytane.
    func applyRead(ref: String?, from c: Contact) {
        guard readReceiptsEnabled, let ref, let upTo = c.messages.first(where: { $0.id == ref && $0.isMine })?.sentAt else { return }
        for m in c.messages where m.isMine && m.sentAt <= upTo && (m.status == .sent || m.status == .delivered) {
            m.status = .read
        }
    }

    // MARK: przypinanie i wyciszanie

    func togglePin(_ t: ChatTarget) {
        switch t { case .contact(let c): c.pinned.toggle(); case .group(let g): g.pinned.toggle() }
        try? context.save()
    }

    /// `until == nil` – wyłącza wyciszenie.
    func setMute(_ t: ChatTarget, until: Date?) {
        switch t { case .contact(let c): c.mutedUntil = until; case .group(let g): g.mutedUntil = until }
        try? context.save()
    }

    // MARK: wyszukiwanie

    func searchMessages(_ query: String, limit: Int = 50) -> [Message] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard q.count >= 2 else { return [] }
        var d = FetchDescriptor<Message>(predicate: #Predicate { !$0.wasDeleted && $0.text.localizedStandardContains(q) },
                                         sortBy: [SortDescriptor(\.sentAt, order: .reverse)])
        d.fetchLimit = limit
        return ((try? context.fetch(d)) ?? []).filter { $0.contact != nil || $0.group != nil }
    }

    // MARK: przekazywanie

    /// Wysyła kopię wiadomości (tekst albo załącznik z lokalnego pliku) jako nową wiadomość do `t`.
    func forward(_ m: Message, to t: ChatTarget) async {
        guard !m.wasDeleted else { return }
        if m.hasAttachment {
            guard let f = m.attachmentFile, let data = try? Data(contentsOf: Storage.media.appendingPathComponent(f)) else { return }
            let n = newForwarded(for: t)
            n.kind = m.isImage ? .image : m.kind
            n.attachmentW = m.attachmentW; n.attachmentH = m.attachmentH
            n.fileName = m.fileName; n.mimeType = m.mimeType; n.duration = m.duration
            n.attachmentFile = saveMediaFile(data, ext: (f as NSString).pathExtension.isEmpty ? "bin" : (f as NSString).pathExtension)
            try? context.save()
            await uploadAndSend(n, data: data, to: t)
        } else if !m.text.isEmpty {
            await sendText(m.text, to: t)
        }
    }

    private func newForwarded(for t: ChatTarget) -> Message {
        let m: Message
        switch t {
        case .contact(let c): m = Message(contact: c, text: "", isMine: true); c.lastActivity = Date()
        case .group(let g): m = Message(group: g, text: "", isMine: true); g.lastActivity = Date()
        }
        if t.disappearAfter > 0 { m.expiresAt = Date().addingTimeInterval(TimeInterval(t.disappearAfter)) }
        context.insert(m)
        return m
    }

    /// Wszystkie rozmowy do wyboru (notatka na początku, potem wg aktywności).
    func allTargets() -> [ChatTarget] {
        let contacts = (try? context.fetch(FetchDescriptor<Contact>())) ?? []
        let groups = (try? context.fetch(FetchDescriptor<ChatGroup>(predicate: #Predicate { !$0.left }))) ?? []
        let all = contacts.map(ChatTarget.contact) + groups.map(ChatTarget.group)
        return all.sorted { a, b in
            if isNoteToSelf(a) != isNoteToSelf(b) { return isNoteToSelf(a) }
            return a.lastActivity > b.lastActivity
        }
    }
}
