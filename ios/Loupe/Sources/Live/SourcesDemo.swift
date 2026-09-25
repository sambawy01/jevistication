#if DEBUG
import Foundation
import UIKit

/// DEBUG only (never in a release build): fixture pictures for the Photos source, so the live scan display can
/// be tested, screenshotted and recorded without real photos. `-LoupeFixtures -LoupePhotosDemo` swaps PhotoKit for
/// `FixturePhotoLibrary`; Vision still reads the rendered pictures for real, so the text, boxes and counts on
/// screen are what OCR found. `-LoupeScanDemo photos|files|calendar|contacts` turns that source on at launch.
enum SourcesDemo {
    private static let args = ProcessInfo.processInfo.arguments

    static let photos: Bool = args.contains("-LoupePhotosDemo") && LaunchOptions.current.fixtureMode

    /// A source to turn on (and so scan) when the app opens.
    static let autoScan: PhoneSource? = {
        guard LaunchOptions.current.fixtureMode, let i = args.firstIndex(of: "-LoupeScanDemo"), i + 1 < args.count else { return nil }
        return PhoneSource(rawValue: args[i + 1])
    }()

    /// Seconds between fixture photos, so the scan runs long enough to be watched (Vision's own time comes on top).
    static var photoPace: TimeInterval { photos ? 0.35 : 0 }

    static func photoLibrary() -> PhotoLibraryReading? { photos ? FixturePhotoLibrary() : nil }
}

/// Rendered pictures standing in for a photo library: receipts, a boarding pass, a Wi-Fi note, a chat screenshot,
/// a business card, and a few pictures with no text at all. Numbers in them are test values (the card number is
/// the Luhn-valid test card the privacy demo uses, not a real card).
final class FixturePhotoLibrary: PhotoLibraryReading {
    private var status: PhonePermission = .notAsked
    private let lock = NSLock()
    private var cache: [String: Data] = [:]

    struct Picture {
        let name: String
        let size: CGSize
        let lines: [String]
        let style: Style
        enum Style { case paper, screen, card, scene(Int) }
    }

    static let pictures: [Picture] = [
        Picture(name: "IMG_4101.JPG", size: CGSize(width: 720, height: 1040), lines: [
            "FRESH BASKET MARKET", "12 Nile St, Zamalek", "", "Oat milk        2.10", "Sourdough       3.80",
            "Blueberries     4.50", "Eggs x12        2.00", "", "TOTAL          12.40", "VISA 4539 1488 0343 6467", "Thank you!"], style: .paper),
        Picture(name: "IMG_4102.JPG", size: CGSize(width: 1080, height: 810), lines: [], style: .scene(0)),
        Picture(name: "IMG_4103.PNG", size: CGSize(width: 640, height: 1180), lines: [
            "Messages", "Mum", "Call me when you land", "Here's the number:", "+44 7700 900123", "Love you x"], style: .screen),
        Picture(name: "IMG_4104.JPG", size: CGSize(width: 1080, height: 620), lines: [
            "BOARDING PASS", "LHR  →  CAI", "FLIGHT BA 155   25 SEP", "GATE B32   SEAT 14A", "BOARDING 09:40"], style: .card),
        Picture(name: "IMG_4105.JPG", size: CGSize(width: 900, height: 900), lines: [
            "Wi-Fi: Loupe-Guest", "password = hunter2-sunrise", "Router in the hall cupboard"], style: .paper),
        Picture(name: "IMG_4106.JPG", size: CGSize(width: 1080, height: 810), lines: [], style: .scene(1)),
        Picture(name: "IMG_4107.JPG", size: CGSize(width: 720, height: 1000), lines: [
            "THAMES WATER", "Account 88213904417", "Amount due  £48.20", "Pay by 12 Oct 2026", "Direct Debit"], style: .paper),
        Picture(name: "IMG_4108.JPG", size: CGSize(width: 1000, height: 600), lines: [
            "Dr Salma Hany", "Paediatrics", "salma.hany@example.com", "Clinic 3, Floor 2"], style: .card),
        Picture(name: "IMG_4109.JPG", size: CGSize(width: 1080, height: 810), lines: [], style: .scene(2)),
        Picture(name: "IMG_4110.JPG", size: CGSize(width: 760, height: 980), lines: [
            "PENALTY CHARGE NOTICE", "PCN AB12345678", "Contravention 01", "Pay £35 within 14 days"], style: .paper),
        Picture(name: "IMG_4111.PNG", size: CGSize(width: 640, height: 1180), lines: [
            "Calendar", "Dentist", "Tue 30 Sep 16:15", "Bring the referral letter"], style: .screen),
        Picture(name: "IMG_4112.JPG", size: CGSize(width: 1080, height: 810), lines: [], style: .scene(3)),
        Picture(name: "IMG_4113.JPG", size: CGSize(width: 900, height: 700), lines: [
            "CAFE MENU", "Flat white   3.20", "Mint tea     2.60", "Cardamom bun 2.90"], style: .card),
        Picture(name: "IMG_4114.JPG", size: CGSize(width: 720, height: 1000), lines: [
            "WARRANTY", "Dishwasher DW-60", "Serial 5519 2044 7781", "Valid until Mar 2028"], style: .paper),
        Picture(name: "IMG_4115.JPG", size: CGSize(width: 1080, height: 810), lines: [], style: .scene(4)),
        Picture(name: "IMG_4116.JPG", size: CGSize(width: 1000, height: 640), lines: [
            "PARCEL READY", "Locker 14, Code 7731", "Collect by Friday"], style: .card),
    ]

    func authorization() -> PhonePermission { lock.lock(); defer { lock.unlock() }; return status }
    func requestAuthorization() async -> PhonePermission { lock.lock(); status = .granted; lock.unlock(); return .granted }

    func allAssets() -> [PhotoAssetRecord] {
        Self.pictures.enumerated().map { i, p in
            PhotoAssetRecord(localId: "fixture-\(i)", fileName: p.name, created: Date(timeIntervalSince1970: 1_790_000_000 - Double(i) * 3600),
                             isScreenshot: { if case .screen = p.style { return true } else { return false } }(),
                             pixelWidth: Int(p.size.width), pixelHeight: Int(p.size.height), hasLocation: false)
        }
    }

    func changes(since token: Data) -> PhotoChanges? { PhotoChanges(updated: [], deleted: []) }
    func currentToken() -> Data? { Data("fixture".utf8) }

    func imageData(localId: String) async -> Data? {
        guard let i = Int(localId.dropFirst("fixture-".count)), i < Self.pictures.count else { return nil }
        lock.lock()
        if let d = cache[localId] { lock.unlock(); return d }
        lock.unlock()
        let d = Self.render(Self.pictures[i])
        lock.lock(); cache[localId] = d; lock.unlock()
        return d
    }

    // MARK: Rendering (content pictures, not UI: their colours are the pictures' own)

    static func render(_ p: Picture) -> Data {
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        let img = UIGraphicsImageRenderer(size: p.size, format: fmt).image { ctx in
            let c = ctx.cgContext
            let r = CGRect(origin: .zero, size: p.size)
            switch p.style {
            case .paper:
                UIColor(white: 0.97, alpha: 1).setFill(); c.fill(r)
                draw(p.lines, in: r.insetBy(dx: 50, dy: 70), size: p.size.width / 20, color: UIColor(white: 0.1, alpha: 1), mono: true)
            case .screen:
                UIColor(white: 0.98, alpha: 1).setFill(); c.fill(r)
                UIColor(red: 0.2, green: 0.45, blue: 0.95, alpha: 1).setFill()
                c.fill(CGRect(x: 0, y: 0, width: r.width, height: 120))
                draw(p.lines, in: r.insetBy(dx: 40, dy: 40), size: 40, color: UIColor(white: 0.08, alpha: 1), mono: false)
            case .card:
                let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
                                   colors: [UIColor(red: 0.93, green: 0.95, blue: 1, alpha: 1).cgColor,
                                            UIColor(red: 0.82, green: 0.87, blue: 0.97, alpha: 1).cgColor] as CFArray, locations: nil)!
                c.drawLinearGradient(g, start: .zero, end: CGPoint(x: r.width, y: r.height), options: [])
                draw(p.lines, in: r.insetBy(dx: 60, dy: 60), size: p.size.width / 18, color: UIColor(red: 0.05, green: 0.1, blue: 0.25, alpha: 1), mono: false)
            case .scene(let n):
                scene(n, in: c, r)
            }
        }
        return img.jpegData(compressionQuality: 0.85) ?? Data()
    }

    private static func draw(_ lines: [String], in r: CGRect, size: CGFloat, color: UIColor, mono: Bool) {
        let font = mono ? UIFont.monospacedSystemFont(ofSize: size, weight: .semibold) : UIFont.systemFont(ofSize: size, weight: .bold)
        var y = r.minY
        for l in lines {
            (l as NSString).draw(at: CGPoint(x: r.minX, y: y), withAttributes: [.font: font, .foregroundColor: color])
            y += size * 1.45
        }
    }

    /// A text-free picture: a sky, a sun and hills, in one of five palettes.
    private static func scene(_ n: Int, in c: CGContext, _ r: CGRect) {
        let skies: [(UIColor, UIColor)] = [
            (UIColor(red: 0.98, green: 0.62, blue: 0.35, alpha: 1), UIColor(red: 0.45, green: 0.2, blue: 0.5, alpha: 1)),
            (UIColor(red: 0.45, green: 0.75, blue: 0.98, alpha: 1), UIColor(red: 0.85, green: 0.93, blue: 1, alpha: 1)),
            (UIColor(red: 0.1, green: 0.15, blue: 0.35, alpha: 1), UIColor(red: 0.4, green: 0.3, blue: 0.6, alpha: 1)),
            (UIColor(red: 0.95, green: 0.8, blue: 0.5, alpha: 1), UIColor(red: 0.98, green: 0.5, blue: 0.4, alpha: 1)),
            (UIColor(red: 0.5, green: 0.85, blue: 0.8, alpha: 1), UIColor(red: 0.2, green: 0.5, blue: 0.6, alpha: 1)),
        ]
        let (top, bottom) = skies[n % skies.count]
        let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [top.cgColor, bottom.cgColor] as CFArray, locations: nil)!
        c.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: r.height), options: [])
        UIColor(white: 1, alpha: 0.85).setFill()
        c.fillEllipse(in: CGRect(x: r.width * (0.2 + 0.12 * CGFloat(n)), y: r.height * 0.18, width: r.height * 0.22, height: r.height * 0.22))
        for k in 0..<3 {
            let shade = 0.18 + 0.12 * CGFloat(k)
            UIColor(red: shade * 0.6, green: shade + 0.1, blue: shade * 0.8, alpha: 1).setFill()
            let path = UIBezierPath()
            let base = r.height * (0.62 + 0.12 * CGFloat(k))
            path.move(to: CGPoint(x: 0, y: r.height))
            path.addLine(to: CGPoint(x: 0, y: base))
            for x in stride(from: 0, through: r.width, by: 40) {
                path.addLine(to: CGPoint(x: x, y: base - sin((x / r.width) * .pi * CGFloat(2 + k) + CGFloat(n)) * 60))
            }
            path.addLine(to: CGPoint(x: r.width, y: r.height))
            path.fill()
        }
    }
}
#endif
