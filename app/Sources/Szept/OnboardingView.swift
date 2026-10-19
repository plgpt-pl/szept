import SwiftUI

struct OnboardingView: View {
    @Environment(Engine.self) private var engine
    @State private var name = ""
    @State private var server = Config.defaultServer
    @State private var showAdvanced = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            ScrollView {
                VStack(spacing: 22) {
                    SzeptLogo(size: 120).padding(.top, 40)
                    Text("Szept").font(.system(size: 40, weight: .bold, design: .rounded))
                    Text("Rozmowy, których nikt poza Wami nie przeczyta.")
                        .font(.title3).multilineTextAlignment(.center).foregroundStyle(Sowa.textMuted)

                    VStack(alignment: .leading, spacing: 14) {
                        bullet("person.crop.circle.badge.xmark", "Bez numeru telefonu, e-maila i konta. Twoja tożsamość to klucz wygenerowany na tym telefonie.")
                        bullet("lock.shield", "Szyfrowanie end-to-end protokołem Signal (PQXDH + Double Ratchet, odporność postkwantowa).")
                        bullet("eye.slash", "Serwer nie zna nadawcy ani treści – przechowuje tylko zaszyfrowane koperty do chwili odbioru.")
                        bullet("timer", "Znikające wiadomości, blokada Face ID, zero kopii w chmurze.")
                    }
                    .card()

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Nazwa dla kontaktów (opcjonalnie)").font(.footnote).foregroundStyle(Sowa.textMuted)
                        TextField("np. Adam", text: $name).textFieldStyle(.roundedBorder)
                        DisclosureGroup("Zaawansowane", isExpanded: $showAdvanced) {
                            TextField("Adres serwera", text: $server)
                                .textFieldStyle(.roundedBorder).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                                .padding(.top, 6)
                        }.font(.footnote)
                    }
                    .card()

                    if let error { Text(error).font(.footnote).foregroundStyle(Sowa.warn).multilineTextAlignment(.center) }

                    Button {
                        Task { await create() }
                    } label: {
                        HStack { if busy { ProgressView().tint(.white) }; Text("Utwórz tożsamość").bold() }
                            .frame(maxWidth: .infinity).padding(.vertical, 8)
                    }
                    .buttonStyle(.borderedProminent).tint(Sowa.violet).disabled(busy)
                    .padding(.bottom, 30)
                }
                .padding(.horizontal, 20)
            }
        }
    }

    private func bullet(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title3).foregroundStyle(Sowa.cyan).frame(width: 28)
            Text(text).font(.subheadline)
        }
    }

    private func create() async {
        busy = true; defer { busy = false }
        error = nil
        engine.displayName = name.trimmingCharacters(in: .whitespaces)
        engine.serverURL = server.trimmingCharacters(in: .whitespaces)
        do { try await engine.register(); Haptics.success() } catch { self.error = error.localizedDescription; Haptics.error() }
    }
}
