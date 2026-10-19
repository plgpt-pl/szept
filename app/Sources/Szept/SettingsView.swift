import SwiftUI

struct SettingsView: View {
    @Environment(Engine.self) private var engine
    @Environment(AppLock.self) private var lock
    @Environment(\.dismiss) private var dismiss
    @AppStorage("appearance") private var appearance = "dark"
    @State private var name = ""
    @State private var server = ""
    @State private var serverOK: Bool?
    @State private var confirmWipe = false
    @State private var keyCounts = ""

    var body: some View {
        @Bindable var lock = lock
        Form {
            Section("Ja") {
                TextField("Nazwa dla kontaktów", text: $name).onChange(of: name) { _, v in engine.displayName = v }
                LabeledContent("Mój identyfikator") { Text(engine.accountId ?? "-").font(.caption.monospaced()).textSelection(.enabled) }
                LabeledContent("Klucze jednorazowe na serwerze") { Text(keyCounts) }
            }
            Section("Bezpieczeństwo") {
                Toggle("Blokada Face ID / kodem", isOn: $lock.enabled)
                Picker("Wygląd", selection: $appearance) {
                    Text("Ciemny").tag("dark"); Text("Jasny").tag("light"); Text("Systemowy").tag("system")
                }
            }
            Section("Serwer") {
                TextField("Adres", text: $server).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                HStack {
                    Button("Zapisz i sprawdź") {
                        engine.serverURL = server
                        Task { serverOK = (try? await engine.api.health()) ?? false; engine.connect() }
                    }
                    Spacer()
                    if let ok = serverOK { Image(systemName: ok ? "checkmark.circle.fill" : "xmark.octagon.fill").foregroundStyle(ok ? Sowa.ok : Sowa.warn) }
                }
                Text("Serwer widzi tylko: Twój losowy identyfikator, klucze publiczne i zaszyfrowane koperty do chwili odbioru. Nie zna nadawcy, treści ani Twoich kontaktów.")
                    .font(.footnote).foregroundStyle(Sowa.textMuted)
            }
            Section("Co wie kto") {
                row("Treść wiadomości", "tylko Ty i odbiorca")
                row("Kto z kim pisze", "nikt (koperta HPKE, wysyłka anonimowa)")
                row("Kiedy odbierasz", "serwer (konieczne do doręczenia)")
                row("Adres IP", "Cloudflare i serwer na czas połączenia, nie zapisywany")
                row("Powiadomienia push", "brak – nic nie idzie przez Apple")
            }
            Section {
                Button(role: .destructive) { confirmWipe = true } label: { Label("Usuń konto i wszystkie dane", systemImage: "trash") }
            } footer: {
                Text("Szept \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "") · libsignal (AGPL) · kod źródłowy otwarty")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Sowa.backdrop.ignoresSafeArea())
        .navigationTitle("Ustawienia")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Gotowe") { dismiss() } } }
        .onAppear {
            name = engine.displayName; server = engine.serverURL
            Task { if let (a, b) = try? await engine.api.keyCounts() { keyCounts = "\(a) / \(b)" } }
        }
        .confirmationDialog("Usunąć konto? Klucze, kontakty i wiadomości znikną bezpowrotnie z tego telefonu i z serwera.",
                            isPresented: $confirmWipe, titleVisibility: .visible) {
            Button("Usuń wszystko", role: .destructive) { Task { await engine.deleteEverything() } }
        }
    }

    private func row(_ a: String, _ b: String) -> some View {
        VStack(alignment: .leading, spacing: 2) { Text(a).font(.subheadline); Text(b).font(.footnote).foregroundStyle(Sowa.textMuted) }
    }
}
