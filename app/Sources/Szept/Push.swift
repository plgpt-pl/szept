import UIKit
import UserNotifications
import UniformTypeIdentifiers

/// Push przez APNs (docs/WERSJA-0.3.md §3): serwer wysyła wyłącznie generyczne „Nowa wiadomość” – bez treści i bez nadawcy.
/// Apple dowiaduje się tylko, że na ten telefon coś przyszło. Po pushu apka budzi się i pobiera koperty.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Push.registerIfAllowed()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in await Engine.shared.registerPush(token: deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // brak sieci / symulator – odświeżanie w tle (BGAppRefresh) dalej działa
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        Task { @MainActor in
            let e = Engine.shared
            guard e.isRegistered else { return completionHandler(.noData) }
            do { try await e.drainPending(); completionHandler(.newData) } catch { completionHandler(.failed) }
        }
    }
}

enum Push {
    /// Rejestruje telefon w APNs, jeśli użytkownik zgodził się na powiadomienia (token wraca do AppDelegate).
    static func registerIfAllowed() {
        #if targetEnvironment(simulator)
        return
        #else
        UNUserNotificationCenter.current().getNotificationSettings { s in
            guard s.authorizationStatus == .authorized || s.authorizationStatus == .provisional else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
        #endif
    }

    /// "sandbox" dla buildów deweloperskich, "production" dla TestFlight/App Store – z `aps-environment` w embedded.mobileprovision.
    static var environment: String {
        guard let path = Bundle.main.path(forResource: "embedded", ofType: "mobileprovision"),
              let raw = try? Data(contentsOf: path.hasPrefix("/") ? URL(fileURLWithPath: path) : URL(fileURLWithPath: path)),
              let start = raw.range(of: Data("<?xml".utf8)), let end = raw.range(of: Data("</plist>".utf8)),
              let plist = try? PropertyListSerialization.propertyList(from: raw[start.lowerBound..<end.upperBound], format: nil) as? [String: Any],
              let ent = plist["Entitlements"] as? [String: Any], let aps = ent["aps-environment"] as? String else { return "production" }
        return aps == "development" ? "sandbox" : "production"
    }

    static func clearDelivered() { UNUserNotificationCenter.current().removeAllDeliveredNotifications() }
}

enum MimeTypes {
    static func mime(for ext: String) -> String {
        UTType(filenameExtension: ext.lowercased())?.preferredMIMEType ?? "application/octet-stream"
    }

    static func icon(for m: Message) -> String {
        let ext = ((m.fileName ?? "") as NSString).pathExtension.lowercased()
        switch ext {
        case "pdf": return "doc.richtext"
        case "zip", "gz", "7z", "rar": return "doc.zipper"
        case "mp4", "mov", "m4v": return "film"
        case "mp3", "m4a", "wav", "aac": return "waveform"
        case "jpg", "jpeg", "png", "heic", "gif": return "photo"
        case "doc", "docx", "pages", "txt", "md", "rtf": return "doc.text"
        case "xls", "xlsx", "numbers", "csv": return "tablecells"
        default: return "doc"
        }
    }

    static func size(_ bytes: Int) -> String {
        bytes >= 1_048_576 ? String(format: "%.1f MB", Double(bytes) / 1_048_576) : "\(max(1, bytes / 1024)) KB"
    }
}
