import SwiftUI
import LocalAuthentication

/// Opcjonalna blokada Face ID / kodem po powrocie do apki.
@Observable
final class AppLock {
    var locked = false
    private var leftAt: Date?
    var enabled: Bool {
        get { UserDefaults.standard.bool(forKey: "app_lock") }
        set { UserDefaults.standard.set(newValue, forKey: "app_lock"); if newValue { authenticate() } }
    }

    init() { if enabled { locked = true } }

    func onBackground() { leftAt = Date(); if enabled { locked = true } }
    func onActive() { if enabled && locked { authenticate() } }

    func authenticate() {
        let ctx = LAContext()
        ctx.localizedCancelTitle = String(localized: "Anuluj")
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else { locked = false; return }
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: String(localized: "Odblokuj Szept")) { ok, _ in
            Task { @MainActor in self.locked = !ok }
        }
    }
}

struct LockScreen: View {
    @Environment(AppLock.self) private var lock
    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            VStack(spacing: 20) {
                SzeptLogo(size: 96)
                Text("Szept zablokowany").font(.title2.weight(.semibold))
                Button { lock.authenticate() } label: {
                    Label("Odblokuj", systemImage: "faceid").padding(.horizontal, 8)
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }
}
