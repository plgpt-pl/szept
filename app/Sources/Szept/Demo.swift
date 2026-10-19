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
        func add(_ c: Contact, _ t: String, mine: Bool, ago: TimeInterval, status: Message.Status = .delivered, expires: Bool = false) {
            let m = Message(contact: c, text: t, isMine: mine, sentAt: now.addingTimeInterval(-ago)); m.status = status
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
        ola.lastActivity = now.addingTimeInterval(-120); marek.lastActivity = now.addingTimeInterval(-60)
        try? ctx.save()
    }
}
