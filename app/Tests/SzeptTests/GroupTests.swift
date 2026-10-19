import XCTest
import SwiftData
@testable import Szept

/// Grupy przez rozsyłanie (docs/GRUPY.md): trzy telefony przez prawdziwy serwer 127.0.0.1:11437. Pomijany, gdy serwer nie działa.
@MainActor
final class GroupTests: XCTestCase {
    let server = URL(string: "http://127.0.0.1:11437")!

    func makeEngine(_ scope: String, name: String) throws -> Engine {
        Keychain.set(nil as String?, account: scope + "token"); Keychain.set(nil as String?, account: scope + "account_id")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("szept-grp-\(scope)-\(UUID().uuidString)")
        let e = try Engine(container: try Storage.makeContainer(inMemory: true), storeRoot: dir, scope: scope)
        e.api.baseURL = server
        e.displayName = name
        return e
    }

    func testGroupContentJSONRoundTrip() throws {
        let m = MessageContent(id: "X", ts: 1, kind: .group, g: "G1", gname: "Rodzina",
                               members: [.init(i: "A", k: "a2V5", n: "Ala"), .init(i: "B", k: "a2V5", n: nil)], op: "set")
        let data = try JSONEncoder().encode(m)
        let json = String(data: data, encoding: .utf8)!
        XCTAssertTrue(json.contains("\"op\":\"set\"") && json.contains("\"gname\":\"Rodzina\"") && json.contains("\"g\":\"G1\""))
        let back = try JSONDecoder().decode(MessageContent.self, from: data)
        XCTAssertEqual(back.members, m.members); XCTAssertEqual(back.kind, .group)
        // zwykła wiadomość nie niesie pól grupowych
        let plain = try JSONEncoder().encode(MessageContent(id: "Y", ts: 1, kind: .text, text: "hej"))
        XCTAssertFalse(String(data: plain, encoding: .utf8)!.contains("\"g\""))
    }

    func testThreePhonesInAGroup() async throws {
        let probe = SzeptAPI(baseURL: server, token: nil)
        guard (try? await probe.health()) == true else { throw XCTSkip("serwer lokalny nie działa") }
        let ala = try makeEngine("grpA", name: "Ala"), bartek = try makeEngine("grpB", name: "Bartek"), celina = try makeEngine("grpC", name: "Celina")
        for e in [ala, bartek, celina] { try await e.register(); e.stop() }

        // Ala zna Bartka i Celinę z QR; Bartek i Celina nie znają się nawzajem.
        let bAtA = try ala.addContact(bartek.myLink!, name: "Bartek")
        let cAtA = try ala.addContact(celina.myLink!, name: "Celina")
        let g = await ala.createGroup(name: "Wypad", members: [bAtA, cAtA])
        XCTAssertEqual(g.members.count, 2)

        try await bartek.drainPending(); try await celina.drainPending()
        let gAtB = try XCTUnwrap(bartek.group(id: g.id)); let gAtC = try XCTUnwrap(celina.group(id: g.id))
        XCTAssertEqual(gAtB.name, "Wypad")
        XCTAssertEqual(Set(gAtB.members), Set([ala.accountId!, celina.accountId!]), "Bartek widzi Alę i Celinę")
        XCTAssertEqual(Set(gAtC.members), Set([ala.accountId!, bartek.accountId!]))
        let cAtB = try XCTUnwrap(bartek.contact(id: celina.accountId!))
        XCTAssertTrue(cAtB.fromUnknown, "Celina doszła z zaproszenia, nie z QR"); XCTAssertEqual(cAtB.name, "Celina")
        XCTAssertEqual(cAtB.identityKey, celina.proto.identityKey.serialize(), "klucz Celiny przypięty z wiadomości Ali")
        XCTAssertEqual(gAtB.unread, 1)

        // Ala pisze do grupy – oboje dostają w grupie, nie w rozmowie 1:1
        await ala.sendText("Jedziemy w sobotę?", to: g)
        XCTAssertEqual(g.messages.first { $0.isMine && !$0.text.hasPrefix("Utworzyłeś") }?.status, .sent)
        try await bartek.drainPending(); try await celina.drainPending()
        let atB = try XCTUnwrap(gAtB.messages.first { $0.text == "Jedziemy w sobotę?" })
        XCTAssertEqual(atB.senderId, ala.accountId); XCTAssertFalse(atB.isMine)
        XCTAssertTrue(bartek.contact(id: ala.accountId!)!.messages.isEmpty, "rozmowa 1:1 z Alą pusta")
        XCTAssertNotNil(gAtC.messages.first { $0.text == "Jedziemy w sobotę?" })

        // Celina odpowiada (do Bartka pierwsza wiadomość: sesja z paczki kluczy, klucz przypięty TOFU)
        await celina.sendText("Tak!", to: gAtC)
        try await ala.drainPending(); try await bartek.drainPending()
        XCTAssertEqual(g.messages.first { $0.text == "Tak!" }?.senderId, celina.accountId)
        XCTAssertEqual(gAtB.messages.first { $0.text == "Tak!" }?.senderId, celina.accountId)
        XCTAssertEqual(bartek.senderName(celina.accountId), "Celina")

        // Zmiana nazwy i znikanie
        await ala.renameGroup(g, to: "Wypad w góry")
        await ala.setDisappear(300, for: g)
        try await bartek.drainPending()
        XCTAssertEqual(gAtB.name, "Wypad w góry"); XCTAssertEqual(gAtB.disappearAfter, 300)

        // Obcy nie może sterować grupą
        let dawid = try makeEngine("grpD", name: "Dawid"); try await dawid.register(); dawid.stop()
        _ = try dawid.addContact(bartek.myLink!, name: "B")
        let fake = ChatGroup(id: g.id, name: "Przejęta", members: [bartek.accountId!]); dawid.context.insert(fake)
        await dawid.sendText("psst", to: fake)
        try await bartek.drainPending()
        XCTAssertEqual(gAtB.name, "Wypad w góry"); XCTAssertNil(gAtB.messages.first { $0.text == "psst" }, "wiadomość nie-członka zignorowana")

        // Bartek wychodzi
        await bartek.leaveGroup(gAtB)
        XCTAssertNil(bartek.group(id: g.id))
        try await ala.drainPending(); try await celina.drainPending()
        XCTAssertEqual(g.members, [celina.accountId!]); XCTAssertEqual(gAtC.members, [ala.accountId!])
        XCTAssertNotNil(g.messages.first { $0.text.contains("opuścił") })

        for e in [ala, bartek, celina, dawid] { try? await e.api.deleteAccount() }
    }
}
