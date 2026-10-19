import SwiftUI
import SwiftData

@main
struct SzeptApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var engine = Engine.shared
    @State private var lock = AppLock()
    @AppStorage("appearance") private var appearance = "dark"
    @State private var pendingLink: ContactLink?

    var body: some Scene {
        WindowGroup {
            Group {
                if engine.isRegistered { MainView(pendingLink: $pendingLink) } else { OnboardingView() }
            }
            .environment(engine)
            .environment(lock)
            .overlay { if lock.locked { LockScreen() } }
            .preferredColorScheme(appearance == "system" ? nil : (appearance == "light" ? .light : .dark))
            .tint(Sowa.cyan)
            .onOpenURL { url in pendingLink = ContactLink(string: url.absoluteString) }
            .modelContainer(engine.container)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: lock.onActive(); engine.start(); if engine.connection != .online { engine.connect() }
            case .background: lock.onBackground()
            default: break
            }
        }
    }
}

struct MainView: View {
    @Environment(Engine.self) private var engine
    @Binding var pendingLink: ContactLink?
    @State private var path = NavigationPath()
    var body: some View {
        NavigationStack(path: $path) {
            ChatListView()
        }
        .onAppear { if CommandLine.arguments.contains("-demo-open") { path.append("OLAxxxxxxxxxxxxxxxxxxx") } }
        .sheet(item: $pendingLink) { link in
            NavigationStack { AddContactConfirm(link: link) }
                .presentationDetents([.medium])
        }
        .onAppear { Engine.requestNotifications() }
    }
}

extension ContactLink: Identifiable { var id: String { accountId } }
