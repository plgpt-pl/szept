import XCTest
@testable import Szept

/// Lokalizacja (docs/WERSJA-0.3.md §5): każdy klucz katalogu ma tłumaczenie en, a zbudowana apka zawiera pl i en.
final class LocalizationTests: XCTestCase {
    private func catalog(_ name: String) throws -> [String: Any] {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("Sources/Szept/\(name).xcstrings")
        let json = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any]
        return try XCTUnwrap(json)
    }

    private func englishValue(_ entry: [String: Any]) -> String? {
        guard let loc = entry["localizations"] as? [String: Any], let en = loc["en"] as? [String: Any] else { return nil }
        if let unit = en["stringUnit"] as? [String: Any] { return unit["value"] as? String }
        if let variations = en["variations"] as? [String: Any], let plural = variations["plural"] as? [String: Any],
           let other = plural["other"] as? [String: Any], let unit = other["stringUnit"] as? [String: Any] { return unit["value"] as? String }
        return nil
    }

    func testEveryKeyHasEnglish() throws {
        for name in ["Localizable", "InfoPlist"] {
            let cat = try catalog(name)
            XCTAssertEqual(cat["sourceLanguage"] as? String, "pl")
            let strings = try XCTUnwrap(cat["strings"] as? [String: Any])
            XCTAssertGreaterThan(strings.count, name == "Localizable" ? 150 : 3)
            for (key, raw) in strings {
                let entry = try XCTUnwrap(raw as? [String: Any], key)
                let en = englishValue(entry)
                XCTAssertNotNil(en, "brak tłumaczenia en: \(key)")
                XCTAssertFalse((en ?? "").isEmpty, "puste tłumaczenie en: \(key)")
                // te same specyfikatory formatu po obu stronach
                let spec = { (s: String) in s.components(separatedBy: "%").dropFirst().map { String($0.prefix(while: { !$0.isWhitespace && !$0.isPunctuation || $0 == "$" })) }.count }
                XCTAssertEqual(spec(key), spec(en ?? ""), "liczba %-argumentów różni się: \(key)")
            }
        }
    }

    func testBundleContainsBothLanguages() throws {
        let bundle = Bundle(for: Engine.self)
        XCTAssertTrue(bundle.localizations.contains("pl"), "\(bundle.localizations)")
        XCTAssertTrue(bundle.localizations.contains("en"), "\(bundle.localizations)")
        let en = try XCTUnwrap(Bundle(path: try XCTUnwrap(bundle.path(forResource: "en", ofType: "lproj"))))
        XCTAssertEqual(en.localizedString(forKey: "Dodaj kontakt", value: nil, table: nil), "Add contact")
        XCTAssertEqual(en.localizedString(forKey: "Szepnij coś…", value: nil, table: nil), "Whisper something…")
        XCTAssertEqual(en.localizedString(forKey: "NSMicrophoneUsageDescription", value: nil, table: "InfoPlist"), "Recording voice messages (encrypted).")
        let pl = try XCTUnwrap(Bundle(path: try XCTUnwrap(bundle.path(forResource: "pl", ofType: "lproj"))))
        XCTAssertEqual(pl.localizedString(forKey: "NSMicrophoneUsageDescription", value: nil, table: "InfoPlist"), "Nagrywanie wiadomości głosowych (szyfrowanych).")
    }
}
