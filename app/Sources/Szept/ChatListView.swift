import SwiftUI
import SwiftData

struct ChatListView: View {
    @Environment(Engine.self) private var engine
    @Query(sort: \Contact.lastActivity, order: .reverse) private var contacts: [Contact]
    @Query(sort: \ChatGroup.lastActivity, order: .reverse) private var groups: [ChatGroup]
    @State private var showAdd = false
    @State private var showNewGroup = false
    @State private var showSettings = false
    @State private var leaving: ChatGroup?
    @State private var query = ""

    /// Wiersze listy: przypięte na górze, potem wg ostatniej aktywności; przy wyszukiwaniu – pasujące nazwą.
    private var rows: [ChatTarget] {
        let all = (contacts.map { ChatTarget.contact($0) } + groups.map { ChatTarget.group($0) })
            .sorted { a, b in a.pinned != b.pinned ? a.pinned : a.lastActivity > b.lastActivity }
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? all : all.filter { $0.name.localizedStandardContains(q) }
    }

    private var hits: [Message] { engine.searchMessages(query) }

    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            if contacts.allSatisfy({ $0.id == engine.accountId }) && groups.isEmpty {
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
                    Section {
                        ForEach(rows) { row in
                            NavigationLink(value: row.id) {
                                switch row {
                                case .contact(let c): ContactRow(contact: c)
                                case .group(let g): GroupRow(group: g)
                                }
                            }
                            .listRowBackground(Sowa.card)
                            .swipeActions(edge: .leading) {
                                Button { engine.togglePin(row) } label: {
                                    Label(row.pinned ? "Odepnij" : "Przypnij", systemImage: row.pinned ? "pin.slash" : "pin")
                                }.tint(Sowa.violet)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) { remove(row) } label: { Label("Usuń", systemImage: "trash") }
                                Button { engine.setMute(row, until: row.isMuted ? nil : .distantFuture) } label: {
                                    Label(row.isMuted ? "Włącz dźwięk" : "Wycisz", systemImage: row.isMuted ? "bell" : "bell.slash")
                                }.tint(.gray)
                            }
                            .contextMenu { ChatMenu(target: row) }
                        }
                    }
                    if !hits.isEmpty {
                        Section("Wiadomości") {
                            ForEach(hits) { m in
                                if let t = engine.target(of: m) {
                                    NavigationLink(value: t.id) { SearchHitRow(message: m, target: t) }
                                        .listRowBackground(Sowa.card)
                                }
                            }
                        }
                    }
                }
                .scrollContentBackground(.hidden)
                .searchable(text: $query, prompt: Text("Szukaj"))
                .navigationDestination(for: String.self) { id in
                    if id.hasPrefix("g:"), let g = engine.group(id: String(id.dropFirst(2))) { ChatView(target: .group(g)) }
                    else if let c = engine.contact(id: id) { ChatView(target: .contact(c)) }
                }
                .confirmationDialog("Opuścić grupę „\(leaving?.name ?? "")”?", isPresented: Binding(get: { leaving != nil }, set: { if !$0 { leaving = nil } }), titleVisibility: .visible) {
                    Button("Opuść", role: .destructive) { if let g = leaving { Task { await engine.leaveGroup(g) } }; leaving = nil }
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
                Menu {
                    Button { showAdd = true } label: { Label("Dodaj kontakt", systemImage: "qrcode") }
                    Button { showNewGroup = true } label: { Label("Nowa grupa", systemImage: "person.3") }
                        .disabled(contacts.allSatisfy { $0.id == engine.accountId })
                } label: { Image(systemName: "plus.circle.fill") }
            }
        }
        .sheet(isPresented: $showAdd) { NavigationStack { AddContactView() } }
        .onAppear { if CommandLine.arguments.contains("-demo-add") { showAdd = true } }
        .sheet(isPresented: $showNewGroup) { NavigationStack { NewGroupView() } }
        .sheet(isPresented: $showSettings) { NavigationStack { SettingsView() } }
    }

    private func remove(_ row: ChatTarget) {
        switch row { case .contact(let c): engine.delete(c); case .group(let g): leaving = g }
    }
}

/// Menu rozmowy: przypnij, wycisz na czas, włącz dźwięk (lista rozmów i czat).
struct ChatMenu: View {
    @Environment(Engine.self) private var engine
    let target: ChatTarget
    var body: some View {
        Button { engine.togglePin(target) } label: {
            Label(target.pinned ? "Odepnij" : "Przypnij", systemImage: target.pinned ? "pin.slash" : "pin")
        }
        if target.isMuted {
            Button { engine.setMute(target, until: nil) } label: { Label("Włącz dźwięk", systemImage: "bell") }
        } else {
            Menu {
                Button("1 godzina") { engine.setMute(target, until: Date().addingTimeInterval(3600)) }
                Button("8 godzin") { engine.setMute(target, until: Date().addingTimeInterval(8 * 3600)) }
                Button("1 tydzień") { engine.setMute(target, until: Date().addingTimeInterval(7 * 86400)) }
                Button("Zawsze") { engine.setMute(target, until: .distantFuture) }
            } label: { Label("Wycisz", systemImage: "bell.slash") }
        }
    }
}

/// Pinezka i wyciszenie przy nazwie rozmowy.
struct RowBadges: View {
    let target: ChatTarget
    var body: some View {
        if target.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(Sowa.textMuted) }
        if target.isMuted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(Sowa.textMuted) }
    }
}

/// Podgląd ostatniej wiadomości albo „pisze…”.
struct PreviewText: View {
    @Environment(Engine.self) private var engine
    let target: ChatTarget
    let fallback: String
    var body: some View {
        if let typing = engine.typingLabel(in: target) {
            Text(typing).font(.subheadline.italic()).foregroundStyle(Sowa.cyan).lineLimit(1)
        } else {
            Text(fallback).font(.subheadline).foregroundStyle(Sowa.textMuted).lineLimit(1)
        }
    }
}

struct SearchHitRow: View {
    let message: Message
    let target: ChatTarget
    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(target.name).font(.subheadline.weight(.semibold))
                Spacer()
                Text(message.sentAt, format: .dateTime.day().month().hour().minute()).font(.caption2).foregroundStyle(Sowa.textMuted)
            }
            Text(message.text).font(.subheadline).foregroundStyle(Sowa.textMuted).lineLimit(2)
        }
        .padding(.vertical, 2)
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
        switch engine.connection { case .online: return String(localized: "połączono"); case .connecting: return String(localized: "łączenie…"); case .offline: return String(localized: "offline") }
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
                    RowBadges(target: .contact(contact))
                    if contact.verified { Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(Sowa.ok) }
                    if contact.identityChanged { Image(systemName: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(Sowa.warn) }
                    Spacer()
                    Text(contact.lastActivity, style: .relative).font(.caption2).foregroundStyle(Sowa.textMuted)
                }
                HStack {
                    PreviewText(target: .contact(contact), fallback: preview)
                    Spacer()
                    if contact.disappearAfter > 0 { Image(systemName: "timer").font(.caption2).foregroundStyle(Sowa.textMuted) }
                    if contact.unread > 0 {
                        Text("\(contact.unread)").font(.caption2.bold()).padding(.horizontal, 7).padding(.vertical, 2)
                            .background(ChatTarget.contact(contact).isMuted ? AnyShapeStyle(Color.gray) : AnyShapeStyle(Sowa.action), in: Capsule()).foregroundStyle(.white)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
    private var preview: String {
        guard let last = contact.messages.max(by: { $0.sentAt < $1.sentAt }) else { return String(localized: "Brak wiadomości") }
        return (last.isMine ? String(localized: "Ty: ") : "") + last.summary
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
