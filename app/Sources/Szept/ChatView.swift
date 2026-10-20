import SwiftUI
import SwiftData
import PhotosUI
import QuickLook

struct ChatView: View {
    @Environment(Engine.self) private var engine
    let target: ChatTarget
    @State private var draft = ""
    @State private var photo: PhotosPickerItem?
    @State private var showPhotos = false
    @State private var showFiles = false
    @State private var showAttach = false
    @State private var showInfo = false
    @State private var preview: UIImage?
    @State private var replyTo: Message?
    @State private var editing: Message?
    @State private var quickLook: URL?
    @State private var customEmojiFor: Message?
    @State private var customEmoji = ""
    @State private var scrollTo: String?
    @State private var recorder = VoiceRecorder()
    @FocusState private var focused: Bool

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
                                Bubble(message: m,
                                       senderName: (group != nil && !m.isMine) ? engine.senderName(m.senderId) : nil,
                                       quoted: m.replyToId.flatMap { engine.messageBy(id: $0, in: target) },
                                       quotedName: { quotedName($0) },
                                       myReaction: engine.myReaction(on: m),
                                       actions: actions(for: m))
                                    .id(m.id)
                            }
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                    }
                    .onChange(of: messages.count) { _, _ in
                        if let last = messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                    .onChange(of: scrollTo) { _, id in
                        if let id { withAnimation { proxy.scrollTo(id, anchor: .center) }; scrollTo = nil }
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
                        Label(group == nil ? String(localized: "Informacje i weryfikacja") : String(localized: "Członkowie i ustawienia"), systemImage: group == nil ? "checkmark.seal" : "person.3")
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
        .quickLookPreview($quickLook)
        .photosPicker(isPresented: $showPhotos, selection: $photo, matching: .images)
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.item]) { result in
            guard case .success(let url) = result else { return }
            let reply = replyTo?.id; replyTo = nil
            Task { await engine.sendFile(url, to: target, replyTo: reply) }
        }
        .confirmationDialog("Załącz", isPresented: $showAttach, titleVisibility: .hidden) {
            Button("Zdjęcie") { showPhotos = true }
            Button("Plik") { showFiles = true }
            Button("Anuluj", role: .cancel) {}
        }
        .alert("Reakcja", isPresented: Binding(get: { customEmojiFor != nil }, set: { if !$0 { customEmojiFor = nil } })) {
            TextField("Emoji", text: $customEmoji)
            Button("Dodaj") {
                if let m = customEmojiFor { let e = customEmoji.trimmingCharacters(in: .whitespaces); Task { await engine.react(e.isEmpty ? nil : e, to: m) } }
                customEmoji = ""; customEmojiFor = nil
            }
            Button("Anuluj", role: .cancel) { customEmoji = ""; customEmojiFor = nil }
        } message: { Text("Wpisz emoji, którym chcesz zareagować.") }
        .onAppear { engine.markRead(target) }
        .onDisappear { AudioPlayer.shared.stop(); if recorder.isRecording { recorder.cancel() } }
        .onChange(of: target.unread) { _, _ in engine.markRead(target) }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            let reply = replyTo?.id; replyTo = nil
            Task {
                if let d = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: d) {
                    await engine.sendImage(img, to: target, replyTo: reply)
                }
                photo = nil
            }
        }
    }

    private func quotedName(_ m: Message) -> String {
        if m.isMine { return String(localized: "Ty") }
        if group != nil { return engine.senderName(m.senderId) }
        return contact?.name ?? ""
    }

    private func actions(for m: Message) -> BubbleActions {
        BubbleActions(
            onRetry: { Task { await engine.retry(m) } },
            onTapImage: { preview = $0 },
            onReply: { editing = nil; replyTo = m; focused = true },
            onReact: { e in Task { await engine.react(e, to: m) } },
            onCustomReact: { customEmojiFor = m },
            onEdit: { replyTo = nil; editing = m; draft = m.text; focused = true },
            onDeleteAll: { Task { await engine.deleteForEveryone(m) } },
            onDeleteLocal: { if editing?.id == m.id { editing = nil; draft = "" }; if replyTo?.id == m.id { replyTo = nil }; engine.deleteLocally(m) },
            onTapQuote: { scrollTo = $0 },
            onOpenFile: { if let u = engine.exportURL(for: m) { quickLook = u } },
            exportURL: { engine.exportURL(for: m) })
    }

    // MARK: pasek wpisywania

    private var composer: some View {
        VStack(spacing: 0) {
            if let r = replyTo { composerBar(icon: "arrowshape.turn.up.left", title: String(localized: "Odpowiedź do: \(quotedName(r))"), text: r.summary) { replyTo = nil } }
            if let e = editing { composerBar(icon: "pencil", title: String(localized: "Edycja wiadomości"), text: e.text) { editing = nil; draft = "" } }
            HStack(alignment: .bottom, spacing: 10) {
                if recorder.isRecording {
                    recordingView
                } else {
                    Button { showAttach = true } label: {
                        Image(systemName: "paperclip").font(.title2).foregroundStyle(Sowa.cyan).padding(.bottom, 8)
                    }
                    .disabled(editing != nil)
                    TextField(editing == nil ? String(localized: "Szepnij coś…") : String(localized: "Nowa treść…"), text: $draft, axis: .vertical)
                        .lineLimit(1...6)
                        .focused($focused)
                        .padding(.horizontal, 14).padding(.vertical, 9)
                        .background(Sowa.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(Sowa.border))
                }
                if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && editing == nil {
                    micButton
                } else {
                    Button {
                        let t = draft; draft = ""
                        Haptics.light()
                        if let e = editing { editing = nil; Task { await engine.edit(e, text: t) } }
                        else { let reply = replyTo?.id; replyTo = nil; Task { await engine.sendText(t, to: target, replyTo: reply) } }
                    } label: {
                        Image(systemName: editing == nil ? "arrow.up" : "checkmark").font(.headline.bold()).foregroundStyle(.white)
                            .frame(width: 38, height: 38).background(Sowa.action, in: Circle())
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .padding(.bottom, 1)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
        .background(.ultraThinMaterial)
    }

    private func composerBar(icon: String, title: String, text: String, close: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon).foregroundStyle(Sowa.cyan)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(.caption.bold()).foregroundStyle(Sowa.cyan)
                Text(text).font(.caption).foregroundStyle(Sowa.textMuted).lineLimit(1)
            }
            Spacer()
            Button(action: close) { Image(systemName: "xmark.circle.fill").foregroundStyle(Sowa.textMuted) }
        }
        .padding(.horizontal, 14).padding(.top, 8)
    }

    private var recordingView: some View {
        HStack(spacing: 10) {
            Circle().fill(Color.red).frame(width: 10, height: 10)
            Text(Durations.clock(recorder.elapsed)).font(.body.monospacedDigit())
            Text(recorder.cancelling ? String(localized: "Puść, żeby anulować") : String(localized: "◀︎ przesuń, by anulować")).font(.footnote).foregroundStyle(recorder.cancelling ? Sowa.warn : Sowa.textMuted)
            Spacer()
        }
        .padding(.horizontal, 14).padding(.vertical, 9)
        .background(Sowa.card, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    /// Przytrzymanie nagrywa, puszczenie wysyła, przesunięcie w lewo anuluje.
    private var micButton: some View {
        Image(systemName: recorder.isRecording ? "mic.fill" : "mic")
            .font(.headline.bold()).foregroundStyle(.white)
            .frame(width: 38, height: 38)
            .background(recorder.isRecording ? AnyShapeStyle(Color.red) : AnyShapeStyle(Sowa.action), in: Circle())
            .scaleEffect(recorder.isRecording ? 1.25 : 1)
            .animation(.easeInOut(duration: 0.15), value: recorder.isRecording)
            .padding(.bottom, 1)
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        if !recorder.isRecording { if recorder.start() { Haptics.light() } }
                        recorder.cancelling = v.translation.width < -80
                    }
                    .onEnded { v in
                        guard recorder.isRecording else { return }
                        let cancel = v.translation.width < -80
                        guard let (url, dur) = recorder.stop() else { return }
                        if cancel || dur < 0.5 { try? FileManager.default.removeItem(at: url); return }
                        Haptics.light()
                        let reply = replyTo?.id; replyTo = nil
                        Task { await engine.sendAudio(url, duration: dur, to: target, replyTo: reply); try? FileManager.default.removeItem(at: url) }
                    }
            )
    }

    // MARK: banery

    private var emptyGroupBanner: some View {
        HStack {
            Image(systemName: "person.3").foregroundStyle(Sowa.cyan)
            Text("W tej grupie nie ma już nikogo poza Tobą. Dodaj osoby w ustawieniach grupy albo ją opuść.").font(.footnote)
            Spacer()
        }
        .padding(10).background(Sowa.glow)
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

struct BubbleActions {
    var onRetry: () -> Void
    var onTapImage: (UIImage) -> Void
    var onReply: () -> Void
    var onReact: (String?) -> Void
    var onCustomReact: () -> Void
    var onEdit: () -> Void
    var onDeleteAll: () -> Void
    var onDeleteLocal: () -> Void
    var onTapQuote: (String) -> Void
    var onOpenFile: () -> Void
    var exportURL: () -> URL?
}

struct Bubble: View {
    let message: Message
    var senderName: String? = nil        // w grupach: kto napisał
    var quoted: Message? = nil
    var quotedName: (Message) -> String = { _ in "" }
    var myReaction: String? = nil
    var actions: BubbleActions
    @State private var dragX: CGFloat = 0

    static let quickEmoji = ["👍", "❤️", "😂", "😮", "😢", "🙏"]

    private var isNote: Bool { message.senderId == nil && !message.isMine && !message.hasAttachment && !message.wasDeleted && message.group != nil }

    var body: some View {
        if isNote || (message.group != nil && message.isMine && message.status == .sent && message.text.hasPrefix(String(localized: "Utworzyłeś(aś)")) || message.text.hasPrefix("Utworzyłeś(aś)")) {
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
                if let quoted { quote(quoted) }
                content
                if !message.reactions.isEmpty { reactions }
                HStack(spacing: 4) {
                    if message.expiresAt != nil { Image(systemName: "timer").font(.system(size: 9)) }
                    if message.editedAt != nil && !message.wasDeleted { Text("(edytowano)").font(.system(size: 10)) }
                    Text(message.sentAt, style: .time).font(.system(size: 10))
                    if message.isMine { statusIcon }
                }
                .foregroundStyle(Sowa.textMuted)
            }
            .offset(x: dragX)
            .gesture(
                DragGesture(minimumDistance: 25)
                    .onChanged { v in
                        guard !message.wasDeleted, v.translation.width > 0, abs(v.translation.width) > abs(v.translation.height) * 1.5 else { return }
                        dragX = min(v.translation.width, 70)
                    }
                    .onEnded { _ in
                        if dragX > 50 { Haptics.light(); actions.onReply() }
                        withAnimation(.spring(duration: 0.25)) { dragX = 0 }
                    }
            )
            .contextMenu { menu }
            if !message.isMine { Spacer(minLength: 50) }
        }
    }

    @ViewBuilder private var content: some View {
        if message.wasDeleted {
            Text("Wiadomość usunięta").italic().foregroundStyle(Sowa.textMuted)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background(Sowa.card.opacity(0.6)).clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        } else if message.isImage {
            if let f = message.attachmentFile, let img = UIImage(contentsOfFile: Storage.media.appendingPathComponent(f).path) {
                Image(uiImage: img).resizable().scaledToFit()
                    .frame(maxWidth: 240, maxHeight: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .onTapGesture { actions.onTapImage(img) }
            } else {
                RoundedRectangle(cornerRadius: 14).fill(Sowa.card).frame(width: 200, height: 150)
                    .overlay { if message.text.isEmpty { ProgressView() } else { Text(message.text).font(.caption).padding() } }
            }
        } else if message.kind == .audio {
            AudioBubble(message: message, mine: message.isMine)
        } else if message.kind == .file {
            fileBubble
        } else if !message.text.isEmpty {
            Text(message.text).textSelection(.enabled)
                .padding(.horizontal, 14).padding(.vertical, 9)
                .background { if message.isMine { Sowa.userBubble } else { Sowa.card } }
                .foregroundStyle(message.isMine ? .white : .primary)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }

    private var fileBubble: some View {
        HStack(spacing: 10) {
            Image(systemName: MimeTypes.icon(for: message)).font(.title2).foregroundStyle(message.isMine ? .white : Sowa.cyan)
            VStack(alignment: .leading, spacing: 2) {
                Text(message.fileName ?? String(localized: "Plik")).font(.subheadline.bold()).lineLimit(2)
                if let f = message.attachmentFile,
                   let size = try? FileManager.default.attributesOfItem(atPath: Storage.media.appendingPathComponent(f).path)[.size] as? Int {
                    Text(MimeTypes.size(size)).font(.caption)
                } else if !message.text.isEmpty { Text(message.text).font(.caption) } else { ProgressView().controlSize(.small) }
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .frame(maxWidth: 260, alignment: .leading)
        .background { if message.isMine { Sowa.userBubble } else { Sowa.card } }
        .foregroundStyle(message.isMine ? .white : .primary)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .onTapGesture { if message.attachmentFile != nil { actions.onOpenFile() } }
    }

    private func quote(_ q: Message) -> some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 2).fill(Sowa.cyan).frame(width: 3)
            VStack(alignment: .leading, spacing: 1) {
                Text(quotedName(q)).font(.caption.bold()).foregroundStyle(Sowa.cyan)
                Text(q.summary).font(.caption).foregroundStyle(Sowa.textMuted).lineLimit(2)
            }
        }
        .padding(8).frame(maxWidth: 260, alignment: .leading)
        .background(Sowa.card.opacity(0.7), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .onTapGesture { actions.onTapQuote(q.id) }
    }

    private struct ReactionCount: Identifiable { let emoji: String; let count: Int; var id: String { emoji } }

    private var reactionCounts: [ReactionCount] {
        var counts: [String: Int] = [:]
        for e in message.reactions.values { counts[e, default: 0] += 1 }
        return counts.map { ReactionCount(emoji: $0.key, count: $0.value) }
            .sorted { a, b in a.count != b.count ? a.count > b.count : a.emoji < b.emoji }
    }

    private var reactions: some View {
        HStack(spacing: 4) {
            ForEach(reactionCounts) { rc in reactionPill(rc) }
        }
    }

    private func reactionPill(_ rc: ReactionCount) -> some View {
        let mine = myReaction == rc.emoji
        let label: String = rc.count > 1 ? "\(rc.emoji) \(rc.count)" : rc.emoji
        return Text(label).font(.caption)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(mine ? Sowa.glow : Sowa.card, in: Capsule())
            .overlay(Capsule().stroke(mine ? Sowa.cyan : Sowa.border))
            .onTapGesture { actions.onReact(mine ? nil : rc.emoji) }
    }

    @ViewBuilder private var menu: some View {
        if !message.wasDeleted {
            ForEach(Bubble.quickEmoji, id: \.self) { e in
                Button { actions.onReact(myReaction == e ? nil : e) } label: { Text(myReaction == e ? "\(e) ✓" : e) }
            }
            Button { actions.onCustomReact() } label: { Label("Inna reakcja…", systemImage: "face.smiling") }
            Divider()
            Button { actions.onReply() } label: { Label("Odpowiedz", systemImage: "arrowshape.turn.up.left") }
            if message.kind == .text && !message.text.isEmpty {
                Button { UIPasteboard.general.string = message.text } label: { Label("Kopiuj", systemImage: "doc.on.doc") }
            }
            if message.hasAttachment, message.attachmentFile != nil, let url = actions.exportURL() {
                ShareLink(item: url) { Label("Udostępnij", systemImage: "square.and.arrow.up") }
            }
            if message.isMine && message.kind == .text {
                Button { actions.onEdit() } label: { Label("Edytuj", systemImage: "pencil") }
            }
            if message.isMine {
                Button(role: .destructive) { actions.onDeleteAll() } label: { Label("Usuń u wszystkich", systemImage: "trash") }
            }
        }
        Button(role: .destructive) { actions.onDeleteLocal() } label: { Label("Usuń u mnie", systemImage: "trash.slash") }
    }

    @ViewBuilder private var statusIcon: some View {
        switch message.status {
        case .pending: Image(systemName: "clock").font(.system(size: 9))
        case .sent: Image(systemName: "checkmark").font(.system(size: 9))
        case .delivered: Image(systemName: "checkmark.circle.fill").font(.system(size: 9)).foregroundStyle(Sowa.cyan)
        case .failed: Button(action: actions.onRetry) { Label("ponów", systemImage: "exclamationmark.arrow.circlepath").font(.system(size: 10)).foregroundStyle(Sowa.warn) }
        }
    }
}

struct AudioBubble: View {
    let message: Message
    let mine: Bool
    private var player: AudioPlayer { AudioPlayer.shared }
    private var isCurrent: Bool { player.currentFile != nil && player.currentFile == message.attachmentFile }

    var body: some View {
        HStack(spacing: 10) {
            Button {
                if let f = message.attachmentFile { player.toggle(file: f) }
            } label: {
                Image(systemName: isCurrent && player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.headline).frame(width: 34, height: 34)
                    .background(mine ? Color.white.opacity(0.2) : Sowa.glow, in: Circle())
            }
            .disabled(message.attachmentFile == nil)
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: isCurrent ? player.progress : 0).tint(mine ? .white : Sowa.cyan)
                HStack {
                    Image(systemName: "mic.fill").font(.system(size: 10))
                    Text(isCurrent && player.isPlaying ? Durations.clock(player.progress * message.duration) : Durations.clock(message.duration)).font(.caption.monospacedDigit())
                    if message.attachmentFile == nil { ProgressView().controlSize(.mini) }
                }
            }
            .frame(width: 150)
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background { if mine { Sowa.userBubble } else { Sowa.card } }
        .foregroundStyle(mine ? .white : .primary)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
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
