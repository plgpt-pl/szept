import Foundation
import SwiftData

/// Tryb `-demo` (zrzuty ekranu w symulatorze): udawane konto i przykładowa rozmowa, bez sieci.
@MainActor
enum Demo {
    static func prepare() {
        Keychain.set("demo-token", account: "token")
        Keychain.set("DEMOxxxxxxxxxxxxxxxxxx", account: "account_id")
        UserDefaults.standard.set("Adam", forKey: "display_name")
    }

    static func seed(_ e: Engine) {
        let ctx = e.context
        let key = e.proto.identityKey.serialize()
        let ola = Contact(id: "OLAxxxxxxxxxxxxxxxxxxx", name: "Ola", identityKey: key); ola.verified = true
        let marek = Contact(id: "MAREKxxxxxxxxxxxxxxxxx", name: "Marek", identityKey: key); marek.disappearAfter = 3600; marek.unread = 2
        ctx.insert(ola); ctx.insert(marek)
        let now = Date()
        func add(_ c: Contact, _ t: String.LocalizationValue, mine: Bool, ago: TimeInterval, status: Message.Status = .delivered, expires: Bool = false) {
            let m = Message(contact: c, text: String(localized: t), isMine: mine, sentAt: now.addingTimeInterval(-ago)); m.status = status
            if expires { m.expiresAt = now.addingTimeInterval(3600 - ago) }
            ctx.insert(m)
        }
        add(ola, "Cześć! Działa Ci już Szept?", mine: false, ago: 3600)
        add(ola, "Tak, właśnie zweryfikowałem Twój numer bezpieczeństwa 🙂", mine: true, ago: 3500)
        add(ola, "Super. Serwer naprawdę nic nie widzi?", mine: false, ago: 3400)
        add(ola, "Tylko zaszyfrowane koperty, bez nadawcy. Treść zostaje między nami.", mine: true, ago: 3300)
        add(ola, "To przerzucam tu całą rozmowę o projekcie.", mine: false, ago: 120)
        add(marek, "Wysyłam Ci dokumenty, ustaw znikanie po godzinie", mine: false, ago: 900, expires: true)
        add(marek, "Ustawione, po godzinie znikną u nas obu.", mine: true, ago: 800, status: .sent, expires: true)
        add(marek, "Dzięki!", mine: false, ago: 60, expires: true)
        // 0.3: odpowiedź, reakcje, głosówka, plik
        func media(_ name: String, bytes: Int) -> String {
            let f = "demo-\(name)"; try? Data(count: bytes).write(to: Storage.media.appendingPathComponent(f)); return f
        }
        let q = Message(contact: ola, text: String(localized: "Wyślesz mi plan na jutro?"), isMine: false, sentAt: now.addingTimeInterval(-600))
        q.status = .delivered; ctx.insert(q)
        let pdf = Message(contact: ola, text: "", isMine: true, sentAt: now.addingTimeInterval(-540)); pdf.status = .delivered
        pdf.kindRaw = "file"; pdf.fileName = "Plan-projektu.pdf"; pdf.mimeType = "application/pdf"; pdf.replyToId = q.id
        pdf.attachmentFile = media("plan.pdf", bytes: 248_000); pdf.reactionsJSON = #"{"OLAxxxxxxxxxxxxxxxxxxx":"❤️"}"#; ctx.insert(pdf)
        let voice = Message(contact: ola, text: "", isMine: false, sentAt: now.addingTimeInterval(-300)); voice.status = .delivered
        voice.kindRaw = "audio"; voice.mimeType = "audio/mp4"; voice.duration = 14; voice.attachmentFile = media("voice.m4a", bytes: 30_000); ctx.insert(voice)
        let ok = Message(contact: ola, text: String(localized: "Odsłuchane, wszystko jasne 👍"), isMine: true, sentAt: now.addingTimeInterval(-240))
        ok.status = .delivered; ok.reactionsJSON = #"{"OLAxxxxxxxxxxxxxxxxxxx":"🔥"}"#; ctx.insert(ok)

        let kasia = Contact(id: "KASIAxxxxxxxxxxxxxxxxx", name: "Kasia", identityKey: key); kasia.verified = true
        let tomek = Contact(id: "TOMEKxxxxxxxxxxxxxxxxx", name: "Tomek", identityKey: key)
        ctx.insert(kasia); ctx.insert(tomek)
        add(kasia, "Do zobaczenia w sobotę!", mine: false, ago: 7200)
        add(tomek, "Podeślij mi link do Szeptu, dodam Mamę", mine: false, ago: 86_000)
        kasia.lastActivity = now.addingTimeInterval(-7200); tomek.lastActivity = now.addingTimeInterval(-86_000)

        let g = ChatGroup(id: "DEMOGROUP", name: String(localized: "Rodzina 🏡"), members: [ola.id, kasia.id, tomek.id]); g.unread = 3
        ctx.insert(g)
        func gadd(_ from: Contact?, _ t: String.LocalizationValue, ago: TimeInterval) {
            let m = Message(group: g, text: String(localized: t), isMine: from == nil, sentAt: now.addingTimeInterval(-ago))
            m.senderId = from?.id; m.status = .delivered; ctx.insert(m)
        }
        gadd(kasia, "Kto robi zakupy na niedzielę?", ago: 1800)
        gadd(nil, "Ja, wyślijcie listę", ago: 1700)
        gadd(ola, "Ja przywiozę owoce z działki 🍓", ago: 1500)
        gadd(kasia, "Super, to widzimy się o 14", ago: 900)
        gadd(tomek, "Ciasto biorę na siebie 🎂", ago: 200)
        if let cake = g.messages.first(where: { $0.senderId == tomek.id }) ?? nil {
            cake.reactionsJSON = #"{"OLAxxxxxxxxxxxxxxxxxxx":"😋","KASIAxxxxxxxxxxxxxxxxx":"❤️"}"#
        }
        g.lastActivity = now.addingTimeInterval(-200)

        ola.lastActivity = now.addingTimeInterval(-240); marek.lastActivity = now.addingTimeInterval(-60)
        try? ctx.save()
    }
}
