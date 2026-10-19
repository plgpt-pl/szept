import BackgroundTasks
import Foundation

/// Odświeżanie w tle bez APNs: iOS budzi apkę co jakiś czas (wg własnego uznania, zwykle co kilka godzin),
/// apka odbiera zaległe koperty i pokazuje powiadomienie bez treści. Żadna informacja nie idzie przez Apple.
enum BackgroundRefresh {
    static let id = "pl.plgpt.szept.refresh"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            guard let task = task as? BGAppRefreshTask else { return }
            schedule()
            let work = Task { @MainActor in
                let e = Engine.shared
                guard e.isRegistered else { return task.setTaskCompleted(success: true) }
                do { try await e.drainPending(); task.setTaskCompleted(success: true) } catch { task.setTaskCompleted(success: false) }
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    static func schedule() {
        let req = BGAppRefreshTaskRequest(identifier: id)
        req.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(req)
    }
}
