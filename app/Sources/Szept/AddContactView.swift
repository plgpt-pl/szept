import SwiftUI
import CoreImage.CIFilterBuiltins
import VisionKit

struct AddContactView: View {
    @Environment(Engine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    @State private var mode = 0
    @State private var pasted = ""
    @State private var scanned: ContactLink?
    @State private var error: String?

    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            VStack(spacing: 16) {
                Picker("", selection: $mode) { Text("Mój kod").tag(0); Text("Skanuj").tag(1) }.pickerStyle(.segmented)
                if mode == 0 { myCode } else { scanner }
                Spacer()
            }
            .padding()
        }
        .navigationTitle("Dodaj kontakt")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Zamknij") { dismiss() } } }
        .sheet(item: $scanned) { link in NavigationStack { AddContactConfirm(link: link) }.presentationDetents([.medium]) }
    }

    private var myCode: some View {
        VStack(spacing: 14) {
            if let link = engine.myLink {
                QRView(text: link.url.absoluteString).frame(width: 240, height: 240)
                    .padding(12).background(.white, in: RoundedRectangle(cornerRadius: 16))
                Text("Pokaż ten kod osobie, z którą chcesz rozmawiać, albo wyślij link innym kanałem. Kod zawiera Twój identyfikator i klucz publiczny – nic więcej.")
                    .font(.footnote).foregroundStyle(Sowa.textMuted).multilineTextAlignment(.center)
                HStack {
                    ShareLink(item: link.url.absoluteString) { Label("Udostępnij link", systemImage: "square.and.arrow.up") }
                    Button { UIPasteboard.general.string = link.url.absoluteString; Haptics.success() } label: { Label("Kopiuj", systemImage: "doc.on.doc") }
                }
                .buttonStyle(.bordered)
                Text("ID: \(link.accountId)").font(.caption2.monospaced()).foregroundStyle(Sowa.textMuted)
            }
        }
        .card()
    }

    private var scanner: some View {
        VStack(spacing: 14) {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                QRScanner { text in
                    if let l = ContactLink(string: text) { scanned = l; Haptics.success() } else { error = String(localized: "To nie jest kod Szept.") }
                }
                .frame(height: 300).clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                Text("Skaner niedostępny na tym urządzeniu – wklej link.").font(.footnote).foregroundStyle(Sowa.textMuted)
            }
            HStack {
                TextField("szept://add?…", text: $pasted).textFieldStyle(.roundedBorder).autocorrectionDisabled().textInputAutocapitalization(.never)
                Button("Dodaj") {
                    if let l = ContactLink(string: pasted) { scanned = l } else { error = String(localized: "Niepoprawny link.") }
                }.buttonStyle(.borderedProminent).tint(Sowa.violet)
            }
            if let error { Text(error).font(.footnote).foregroundStyle(Sowa.warn) }
        }
        .card()
    }
}

struct AddContactConfirm: View {
    @Environment(Engine.self) private var engine
    @Environment(\.dismiss) private var dismiss
    let link: ContactLink
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        ZStack {
            Sowa.backdrop.ignoresSafeArea()
            VStack(spacing: 16) {
                Avatar(name: name.isEmpty ? (link.name ?? "?") : name, size: 64)
                TextField("Nazwa kontaktu", text: $name).textFieldStyle(.roundedBorder)
                    .onAppear { name = link.name ?? "" }
                Text("ID: \(link.accountId)").font(.caption.monospaced()).foregroundStyle(Sowa.textMuted)
                if let error { Text(error).font(.footnote).foregroundStyle(Sowa.warn) }
                Button {
                    do { try engine.addContact(link, name: name); Haptics.success(); dismiss() } catch { self.error = error.localizedDescription }
                } label: { Text("Dodaj kontakt").bold().frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).tint(Sowa.violet)
            }
            .padding()
        }
        .navigationTitle("Nowy kontakt")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Anuluj") { dismiss() } } }
    }
}

struct QRView: View {
    let text: String
    var body: some View {
        if let img = Self.make(text) { Image(uiImage: img).interpolation(.none).resizable().scaledToFit() }
    }
    static func make(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8); f.correctionLevel = "M"
        guard let out = f.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

struct QRScanner: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    func makeCoordinator() -> Coord { Coord(onCode: onCode) }
    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
                                           isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        try? vc.startScanning()
        return vc
    }
    func updateUIViewController(_ vc: DataScannerViewController, context: Context) {}
    final class Coord: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        var last = ""
        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }
        func dataScanner(_ s: DataScannerViewController, didAdd added: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in added { if case .barcode(let b) = item, let v = b.payloadStringValue, v != last { last = v; onCode(v) } }
        }
    }
}
