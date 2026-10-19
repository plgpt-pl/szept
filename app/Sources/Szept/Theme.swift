import SwiftUI

/// Paleta „Sowa” – wspólna dla zestawu apek PLGPT: granat/fiolet w tle, neonowy cyjan i magenta jako akcenty.
enum Sowa {
    static let cyan = Color(dyn(light: (0x0B, 0x7F, 0xB8), dark: (0x5C, 0xE6, 0xFF)))
    static let magenta = Color(dyn(light: (0x9A, 0x3C, 0xD8), dark: (0xDA, 0x6C, 0xFF)))
    static let violet = Color(dyn(light: (0x5A, 0x45, 0xE6), dark: (0x6B, 0x4D, 0xFF)))
    static let violetDeep = Color(dyn(light: (0x8E, 0x3F, 0xE0), dark: (0x9D, 0x4E, 0xFF)))
    static let glow = Color(dyn(light: (0xE6, 0xF4, 0xFC), dark: (0x14, 0x2A, 0x4A)))
    static let ground = Color(dyn(light: (0xF4, 0xF5, 0xFD), dark: (0x0A, 0x0E, 0x2B)))
    static let groundAlt = Color(dyn(light: (0xF1, 0xEC, 0xFB), dark: (0x1B, 0x10, 0x3C)))
    static let card = Color(dyn(light: (0xFF, 0xFF, 0xFF), dark: (0x14, 0x1A, 0x3E)))
    static let border = Color(dyn(light: (0xDE, 0xE1, 0xF4), dark: (0x2C, 0x35, 0x6E)))
    static let textMuted = Color(dyn(light: (0x5E, 0x66, 0x93), dark: (0xA2, 0xAC, 0xDC)))
    static let ok = Color(dyn(light: (0x1E, 0x8E, 0x5A), dark: (0x4A, 0xE3, 0x9E)))
    static let warn = Color(dyn(light: (0xC2, 0x5A, 0x00), dark: (0xFF, 0xB0, 0x4A)))

    static var backdrop: LinearGradient {
        LinearGradient(colors: [ground, groundAlt], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var userBubble: LinearGradient {
        LinearGradient(colors: [violet, violetDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
    }
    static var action: LinearGradient {
        LinearGradient(colors: [magenta, violet], startPoint: .topLeading, endPoint: .bottomTrailing)
    }

    private static func dyn(light: (Int, Int, Int), dark: (Int, Int, Int)) -> UIColor {
        UIColor { tc in
            let c = tc.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat(c.0) / 255, green: CGFloat(c.1) / 255, blue: CGFloat(c.2) / 255, alpha: 1)
        }
    }
}

struct SzeptLogo: View {
    var size: CGFloat = 44
    var body: some View {
        Image("Logo")
            .resizable().scaledToFit()
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
            .shadow(color: Sowa.cyan.opacity(0.35), radius: size * 0.18)
            .accessibilityHidden(true)
    }
}

struct CardStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .background(Sowa.card, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Sowa.border, lineWidth: 1))
    }
}
extension View { func card() -> some View { modifier(CardStyle()) } }

enum Haptics {
    static func light() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}
