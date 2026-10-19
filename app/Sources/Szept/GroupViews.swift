import SwiftUI
import SwiftData

/// Wybór kontaktów (do nowej grupy albo dodania osób).
struct ContactPicker: View {
    @Query(sort: \Contact.name) private var contacts: [Contact]
    @Binding var selected: Set<String>
    var excluding: Set<String> = []

    var body: some View {
        let list = contacts.filter { !excluding.contains($0.id) }
        if list.isEmpty {
            Text("Brak kontaktów do dodania. Najpierw dodaj kogoś kodem QR.").foregroundStyle(Sowa.textMuted)
        }
        ForEach(list) { c in
            Button {
                if selected.contains(c.id) { selected.remove(c.id) } else { selected.insert(c.id) }
            } label: {
                HStack {
                    Avatar(name: c.name, size: 36)
                    Text(c.name).foregroundStyle(.primary)
                    if c.verified { Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(Sowa.ok) }
                    Spacer()
                    Image(systemName: selected.contains(c.id) ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(selected.contains(c.id) ? AnyShapeStyle(Sowa.action) : AnyShapeStyle(Sowa.textMuted))
                }
            }
        }
    }
}

struct NewGroupView: View {
    @Environment(Engine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var selected = Set<String>()
    @State private var busy = false

    var body: some View {
        Form {
            Section("Nazwa grupy") { TextField("np. Rodzina", text: $name) }
            Section("Członkowie") { ContactPicker(selected: $selected) }
            Section {
                Text("Grupa istnieje tylko w telefonach członków. Serwer nie zna jej nazwy, składu ani tego, że istnieje – każda wiadomość jest szyfrowana osobno dla każdej osoby.")
                    .font(.footnote).foregroundStyle(Sowa.textMuted)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Sowa.backdrop.ignoresSafeArea())
        .navigationTitle("Nowa grupa")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Utwórz") {
                    busy = true
                    let members = selected.compactMap { engine.contact(id: $0) }
                    Task { await engine.createGroup(name: name, members: members); dismiss() }
                }
                .disabled(selected.isEmpty || busy)
            }
        }
    }
}

struct GroupInfoView: View {
    @Environment(Engine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @Bindable var group: ChatGroup
    @State private var name = ""
    @State private var showAdd = false
    @State private var toAdd = Set<String>()
    @State private var confirmLeave = false

    var body: some View {
        Form {
            Section {
                HStack {
                    GroupAvatar(size: 56)
                    TextField("Nazwa grupy", text: $name).font(.title3)
                        .onSubmit { Task { await engine.renameGroup(group, to: name) } }
                }
                LabeledContent("Identyfikator") { Text(group.id).font(.caption.monospaced()).textSelection(.enabled) }
            }
            Section("Członkowie (\(group.members.count + 1))") {
                HStack { Avatar(name: engine.displayName.isEmpty ? "Ty" : engine.displayName, size: 36); Text("Ty").foregroundStyle(Sowa.textMuted) }
                ForEach(engine.memberContacts(group)) { c in
                    NavigationLink { ContactInfoView(contact: c) } label: {
                        HStack {
                            Avatar(name: c.name, size: 36)
                            Text(c.name)
                            if c.verified { Image(systemName: "checkmark.seal.fill").font(.caption).foregroundStyle(Sowa.ok) }
                            if c.identityChanged { Image(systemName: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(Sowa.warn) }
                            if c.fromUnknown && !c.verified { Image(systemName: "person.fill.questionmark").font(.caption).foregroundStyle(Sowa.cyan) }
                        }
                    }
                }
                Button { toAdd = []; showAdd = true } label: { Label("Dodaj osoby", systemImage: "person.badge.plus") }
                Text("Osoby, których nie dodawałeś(aś) sam(a), trafiły tu z zaproszenia innego członka. Zweryfikuj ich numer bezpieczeństwa, zanim im zaufasz.")
                    .font(.footnote).foregroundStyle(Sowa.textMuted)
            }
            Section("Znikające wiadomości") {
                Picker("Po czasie", selection: Binding(get: { group.disappearAfter },
                                                      set: { v in Task { await engine.setDisappear(v, for: group) } })) {
                    ForEach(Durations.options, id: \.0) { Text($0.1).tag($0.0) }
                }
            }
            Section {
                Button(role: .destructive) { confirmLeave = true } label: { Label("Opuść grupę", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Sowa.backdrop.ignoresSafeArea())
        .navigationTitle("Grupa")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Gotowe") { Task { await engine.renameGroup(group, to: name) }; dismiss() }
            }
        }
        .onAppear { name = group.name }
        .sheet(isPresented: $showAdd) {
            NavigationStack {
                Form { Section("Dodaj do grupy") { ContactPicker(selected: $toAdd, excluding: Set(group.members)) } }
                    .scrollContentBackground(.hidden).background(Sowa.backdrop.ignoresSafeArea())
                    .navigationTitle("Dodaj osoby").navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { showAdd = false } }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Dodaj") {
                                let cs = toAdd.compactMap { engine.contact(id: $0) }
                                showAdd = false
                                Task { await engine.addMembers(cs, to: group) }
                            }.disabled(toAdd.isEmpty)
                        }
                    }
            }
            .presentationDetents([.medium, .large])
        }
        .confirmationDialog("Opuścić grupę „\(group.name)”? Wiadomości znikną z tego telefonu, a członkowie zobaczą, że wyszedłeś(aś).",
                            isPresented: $confirmLeave, titleVisibility: .visible) {
            Button("Opuść", role: .destructive) { Task { await engine.leaveGroup(group) }; dismiss() }
        }
    }
}

struct GroupAvatar: View {
    var size: CGFloat = 40
    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [Sowa.violet.opacity(0.8), Sowa.cyan.opacity(0.6)], startPoint: .topLeading, endPoint: .bottomTrailing))
            Image(systemName: "person.3.fill").font(.system(size: size * 0.4)).foregroundStyle(.white)
        }
        .frame(width: size, height: size)
    }
}

struct GroupRow: View {
    @Environment(Engine.self) private var engine
    let group: ChatGroup
    var body: some View {
        HStack(spacing: 12) {
            GroupAvatar(size: 44)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(group.name).font(.headline)
                    Text("\(group.members.count + 1) os.").font(.caption2).foregroundStyle(Sowa.textMuted)
                    Spacer()
                    Text(group.lastActivity, style: .relative).font(.caption2).foregroundStyle(Sowa.textMuted)
                }
                HStack {
                    Text(preview).font(.subheadline).foregroundStyle(Sowa.textMuted).lineLimit(1)
                    Spacer()
                    if group.disappearAfter > 0 { Image(systemName: "timer").font(.caption2).foregroundStyle(Sowa.textMuted) }
                    if group.unread > 0 {
                        Text("\(group.unread)").font(.caption2.bold()).padding(.horizontal, 7).padding(.vertical, 2)
                            .background(Sowa.action, in: Capsule()).foregroundStyle(.white)
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
    private var preview: String {
        guard let last = group.messages.max(by: { $0.sentAt < $1.sentAt }) else { return "Brak wiadomości" }
        let who = last.isMine ? "Ty" : (last.senderId.map { engine.senderName($0) } ?? "")
        let body = (last.attachmentFile != nil || last.attachmentW > 0) ? "📷 Zdjęcie" : last.text
        return who.isEmpty ? body : "\(who): \(body)"
    }
}
