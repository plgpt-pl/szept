import XCTest
import SwiftData
@testable import Szept

/// 0.3 (docs/WERSJA-0.3.md): odpowiedzi, reakcje, edycja, usuwanie, pliki, głosówki – JSON i e2e przez lokalny serwer 127.0.0.1:11437.
@MainActor
final class Features03Tests: XCTestCase {
    let server = URL(string: "http://127.0.0.1:11437")!

    func makeEngine(_ scope: String, name: String) throws -> Engine {
        Keychain.set(nil as String?, account: scope + "token"); Keychain.set(nil as String?, account: scope + "account_id")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("szept-f03-\(scope)-\(UUID().uuidString)")
        let e = try Engine(container: try Storage.makeContainer(inMemory: true), storeRoot: dir, scope: scope)
        e.api.baseURL = server
        e.displayName = name
        return e
    }

    func testNewFieldsAndKindsRoundTrip() throws {
        var m = MessageContent(id: "A", ts: 1, kind: .file, att: .init(id: "att", key: Data(repeating: 1, count: 32), nonce: Data(repeating: 2, count: 12), size: 10, w: 0, h: 0))
        m.fn = "umowa.pdf"; m.mime = "application/pdf"; m.r = "Q"
        let json = String(data: try JSONEncoder().encode(m), encoding: .utf8)!
        XCTAssertTrue(json.contains("\"kind\":\"file\"") && json.contains("\"fn\":\"umowa.pdf\"") && json.contains("\"r\":\"Q\"") && json.contains("\"mime\":\"application\\/pdf\""))
        let back = try JSONDecoder().decode(MessageContent.self, from: Data(json.utf8))
        XCTAssertEqual(back.kind, .file); XCTAssertEqual(back.fn, "umowa.pdf"); XCTAssertEqual(back.r, "Q")

        let audio = MessageContent(id: "B", ts: 1, kind: .audio, att: m.att, dur: 12.5)
        XCTAssertEqual(try JSONDecoder().decode(MessageContent.self, from: try JSONEncoder().encode(audio)).dur, 12.5)
        let react = MessageContent(id: "C", ts: 1, kind: .reaction, text: "👍", ref: "A", op: "set")
        XCTAssertTrue(String(data: try JSONEncoder().encode(react), encoding: .utf8)!.contains("\"op\":\"set\""))
        for k in [MessageContent.Kind.edit, .delete] {
            XCTAssertEqual(try JSONDecoder().decode(MessageContent.self, from: try JSONEncoder().encode(MessageContent(id: "D", ts: 1, kind: k, ref: "A"))).kind, k)
        }
        // zwykła wiadomość nie niesie nowych pól
        let plain = String(data: try JSONEncoder().encode(MessageContent(id: "E", ts: 1, kind: .text, text: "hej")), encoding: .utf8)!
        for key in ["\"r\"", "\"fn\"", "\"mime\"", "\"dur\""] { XCTAssertFalse(plain.contains(key), key) }
        // nieznany rodzaj z nowszego klienta: dekoduje się jako .unknown (ignorowany), nie crashuje
        let future = try JSONDecoder().decode(MessageContent.self, from: Data(#"{"id":"F","ts":1,"kind":"hologram","text":"x"}"#.utf8))
        XCTAssertEqual(future.kind, .unknown)
    }

    func testMessageSummaryAndReactions() {
        let m = Message(text: "", isMine: false)
        m.kind = .audio; m.duration = 65
        XCTAssertEqual(m.summary, "🎤 Głosówka 1:05")
        m.kind = .file; m.fileName = "raport.xlsx"
        XCTAssertEqual(m.summary, "📎 raport.xlsx")
        m.reactions = ["A": "👍", "B": "❤️"]
        XCTAssertEqual(m.reactions["B"], "❤️")
        m.wasDeleted = true
        XCTAssertEqual(m.summary, "Wiadomość usunięta")
    }

    func testReplyReactEditDeleteFilesTwoPhones() async throws {
        let probe = SzeptAPI(baseURL: server, token: nil)
        guard (try? await probe.health()) == true else { throw XCTSkip("serwer lokalny nie działa") }
        let ala = try makeEngine("f03A", name: "Ala"), bob = try makeEngine("f03B", name: "Bob")
        for e in [ala, bob] { try await e.register(); e.stop() }
        let bobAtAla = try ala.addContact(bob.myLink!, name: "Bob")

        // tekst + odpowiedź
        await ala.sendText("Masz chwilę?", to: bobAtAla)
        try await bob.drainPending()
        let alaAtBob = try XCTUnwrap(bob.contact(id: ala.accountId!))
        let q = try XCTUnwrap(alaAtBob.messages.first { $0.text == "Masz chwilę?" })
        await bob.sendText("Tak, za 5 min", to: .contact(alaAtBob), replyTo: q.id)
        try await ala.drainPending()
        let reply = try XCTUnwrap(bobAtAla.messages.first { $0.text == "Tak, za 5 min" })
        XCTAssertEqual(reply.replyToId, q.id, "odpowiedź niesie id cytowanej wiadomości")
        XCTAssertEqual(ala.messageBy(id: q.id, in: .contact(bobAtAla))?.isMine, true)

        // reakcja: set, potem remove
        await ala.react("👍", to: reply)
        XCTAssertEqual(ala.myReaction(on: reply), "👍")
        try await bob.drainPending()
        let replyAtBob = try XCTUnwrap(alaAtBob.messages.first { $0.text == "Tak, za 5 min" })
        XCTAssertEqual(replyAtBob.reactions[ala.accountId!], "👍")
        XCTAssertEqual(alaAtBob.unread, 1, "reakcja nie zwiększa nieprzeczytanych")
        await ala.react(nil, to: reply)
        try await bob.drainPending()
        XCTAssertTrue(replyAtBob.reactions.isEmpty)

        // edycja własnej (przyjęta) i cudzej (zignorowana)
        let mine = try XCTUnwrap(bobAtAla.messages.first { $0.text == "Masz chwilę?" })
        await ala.edit(mine, text: "Masz chwilę? Pilne")
        try await bob.drainPending()
        XCTAssertEqual(q.text, "Masz chwilę? Pilne"); XCTAssertNotNil(q.editedAt)
        // Bob próbuje edytować wiadomość Ali (spreparowany pakiet)
        try await bob.deliver(MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .edit, text: "HACK", ref: q.id), to: alaAtBob)
        try await ala.drainPending()
        XCTAssertEqual(mine.text, "Masz chwilę? Pilne", "cudzej wiadomości nie da się edytować")
        // i usunąć
        try await bob.deliver(MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .delete, ref: q.id), to: alaAtBob)
        try await ala.drainPending()
        XCTAssertFalse(mine.wasDeleted)

        // usunięcie własnej u wszystkich
        await bob.deleteForEveryone(replyAtBob)
        XCTAssertTrue(replyAtBob.wasDeleted)
        try await ala.drainPending()
        XCTAssertTrue(reply.wasDeleted); XCTAssertEqual(reply.text, ""); XCTAssertEqual(reply.summary, "Wiadomość usunięta")

        // plik 100 KB
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("umowa-\(UUID().uuidString).pdf")
        let blob = Data((0..<100_000).map { UInt8($0 % 251) })
        try blob.write(to: tmp)
        await ala.sendFile(tmp, to: .contact(bobAtAla))
        let fileAtAla = try XCTUnwrap(bobAtAla.messages.first { $0.kind == .file })
        XCTAssertEqual(fileAtAla.status, .sent); XCTAssertEqual(fileAtAla.mimeType, "application/pdf")
        try await bob.drainPending()
        let fileAtBob = try XCTUnwrap(alaAtBob.messages.first { $0.kind == .file })
        XCTAssertEqual(fileAtBob.fileName, tmp.lastPathComponent); XCTAssertEqual(fileAtBob.mimeType, "application/pdf")
        for _ in 0..<40 where fileAtBob.attachmentFile == nil { try await Task.sleep(for: .milliseconds(100)) }
        let got = try XCTUnwrap(fileAtBob.attachmentFile)
        XCTAssertEqual(try Data(contentsOf: Storage.media.appendingPathComponent(got)), blob, "plik odszyfrowany 1:1")
        XCTAssertTrue(got.hasSuffix(".pdf"))
        XCTAssertEqual(bob.exportURL(for: fileAtBob)?.lastPathComponent, tmp.lastPathComponent)

        // głosówka
        let voice = FileManager.default.temporaryDirectory.appendingPathComponent("v-\(UUID().uuidString).m4a")
        try Data(repeating: 7, count: 4000).write(to: voice)
        await bob.sendAudio(voice, duration: 3.4, to: .contact(alaAtBob), replyTo: fileAtBob.id)
        try await ala.drainPending()
        let audioAtAla = try XCTUnwrap(bobAtAla.messages.first { $0.kind == .audio })
        XCTAssertEqual(audioAtAla.duration, 3.4); XCTAssertEqual(audioAtAla.replyToId, fileAtAla.id); XCTAssertEqual(audioAtAla.mimeType, "audio/mp4")
        for _ in 0..<40 where audioAtAla.attachmentFile == nil { try await Task.sleep(for: .milliseconds(100)) }
        XCTAssertTrue(try XCTUnwrap(audioAtAla.attachmentFile).hasSuffix(".m4a"))

        // za duży plik odrzucony lokalnie
        let big = FileManager.default.temporaryDirectory.appendingPathComponent("big-\(UUID().uuidString).bin")
        try Data(count: Config.maxFileBytes + 1).write(to: big)
        let before = bobAtAla.messages.count
        await ala.sendFile(big, to: .contact(bobAtAla))
        XCTAssertEqual(bobAtAla.messages.count, before); XCTAssertNotNil(ala.lastError)
        try? FileManager.default.removeItem(at: big)

        for e in [ala, bob] { try? await e.api.deleteAccount() }
    }

    func testReactionAndDeleteInGroup() async throws {
        let probe = SzeptAPI(baseURL: server, token: nil)
        guard (try? await probe.health()) == true else { throw XCTSkip("serwer lokalny nie działa") }
        let ala = try makeEngine("f03GA", name: "Ala"), bartek = try makeEngine("f03GB", name: "Bartek"), celina = try makeEngine("f03GC", name: "Celina")
        for e in [ala, bartek, celina] { try await e.register(); e.stop() }
        let g = await ala.createGroup(name: "Ekipa", members: [try ala.addContact(bartek.myLink!, name: "Bartek"), try ala.addContact(celina.myLink!, name: "Celina")])
        try await bartek.drainPending(); try await celina.drainPending()
        let gB = try XCTUnwrap(bartek.group(id: g.id)), gC = try XCTUnwrap(celina.group(id: g.id))

        await bartek.sendText("Kto jedzie?", to: gB)
        try await ala.drainPending(); try await celina.drainPending()
        let msgA = try XCTUnwrap(g.messages.first { $0.text == "Kto jedzie?" }), msgC = try XCTUnwrap(gC.messages.first { $0.text == "Kto jedzie?" })

        await celina.react("🙏", to: msgC)
        await ala.react("👍", to: msgA)
        try await bartek.drainPending(); try await ala.drainPending(); try await celina.drainPending()
        let msgB = try XCTUnwrap(gB.messages.first { $0.text == "Kto jedzie?" })
        XCTAssertEqual(msgB.reactions, [celina.accountId!: "🙏", ala.accountId!: "👍"])
        XCTAssertEqual(msgA.reactions[celina.accountId!], "🙏", "reakcja rozesłana do wszystkich członków")

        // Ala nie może usunąć wiadomości Bartka; Bartek może
        try await ala.deliver(MessageContent(id: UUID().uuidString, ts: Date().timeIntervalSince1970, kind: .delete, ref: msgA.id, g: g.id), to: .group(g))
        try await bartek.drainPending(); try await celina.drainPending()
        XCTAssertFalse(msgB.wasDeleted); XCTAssertFalse(msgC.wasDeleted)
        await bartek.deleteForEveryone(msgB)
        try await ala.drainPending(); try await celina.drainPending()
        XCTAssertTrue(msgA.wasDeleted && msgC.wasDeleted)
        XCTAssertTrue(msgA.reactions.isEmpty)

        for e in [ala, bartek, celina] { try? await e.api.deleteAccount() }
    }
}
