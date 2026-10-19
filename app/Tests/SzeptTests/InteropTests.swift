import XCTest
import SwiftData
@testable import Szept

/// Interop iOS ↔ Android przez prawdziwy serwer. Wymaga TEST_RUNNER_SZEPT_PEER_LINK (link z testu Androida), inaczej pomijany.
@MainActor
final class InteropTests: XCTestCase {
    func testTalkToAndroid() async throws {
        // link partnera: zmienna środowiskowa (TEST_RUNNER_SZEPT_PEER_LINK) albo plik /tmp/szept-peer-link (symulator widzi dysk hosta)
        let env = ProcessInfo.processInfo.environment["SZEPT_PEER_LINK"]
        let file = try? String(contentsOfFile: "/tmp/szept-peer-link", encoding: .utf8)
        guard let linkStr = env ?? file, let link = ContactLink(string: linkStr) else {
            throw XCTSkip("brak SZEPT_PEER_LINK (env: \(env ?? "-"), plik: \(file ?? "-"))")
        }
        let server = URL(string: ProcessInfo.processInfo.environment["SZEPT_SERVER"] ?? "http://127.0.0.1:11437")!
        let scope = "interop-\(UUID().uuidString.prefix(8))"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(scope)
        let me = try Engine(container: try Storage.makeContainer(inMemory: true), storeRoot: dir, scope: scope)
        me.api.baseURL = server
        me.displayName = "iPhone"
        try await me.register(); me.stop()
        let android = try me.addContact(link, name: "Android")
        await me.sendText("Cześć z iPhone'a 🍎", to: android)
        XCTAssertEqual(android.messages.first?.status, .sent)
        let deadline = Date().addingTimeInterval(90)
        while Date() < deadline, !android.messages.contains(where: { !$0.isMine }) {
            try await me.drainPending()
            try await Task.sleep(for: .seconds(1))
        }
        let reply = try XCTUnwrap(android.messages.first { !$0.isMine }, "Android nie odpowiedział w 90 s")
        print("SZEPT_INTEROP GOT:", reply.text)
        XCTAssertTrue(reply.text.contains("Androida"))
        XCTAssertTrue(reply.text.contains("Cześć z iPhone'a"), "Android zacytował naszą wiadomość")
        try await Task.sleep(for: .seconds(1))
        try await me.drainPending()   // odbiera potwierdzenie i wysyła swoje
        XCTAssertEqual(android.messages.first { $0.isMine }?.status, .delivered)
        try await me.api.deleteAccount()
    }
}

/// „Bot” do demonstracji: rejestruje się, zapisuje swój link do /tmp/szept-ios-link, czeka na wiadomość i odpowiada.
/// Uruchamiany tylko z TEST_RUNNER_SZEPT_PEER_MODE=bot (albo plikiem /tmp/szept-peer-mode = bot).
@MainActor
final class PeerBotTests: XCTestCase {
    func testBot() async throws {
        let mode = ProcessInfo.processInfo.environment["SZEPT_PEER_MODE"] ?? (try? String(contentsOfFile: "/tmp/szept-peer-mode", encoding: .utf8))
        guard mode?.trimmingCharacters(in: .whitespacesAndNewlines) == "bot" else { throw XCTSkip("tryb bot nieaktywny") }
        let server = URL(string: ProcessInfo.processInfo.environment["SZEPT_SERVER"] ?? "https://szept.plgpt.pl")!
        let scope = "bot-\(UUID().uuidString.prefix(8))"
        let me = try Engine(container: try Storage.makeContainer(inMemory: true), storeRoot: FileManager.default.temporaryDirectory.appendingPathComponent(scope), scope: scope)
        me.api.baseURL = server; me.displayName = "Ola (iPhone)"
        try await me.register(); me.stop()
        try me.myLink!.url.absoluteString.write(toFile: "/tmp/szept-ios-link", atomically: true, encoding: .utf8)
        let deadline = Date().addingTimeInterval(300)
        var replied = 0
        while Date() < deadline, replied < 2 {
            try await me.drainPending()
            for c in (try? me.context.fetch(FetchDescriptor<Contact>())) ?? [] where c.messages.contains(where: { !$0.isMine }) && !c.messages.contains(where: { $0.isMine }) {
                await me.sendText("Dzień dobry z iPhone'a 🍎 Widzę Twoją wiadomość, szyfrowanie działa w obie strony.", to: c)
                try await Task.sleep(for: .seconds(2))
                await me.sendText("Serwer zobaczył tylko dwie koperty bez nadawcy.", to: c)
                replied += 1
            }
            try await Task.sleep(for: .seconds(1))
        }
        try await Task.sleep(for: .seconds(15)); try await me.drainPending()   // potwierdzenia
        XCTAssertGreaterThan(replied, 0, "nikt nie napisał w 300 s")
        try? FileManager.default.removeItem(atPath: "/tmp/szept-ios-link")
        try await me.api.deleteAccount()
    }
}
