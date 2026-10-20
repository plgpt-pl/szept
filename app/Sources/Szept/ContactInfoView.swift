import SwiftUI

struct ContactInfoView: View {
    @Environment(Engine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @Bindable var contact: Contact
    @State private var safety = ""
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Section {
                HStack { Avatar(name: contact.name, size: 56); TextField("Nazwa", text: $contact.name).font(.title3) }
                LabeledContent("Identyfikator") { Text(contact.id).font(.caption.monospaced()).textSelection(.enabled) }
            }
            Section("Numer bezpieczeństwa") {
                Text(safety.isEmpty ? "…" : safety).font(.system(.body, design: .monospaced)).textSelection(.enabled)
                Text("Porównaj ten numer z numerem widocznym u kontaktu (osobiście albo głosowo). Jeśli są identyczne, nikt nie podsłuchuje po drodze – także my jako operator serwera.")
                    .font(.footnote).foregroundStyle(Sowa.textMuted)
                Toggle("Zweryfikowano osobiście", isOn: $contact.verified)
                if contact.identityChanged {
                    Button {
                        engine.acceptNewIdentity(contact, key: contact.identityKey)
                    } label: { Label("Zaakceptuj nowy klucz kontaktu", systemImage: "key.horizontal") }
                        .foregroundStyle(Sowa.warn)
                }
            }
            Section("Znikające wiadomości") {
                Picker("Po czasie", selection: Binding(get: { contact.disappearAfter },
                                                      set: { v in Task { await engine.setDisappear(v, for: contact) } })) {
                    ForEach(Durations.options, id: \.0) { Text($0.1).tag($0.0) }
                }
            }
            Section {
                Button(role: .destructive) { confirmDelete = true } label: { Label("Usuń rozmowę i kontakt", systemImage: "trash") }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Sowa.backdrop.ignoresSafeArea())
        .navigationTitle("Kontakt")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Gotowe") { try? engine.context.save(); dismiss() } } }
        .onAppear { safety = (try? engine.proto.safetyNumber(with: contact.id, identity: contact.identityKey)) ?? String(localized: "niedostępny") }
        .confirmationDialog("Usunąć rozmowę z \(contact.name)? Wiadomości znikną z tego telefonu.", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Usuń", role: .destructive) { engine.delete(contact); dismiss() }
        }
    }
}
