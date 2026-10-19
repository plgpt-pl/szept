import Foundation

enum Config {
    /// Domyślny serwer-skrzynka (własny, za Cloudflare Tunnel). Można zmienić w Ustawieniach.
    static let defaultServer = "https://szept.plgpt.pl"
    static let linkScheme = "szept"
    static let preKeyBatch = 40
    static let preKeyLowWater = 10
    static let maxImageSide: CGFloat = 1600
}
