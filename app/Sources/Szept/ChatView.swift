import SwiftUI
import SwiftData
import PhotosUI

struct ChatView: View {
    @Environment(Engine.self) private var engine
    let target: ChatTarget
    @State private var draft = ""
    @State private var photo: PhotosPickerItem?
    @State private var showInfo = false
    @State private var preview: UIImage?

    init(target: ChatTarget) { self.target = target }
    init(contact: Contact) { self.target = .contact(contact) }

    private var contact: Contact? { if case .contact(let c) = target { return c } else { return nil } }
    private var group: ChatGroup? { if case .group(let g) = target { return g } else { return nil } }
    private var messages: [Message] { target.messages.sorted { $0.sentAt < $1.sentAt } }

    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            VStack(spacing: 0) {
                if let contact, contact.identityChanged { identityBanner }
                if let contact, contact.fromUnknown && !contact.verified { unknownBanner }
                if let group, group.members.isEmpty { emptyGroupBanner }
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 6) {
                            ForEach(messages) { m in
                                Bubble(message: m, senderName: (group != nil && !m.isMine) ? engine.senderName(m.senderId) : nil,
                                       onRetry: { Task { await engine.retry(m) } }, onTapImage: { preview = $0 })
                                    .id(m.id)
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                    }
                    .onChange(of: messages.count) { _, _ in
                        if let last = messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                    .onAppear { if let last = messages.last { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
                composer
            }
        }
        .navigationTitle(target.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Picker("Znikające wiadomości", selection: Binding(get: { target.disappearAfter },
                                                                    set: { v in Task { await engine.setDisappear(v, for: target) } })) {
                        ForEach(Durations.options, id: \.0) { Label($0.1, systemImage: "timer").tag($0.0) }
                    }
                    Button { showInfo = true } label: {
                        Label(group == nil ? "Informacje i weryfikacja" : "Członkowie i ustawienia", systemImage: group == nil ? "checkmark.seal" : "person.3")
                    }
                } label: { Image(systemName: target.disappearAfter > 0 ? "timer" : "ellipsis.circle") }
            }
        }
        .sheet(isPresented: $showInfo) {
            NavigationStack {
                if let contact { ContactInfoView(contact: contact) }
                if let group { GroupInfoView(group: group) }
            }
        }
        .fullScreenCover(item: $preview) { img in ImagePreview(image: img) }
        .onAppear { engine.markRead(target) }
        .onChange(of: target.unread) { _, _ in engine.markRead(target) }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task {
                if let d = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: d) {
                    await engine.sendImage(img, to: target)
                }
                photo = nil
            }
        }
    }

    private var emptyGroupBanner: some View {
        HStack {
            Image(systemName: "person.3").foregroundStyle(Sowa.cyan)
            Text("W tej grupie nie ma już nikogo poza Tobą. Dodaj osoby w ustawieniach grupy albo ją opuść.").font(.footnote)
            Spacer()
        }
        .padding(10).background(Sowa.glow)
    }

    private var composer: some View {
        HStack(alignment: .bottom, spacing: 10) {
            PhotosPicker(selection: $photo, matching: .images) {
                Image(systemName: "photo").font(.title2).foregroundStyle(Sowa.cyan).padding(.bottom, 8)
            }
            TextField("Szepnij coś…", text: $draft, axis: .vertical)
                .lineLimit(1...6)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Sowa.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Sowa.border))
            Button {
                let t = draft; draft = ""
                Haptics.light()
                Task { await engine.sendText(t, to: target) }
            } label: {
                Image(systemName: "arrow.up").font(.headline.bold()).foregroundStyle(.white)
                    .frame(width: 38, height: 38).background(Sowa.action, in: Circle())
            }
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .padding(.bottom, 1)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(.ultraThinMaterial)
    }

    private var identityBanner: some View {
        HStack {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Sowa.warn)
            Text("Klucz bezpieczeństwa tego kontaktu się zmienił (nowy telefon albo ktoś się podszywa). Potwierdź numer bezpieczeństwa.")
                .font(.footnote)
            Spacer()
            Button("Sprawdź") { showInfo = true }.font(.footnote.bold())
        }
        .padding(10).background(Sowa.warn.opacity(0.15))
    }

    private var unknownBanner: some View {
        HStack {
            Image(systemName: "person.fill.questionmark").foregroundStyle(Sowa.cyan)
            Text("Ten kontakt napisał pierwszy, nie dodawałeś go kodem QR. Zweryfikuj numer bezpieczeństwa, zanim mu zaufasz.")
                .font(.footnote)
            Spacer()
        }
        .padding(10).background(Sowa.glow)
    }
}

struct Bubble: View {
    let message: Message
    var senderName: String? = nil        // w grupach: kto napisał
    var onRetry: () -> Void
    var onTapImage: (UIImage) -> Void

    private var isNote: Bool { message.senderId == nil && !message.isMine && message.attachmentW == 0 && message.group != nil }

    var body: some View {
        if isNote || (message.group != nil && message.isMine && message.status == .sent && message.text.hasPrefix("Utworzyłeś")) {
            Text(message.text).font(.caption).foregroundStyle(Sowa.textMuted).multilineTextAlignment(.center).padding(.vertical, 4).frame(maxWidth: .infinity)
        } else { bubble }
    }

    private var bubble: some View {
        HStack {
            if message.isMine { Spacer(minLength: 50) }
            VStack(alignment: message.isMine ? .trailing : .leading, spacing: 4) {
                if let senderName, !senderName.isEmpty {
                    Text(senderName).font(.caption.bold()).foregroundStyle(Sowa.cyan).padding(.leading, 4)
                }
                if message.attachmentW > 0 {
                    if let f = message.attachmentFile, let img = UIImage(contentsOfFile: Storage.media.appendingPathComponent(f).path) {
                        Image(uiImage: img).resizable().scaledToFit()
                            .frame(maxWidth: 240, maxHeight: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .onTapGesture { onTapImage(img) }
                    } else {
                        RoundedRectangle(cornerRadius: 14).fill(Sowa.card).frame(width: 200, height: 150)
                            .overlay { if message.text.isEmpty { ProgressView() } else { Text(message.text).font(.caption).padding() } }
                    }
                }
                if !message.text.isEmpty && message.attachmentW == 0 {
                    Text(message.text).textSelection(.enabled)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background { if message.isMine { Sowa.userBubble } else { Sowa.card } }
                        .foregroundStyle(message.isMine ? .white : .primary)
                        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                HStack(spacing: 4) {
                    if message.expiresAt != nil { Image(systemName: "timer").font(.system(size: 9)) }
                    Text(message.sentAt, style: .time).font(.system(size: 10))
                    if message.isMine { statusIcon }
                }
                .foregroundStyle(Sowa.textMuted)
            }
            if !message.isMine { Spacer(minLength: 50) }
        }
    }

    @ViewBuilder private var statusIcon: some View {
        switch message.status {
        case .pending: Image(systemName: "clock").font(.system(size: 9))
        case .sent: Image(systemName: "checkmark").font(.system(size: 9))
        case .delivered: Image(systemName: "checkmark.circle.fill").font(.system(size: 9)).foregroundStyle(Sowa.cyan)
        case .failed: Button(action: onRetry) { Label("ponów", systemImage: "exclamationmark.arrow.circlepath").font(.system(size: 10)).foregroundStyle(Sowa.warn) }
        }
    }
}

extension UIImage: @retroactive Identifiable { public var id: ObjectIdentifier { ObjectIdentifier(self) } }

struct ImagePreview: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            Image(uiImage: image).resizable().scaledToFit()
            Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.title).foregroundStyle(.white).padding() }
        }
    }
}
