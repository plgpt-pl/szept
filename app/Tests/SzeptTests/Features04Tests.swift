import XCTest
import SwiftData
@testable import Szept

/// 0.4 (docs/WERSJA-0.4.md): „pisze…”, przeczytane, notatka dla siebie, przypinanie/wyciszanie, wyszukiwanie, przekazywanie.
@MainActor
final class Features04Tests: XCTestCase {
    let server = URL(string: ProcessInfo.processInfo.environment["SZEPT_TEST_SERVER"] ?? "http://127.0.0.1:11437")!

    override func setUp() {
        UserDefaults.standard.removeObject(forKey: "read_receipts")
        UserDefaults.standard.removeObject(forKey: "typing_indicators")
    }

    func makeEngine(_ scope: String, name: String) throws -> Engine {
        Keychain.set(nil as String?, account: scope + "token"); Keychain.set(nil as String?, account: scope + "account_id")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("szept-f04-\(scope)-\(UUID().uuidString)")
        let e = try Engine(container: try Storage.makeContainer(inMemory: true), storeRoot: dir, scope: scope)
        e.api.baseURL = server
        e.displayName = name
        return e
    }

    func testNewKindsAndUrgency() throws {
        let typing = MessageContent(id: "T", ts: 1, kind: .typing, op: "start")
        let back = try JSONDecoder().decode(MessageContent.self, from: try JSONEncoder().encode(typing))
        XCTAssertEqual(back.kind, .typing); XCTAssertEqual(back.op, "start")
        let read = try JSONDecoder().decode(MessageContent.self, from: Data(#"{"id":"R","ts":1,"kind":"receipt","ref":"X","op":"read"}"#.utf8))
        XCTAssertEqual(read.kind, .receipt); XCTAssertEqual(read.op, "read")
        // rodzaj z przyszłości nie psuje dekodowania
        XCTAssertEqual(try JSONDecoder().decode(MessageContent.self, from: Data(#"{"id":"F","ts":1,"kind":"poll"}"#.utf8)).kind, .unknown)
        for k: MessageContent.Kind in [.receipt, .typing, .reaction, .edit, .delete] {
            XCTAssertFalse(MessageContent(id: "x", ts: 1, kind: k).isUrgent, "\(k) nie budzi telefonu")
        }
        for k: MessageContent.Kind in [.text, .image, .file, .audio, .timer, .group] {
            XCTAssertTrue(MessageContent(id: "x", ts: 1, kind: k).isUrgent, "\(k) budzi telefon")
        }
    }

    func testTypingStateExpiresAndClears() throws {
        let e = try makeEngine("f04T", name: "T")
        let c = Contact(id: "OLAxxxxxxxxxxxxxxxxxxx", name: "Ola", identityKey: Data()); e.context.insert(c)
        let t = ChatTarget.contact(c)
        e.setTyping(true, sender: c.id, in: t)
        XCTAssertEqual(e.typingLabel(in: t), String(localized: "pisze…"))
        e.typing[t.id]?[c.id] = Date().addingTimeInterval(-1)            // wygasło
        XCTAssertNil(e.typingLabel(in: t))
        e.setTyping(true, sender: c.id, in: t); e.setTyping(false, sender: c.id, in: t)
        XCTAssertNil(e.typingLabel(in: t))
        e.typingIndicatorsEnabled = false
        e.setTyping(true, sender: c.id, in: t)
        XCTAssertNil(e.typingLabel(in: t), "wyłączony wskaźnik: nie pokazujemy cudzego")
    }

    func testPinMuteAndSearch() throws {
        let e = try makeEngine("f04P", name: "P")
        let c = Contact(id: "KASIAxxxxxxxxxxxxxxxxx", name: "Kasia", identityKey: Data()); e.context.insert(c)
        let t = ChatTarget.contact(c)
        e.togglePin(t); XCTAssertTrue(t.pinned)
        e.setMute(t, until: Date().addingTimeInterval(3600)); XCTAssertTrue(t.isMuted)
        e.setMute(t, until: Date().addingTimeInterval(-1)); XCTAssertFalse(t.isMuted, "wyciszenie wygasa")
        e.setMute(t, until: nil); XCTAssertFalse(t.isMuted)
        e.context.insert(Message(contact: c, text: "Spotkanie w czwartek o 10", isMine: false))
        let gone = Message(contact: c, text: "czwartek usunięty", isMine: true); gone.wasDeleted = true; e.context.insert(gone)
        try e.context.save()
        XCTAssertEqual(e.searchMessages("CZWARTEK").map(\.text), ["Spotkanie w czwartek o 10"], "bez wielkości liter, bez usuniętych")
        XCTAssertTrue(e.searchMessages("c").isEmpty, "min. 2 znaki")
    }

    func testReadReceiptsNoteToSelfAndForwardTwoPhones() async throws {
        let probe = SzeptAPI(baseURL: server, token: nil)
        guard (try? await probe.health()) == true else { throw XCTSkip("serwer lokalny nie działa") }
        let ala = try makeEngine("f04A", name: "Ala"), bob = try makeEngine("f04B", name: "Bob")
        for e in [ala, bob] { try await e.register(); e.stop() }
        let bobAtAla = try ala.addContact(bob.myLink!, name: "Bob")

        await ala.sendText("Pierwsza", to: bobAtAla)
        await ala.sendText("Druga", to: bobAtAla)
        try await bob.drainPending()
        let alaAtBob = try XCTUnwrap(bob.contact(id: ala.accountId!))
        alaAtBob.fromUnknown = false                                   // Bob „dodał” Alę
        try await Task.sleep(for: .milliseconds(500))                   // potwierdzenia odbioru wysyłane w tle
        try await ala.drainPending()
        XCTAssertEqual(Set(bobAtAla.messages.map(\.status)), [.delivered])

        await bob.sendReadReceipt(alaAtBob)
        try await ala.drainPending()
        XCTAssertEqual(Set(bobAtAla.messages.map(\.status)), [.read], "read obejmuje wszystkie wcześniejsze")
        await bob.sendReadReceipt(alaAtBob)                            // nic nowego – nie wysyła ponownie
        XCTAssertEqual(bob.lastReadSent[alaAtBob.id], alaAtBob.messages.max { $0.sentAt < $1.sentAt }?.id)

        // wyłączone potwierdzenia u Ali: cudze „read” ignorowane
        ala.readReceiptsEnabled = false
        await ala.sendText("Trzecia", to: bobAtAla)
        try await bob.drainPending(); try await Task.sleep(for: .milliseconds(500)); try await ala.drainPending()
        await bob.sendReadReceipt(alaAtBob)
        try await ala.drainPending()
        XCTAssertEqual(bobAtAla.messages.first { $0.text == "Trzecia" }?.status, .delivered)
        ala.readReceiptsEnabled = true

        // „pisze…” od Boba (rozmowa na żywo) dociera do Ali; zwykła wiadomość je czyści
        bob.userTyping(in: .contact(alaAtBob), text: "H")
        try await Task.sleep(for: .milliseconds(500))
        try await ala.drainPending()
        XCTAssertNotNil(ala.typingLabel(in: .contact(bobAtAla)))
        XCTAssertEqual(bobAtAla.unread, 0, "„pisze…” nie zwiększa nieprzeczytanych")
        bob.userStoppedTyping(in: .contact(alaAtBob))
        await bob.sendText("Hej", to: .contact(alaAtBob))
        try await ala.drainPending()
        XCTAssertNil(ala.typingLabel(in: .contact(bobAtAla)))

        // notatka dla siebie: nic nie idzie na serwer
        ala.ensureNoteToSelf()
        let note = try XCTUnwrap(ala.contact(id: ala.accountId!))
        XCTAssertTrue(ala.isNoteToSelf(note))
        await ala.sendText("lista zakupów", to: .contact(note))
        XCTAssertEqual(note.messages.first?.status, .sent)
        let pending = try await ala.api.pending()
        XCTAssertTrue(pending.isEmpty, "notatka nie trafia do skrzynki na serwerze")

        // przekazanie wiadomości z notatki do Boba
        let n = try XCTUnwrap(note.messages.first)
        await ala.forward(n, to: .contact(bobAtAla))
        try await bob.drainPending()
        XCTAssertTrue(alaAtBob.messages.contains { $0.text == "lista zakupów" && $0.replyToId == nil })
    }
}
