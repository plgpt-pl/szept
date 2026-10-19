import SwiftUI
import SwiftData

struct ChatListView: View {
    @Environment(Engine.self) private var engine
    @Query(sort: \Contact.lastActivity, order: .reverse) private var contacts: [Contact]
    @State private var showAdd = false
    @State private var showSettings = false

    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            if contacts.isEmpty {
                VStack(spacing: 16) {
                    SzeptLogo(size: 90)
                    Text("Nikogo tu jeszcze nie ma").font(.title3.weight(.semibold))
                    Text("Dodaj kontakt, pokazując swój kod QR albo skanując cudzy. Bez numerów, bez listy znajomych na serwerze.")
                        .multilineTextAlignment(.center).foregroundStyle(Sowa.textMuted).padding(.horizontal, 30)
                    Button { showAdd = true } label: { Label("Dodaj kontakt", systemImage: "qrcode").padding(.horizontal, 6) }
                        .buttonStyle(.borderedProminent).tint(Sowa.violet)
                }
            } else {
                List {
                    ForEach(contacts) { c in
                        NavigationLink(value: c.id) { ContactRow(contact: c) }
                            .listRowBackground(Sowa.card)
                    }
                    .onDelete { idx in idx.map { contacts[$0] }.forEach(engine.delete) }
                }
                .scrollContentBackground(.hidden)
                .navigationDestination(for: String.self) { id in
                    if let c = engine.contact(id: id) { ChatView(contact: c) }
                }
            }
        }
        .navigationTitle("Szept")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showSettings = true } label: { Image(systemName: "gearshape") }
            }
            ToolbarItem(placement: .principal) { ConnectionDot() }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showAdd = true } label: { Image(systemName: "plus.circle.fill") }
            }
        }
        .sheet(isPresented: $showAdd) { NavigationStack { AddContactView() } }
        .sheet(isPresented: $showSettings) { NavigationStack { SettingsView() } }
    }
}

struct ConnectionDot: View {
    @Environment(Engine.self) private var engine
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.caption).foregroundStyle(Sowa.textMuted)
        }
    }
    private var color: Color {
        switch engine.connection { case .online: return Sowa.ok; case .connecting: return Sowa.warn; case .offline: return .gray }
    }
    private var label: String {
        switch engine.connection { case .online: return "połączono"; case .connecting: return "łączenie…"; case .offline: return "offline" }
    }
}

struct ContactRow: View {
    let contact: Contact
    var body: some View {
        HStack(spacing: 12) {
            Avatar(name: contact.name, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(contact.name).font(.headline)
                    if contact.verified { Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(Sowa.ok) }
                    if contact.identityChanged { Image(systemName: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(Sowa.warn) }
                    Spacer()
                    Text(contact.lastActivity, style: .relative).font(.caption2).foregroundStyle(Sowa.textMuted)
                }
                HStack {
                    Text(preview).font(.subheadline).foregroundStyle(Sowa.textMuted).lineLimit(1)
                    Spacer()
                    if contact.disappearAfter > 0 { Image(systemName: "timer").font(.caption2).foregroundStyle(Sowa.textMuted) }
                    if contact.unread > 0 {
                        Text("\(contact.unread)").font(.caption2.bold()).padding(.horizontal, 7).padding(.vertical, 2)
                            .background(Sowa.action, in: Capsule()).foregroundStyle(.white)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
    private var preview: String {
        guard let last = contact.messages.max(by: { $0.sentAt < $1.sentAt }) else { return "Brak wiadomości" }
        if last.attachmentFile != nil || last.attachmentW > 0 { return (last.isMine ? "Ty: " : "") + "📷 Zdjęcie" }
        return (last.isMine ? "Ty: " : "") + last.text
    }
}

struct Avatar: View {
    let name: String
    var size: CGFloat = 40
    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [Sowa.cyan.opacity(0.7), Sowa.magenta.opacity(0.7)], startPoint: .topLeading, endPoint: .bottomTrailing))
            Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.45, weight: .bold, design: .rounded)).foregroundStyle(.white)
        }
        .frame(width: size, height: size)
    }
}
