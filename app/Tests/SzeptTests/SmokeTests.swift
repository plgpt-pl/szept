import XCTest
import LibSignalClient
@testable import Szept

/// Dwie tożsamości w jednym procesie; „serwer” symulowany w pamięci – czysty test protokołu.
final class ProtocolTests: XCTestCase {
    var dirA: URL!, dirB: URL!
    var a: SzeptProtocol!, b: SzeptProtocol!
    var regA: RegistrationPayload!, regB: RegistrationPayload!

    override func setUpWithError() throws {
        dirA = FileManager.default.temporaryDirectory.appendingPathComponent("szeptA-\(UUID().uuidString)")
        dirB = FileManager.default.temporaryDirectory.appendingPathComponent("szeptB-\(UUID().uuidString)")
        a = SzeptProtocol(store: try SignalStore(root: dirA), accountId: "ALICE")
        b = SzeptProtocol(store: try SignalStore(root: dirB), accountId: "BOB")
        regA = try a.makeRegistration(); regB = try b.makeRegistration()
    }
    override func tearDown() { try? FileManager.default.removeItem(at: dirA); try? FileManager.default.removeItem(at: dirB) }

    /// To, co zrobiłby serwer: paczka z pierwszym kluczem jednorazowym (albo bez, gdy wyczerpane).
    func bundle(_ r: RegistrationPayload, id: String, oneTime: Bool = true) -> BundleJSON {
        BundleJSON(account_id: id, device_id: 1, identity_key: r.identity_key, registration_id: r.registration_id,
                   signed_prekey: r.signed_prekey, prekey: oneTime ? r.prekeys[0] : nil,
                   kyber_prekey: oneTime ? r.kyber_prekeys[0] : r.kyber_last_resort)
    }

    func testRoundTripBothDirections() throws {
        try a.processBundle(bundle(regB, id: "BOB"), pinnedIdentity: Data(base64Encoded: regB.identity_key))
        XCTAssertTrue(a.hasSession(with: "BOB")); XCTAssertFalse(b.hasSession(with: "ALICE"))
        let sealed = try a.seal(Data("cześć Bob".utf8), to: "BOB", recipientIdentity: Data(base64Encoded: regB.identity_key)!)
        XCTAssertNil(String(data: sealed, encoding: .utf8)?.range(of: "ALICE"))
        let opened = try b.open(sealed)
        XCTAssertEqual(String(data: opened.plaintext, encoding: .utf8), "cześć Bob")
        XCTAssertEqual(opened.sender, "ALICE"); XCTAssertTrue(opened.isPreKey)
        XCTAssertEqual(opened.senderIdentity.serialize().base64EncodedString(), regA.identity_key)
        XCTAssertTrue(b.hasSession(with: "ALICE"))
        let reply = try b.seal(Data("hej Alice".utf8), to: "ALICE", recipientIdentity: Data(base64Encoded: regA.identity_key)!)
        let o2 = try a.open(reply)
        XCTAssertEqual(String(data: o2.plaintext, encoding: .utf8), "hej Alice"); XCTAssertFalse(o2.isPreKey)
        for i in 0..<20 {
            let s = try (i % 2 == 0 ? a : b).seal(Data("m\(i)".utf8), to: i % 2 == 0 ? "BOB" : "ALICE",
                                                   recipientIdentity: Data(base64Encoded: i % 2 == 0 ? regB.identity_key : regA.identity_key)!)
            XCTAssertEqual(String(data: try (i % 2 == 0 ? b : a).open(s).plaintext, encoding: .utf8), "m\(i)")
        }
    }

    func testBundleWithoutOneTimeKeysStillWorks() throws {
        try a.processBundle(bundle(regB, id: "BOB", oneTime: false), pinnedIdentity: nil)
        let sealed = try a.seal(Data("x".utf8), to: "BOB", recipientIdentity: Data(base64Encoded: regB.identity_key)!)
        XCTAssertEqual(String(data: try b.open(sealed).plaintext, encoding: .utf8), "x")
    }

    func testPinnedIdentityMismatchRejected() throws {
        XCTAssertThrowsError(try a.processBundle(bundle(regB, id: "BOB"), pinnedIdentity: Data(base64Encoded: regA.identity_key))) {
            guard case SzeptError.identityMismatch = $0 else { return XCTFail("zły błąd: \($0)") }
        }
    }

    func testReplayAndTamperingFail() throws {
        try a.processBundle(bundle(regB, id: "BOB"), pinnedIdentity: nil)
        var sealed = try a.seal(Data("tajne".utf8), to: "BOB", recipientIdentity: Data(base64Encoded: regB.identity_key)!)
        _ = try b.open(sealed)
        XCTAssertThrowsError(try b.open(sealed), "powtórka tej samej koperty musi paść")
        sealed[sealed.count - 1] ^= 0x01
        XCTAssertThrowsError(try b.open(sealed))
        XCTAssertThrowsError(try a.open(sealed), "koperta do Boba nie otwiera się kluczem Alicji")
    }

    func testAttachmentEncryption() throws {
        let data = Data((0..<5000).map { UInt8($0 % 251) })
        let (ct, key) = try SzeptProtocol.encryptAttachment(data)
        XCTAssertNotEqual(ct, data)
        XCTAssertEqual(try SzeptProtocol.decryptAttachment(ct, key: key), data)
        var bad = ct; bad[10] ^= 1
        XCTAssertThrowsError(try SzeptProtocol.decryptAttachment(bad, key: key))
    }

    func testSafetyNumbersMatchOnBothSides() throws {
        let fa = try a.safetyNumber(with: "BOB", identity: Data(base64Encoded: regB.identity_key)!)
        let fb = try b.safetyNumber(with: "ALICE", identity: Data(base64Encoded: regA.identity_key)!)
        XCTAssertEqual(fa, fb)
        XCTAssertEqual(fa.filter(\.isNumber).count, 60)
    }

    func testContactLinkRoundTrip() throws {
        let l = ContactLink(accountId: "ALICE", identityKey: Data(base64Encoded: regA.identity_key)!, name: "Ala Ż")
        let parsed = ContactLink(string: l.url.absoluteString)
        XCTAssertEqual(parsed, l)
        XCTAssertNil(ContactLink(string: "https://example.com/add?i=ALICE"))
        XCTAssertNil(ContactLink(string: "szept://add?i=ALICE&k=bm90YWtleQ=="))
    }

    func testStoreSurvivesReopen() throws {
        try a.processBundle(bundle(regB, id: "BOB"), pinnedIdentity: nil)
        let sealed = try a.seal(Data("1".utf8), to: "BOB", recipientIdentity: Data(base64Encoded: regB.identity_key)!)
        _ = try b.open(sealed)
        let b2 = SzeptProtocol(store: try SignalStore(root: dirB), accountId: "BOB")
        XCTAssertEqual(b2.identityKeyBase64, regB.identity_key)
        XCTAssertTrue(b2.hasSession(with: "ALICE"))
        let reply = try b2.seal(Data("2".utf8), to: "ALICE", recipientIdentity: Data(base64Encoded: regA.identity_key)!)
        XCTAssertEqual(String(data: try a.open(reply).plaintext, encoding: .utf8), "2")
        XCTAssertEqual(b2.store.kyberPreKeyCount, Config.preKeyBatch - 1, "jednorazowy Kyber zużyty, ostatniej szansy został")
    }
}

/// Pełny obieg przez prawdziwy serwer (127.0.0.1:11437, uruchom `server/run-server.sh`). Pomijany, gdy serwer nie działa.
@MainActor
final class EndToEndTests: XCTestCase {
    let server = URL(string: "http://127.0.0.1:11437")!

    func makeEngine(_ scope: String) throws -> Engine {
        Keychain.set(nil as String?, account: scope + "token"); Keychain.set(nil as String?, account: scope + "account_id")
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("szept-e2e-\(scope)-\(UUID().uuidString)")
        let e = try Engine(container: try Storage.makeContainer(inMemory: true), storeRoot: dir, scope: scope)
        e.api.baseURL = server
        return e
    }

    func testTwoPhonesTalkThroughServer() async throws {
        let probe = SzeptAPI(baseURL: server, token: nil)
        guard (try? await probe.health()) == true else { throw XCTSkip("serwer lokalny nie działa") }
        let alice = try makeEngine("e2eA"), bob = try makeEngine("e2eB")
        alice.displayName = "Alice"
        try await alice.register(); try await bob.register()
        alice.stop(); bob.stop()   // bez WebSocketu – odbiór sterujemy ręcznie przez drainPending()
        XCTAssertNotNil(alice.accountId); XCTAssertNotEqual(alice.accountId, bob.accountId)

        let bobContact = try alice.addContact(bob.myLink!, name: "Bob")
        await alice.sendText("Cześć Bob, to Szept", to: bobContact)
        XCTAssertEqual(bobContact.messages.first?.status, .sent)

        try await bob.drainPending()
        let aliceAtBob = try XCTUnwrap(bob.contact(id: alice.accountId!))
        XCTAssertEqual(aliceAtBob.name, "Alice"); XCTAssertTrue(aliceAtBob.fromUnknown)
        XCTAssertEqual(aliceAtBob.messages.first?.text, "Cześć Bob, to Szept"); XCTAssertEqual(aliceAtBob.unread, 1)

        await bob.sendText("Hej Alice!", to: aliceAtBob)
        try await Task.sleep(for: .milliseconds(500))   // potwierdzenie odbioru leci w tle
        try await alice.drainPending()
        XCTAssertEqual(bobContact.messages.map(\.text).sorted(), ["Cześć Bob, to Szept", "Hej Alice!"])
        XCTAssertEqual(bobContact.messages.first { $0.isMine }?.status, .delivered, "potwierdzenie odbioru od Boba")

        let img = UIGraphicsImageRenderer(size: CGSize(width: 300, height: 200)).image { c in UIColor.systemPink.setFill(); c.fill(CGRect(x: 0, y: 0, width: 300, height: 200)) }
        await alice.sendImage(img, to: bobContact)
        try await bob.drainPending()
        try await Task.sleep(for: .seconds(1))
        let photo = try XCTUnwrap(aliceAtBob.messages.first { $0.attachmentW > 0 })
        XCTAssertEqual(photo.attachmentW, 300)
        XCTAssertNotNil(photo.attachmentFile, "załącznik pobrany i odszyfrowany")

        await alice.setDisappear(300, for: bobContact)
        try await bob.drainPending()
        XCTAssertEqual(aliceAtBob.disappearAfter, 300)

        let (n, k) = try await bob.api.keyCounts()
        XCTAssertEqual(n, Config.preKeyBatch - 1); XCTAssertEqual(k, Config.preKeyBatch - 1)

        try await alice.api.deleteAccount(); try await bob.api.deleteAccount()
    }
}
