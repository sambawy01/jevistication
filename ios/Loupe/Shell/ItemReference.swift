import LoupeKit
import Photos
import QuickLook
import QuickLookThumbnailing
import SwiftUI
import UniformTypeIdentifiers
import Vision

// Owner rule (2026-09-24): any result that refers to a file must give the user a way to verify it —
// the extracted evidence, or a way to open the file. Every screen that names an item (privacy
// check, Now's findings, mail triage, judgment results, the Unsure queue, the Review queue, the
// Inbox, site checks through mail) shows it with `ItemRefHeader` and offers `ItemActions`: open the
// item itself (Photos asset, or the file through Quick Look — documents and pictures only), share
// it, open a message in Mail (or show the headers to find it), and for privacy findings "Show
// where": evidence re-derived on tap, masked, held only in view state, never written anywhere.

// MARK: - Resolving an item to something the phone can open

/// A message's identifying headers, read from the `.eml` on tap (never stored).
struct MailRef: Equatable {
    var messageId: String?
    var headers: [MailHeader]
    var file: URL?

    /// `message://%3C<id>%3E`: Mail opens the message when it has it; nil without a Message-ID.
    var mailURL: URL? { messageId.flatMap(MailLink.url(messageId:)) }
}

struct MailHeader: Equatable, Hashable {
    let name: String
    let value: String
}

/// What "Open" does for an item.
enum ItemTarget: Equatable {
    case photo(localId: String)
    case file(URL, scope: URL?)
    case mail(MailRef)
    case unavailable(String)
}

protocol ItemResolving {
    func target(for item: SourceItem) -> ItemTarget
}

/// The live resolver: the privacy locator's rules (bookmarked Files locations, the Send to Loupe
/// inbox, PhotoKit), plus the bundled sample (read-only files in the app) and mail files.
struct LiveItemResolver: ItemResolving {
    var locator: PrivacyLocating
    var extraFiles: [String: URL] = [:]

    func target(for item: SourceItem) -> ItemTarget {
        if let url = extraFiles[item.id] { return .file(url, scope: nil) }
        if item.kind == .email || item.id.hasPrefix("mail:") {
            let url = URL(fileURLWithPath: item.path)
            var ref = MailHeaders.read(url, messageIndex: item.messageIndex.map { Int(truncating: $0) })
            if ref.headers.isEmpty { ref.headers = MailHeaders.fromFacts(item) }
            ref.file = item.messageIndex == nil && FileManager.default.fileExists(atPath: url.path) ? url : nil
            return .mail(ref)
        }
        if item.sourceId == SourcesService.sampleId {
            let url = URL(fileURLWithPath: item.path)
            return FileManager.default.fileExists(atPath: url.path) ? .file(url, scope: nil) : .unavailable("The sample file is missing.")
        }
        switch locator.access(for: item.id) {
        case .photo(let id): return .photo(localId: id)
        case .file(let url, let scope): return .file(url, scope: scope)
        case .suggestOnly(let why): return .unavailable(why)
        }
    }
}

extension LiveItemResolver {
    @MainActor static var live: LiveItemResolver {
        #if DEBUG
        LiveItemResolver(locator: PrivacyService.shared.locator, extraFiles: DemoItems.files)
        #else
        LiveItemResolver(locator: PrivacyService.shared.locator)
        #endif
    }
}

/// Quick Look shows documents and pictures only — never executables, scripts, web pages or code.
enum ItemOpenPolicy {
    static let allowed: [UTType] = [
        .image, .pdf, .rtf, .rtfd, .plainText, .utf8PlainText, .commaSeparatedText, .tabSeparatedText,
        .spreadsheet, .presentation, .emailMessage, .vCard,
    ] + ["org.openxmlformats.wordprocessingml.document", "com.microsoft.word.doc", "com.apple.iwork.pages.sffpages",
         "net.daringfireball.markdown", "com.apple.mail.email"].compactMap { UTType($0) }

    static let denied: [UTType] = [
        .executable, .script, .shellScript, .sourceCode, .javaScript, .pythonScript, .rubyScript, .perlScript,
        .phpScript, .appleScript, .html, .xml, .json, .propertyList, .archive, .application, .applicationBundle,
        .package, .bundle, .unixExecutable, .log,
    ]

    static func type(of url: URL) -> UTType? { UTType(filenameExtension: url.pathExtension.lowercased()) }

    static func allows(_ url: URL) -> Bool {
        guard let t = type(of: url) else { return false }
        if denied.contains(where: { t.conforms(to: $0) }) { return false }
        return allowed.contains { t.conforms(to: $0) }
    }
}

enum MailLink {
    /// `message://%3Cid%40host%3E`, the scheme Mail registers for a message by its Message-ID.
    static func url(messageId raw: String) -> URL? {
        let id = raw.trimmingCharacters(in: CharacterSet(charactersIn: "<> \t\r\n"))
        guard !id.isEmpty, let enc = id.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~!$&'()*+,;="))) else { return nil }
        return URL(string: "message://%3C\(enc)%3E")
    }
}

/// The headers needed to find a message: read from the `.eml` (or the n-th message of an mbox)
/// when the user asks, never kept.
enum MailHeaders {
    static let wanted = ["from", "to", "date", "subject", "message-id"]

    static func read(_ url: URL, messageIndex: Int?) -> MailRef {
        guard let h = try? FileHandle(forReadingFrom: url) else { return MailRef(messageId: nil, headers: [], file: nil) }
        defer { try? h.close() }
        let data = (try? h.read(upToCount: messageIndex == nil ? 64 * 1024 : 4 * 1024 * 1024)) ?? Data()
        var text = String(decoding: data, as: UTF8.self)
        if let n = messageIndex {
            // an mbox: messages start at "From " lines
            let parts = text.components(separatedBy: "\nFrom ")
            guard n < parts.count else { return MailRef(messageId: nil, headers: [], file: nil) }
            text = parts[n]
            if let nl = text.firstIndex(of: "\n") { text = String(text[text.index(after: nl)...]) }
        }
        return parse(text)
    }

    static func parse(_ raw: String) -> MailRef {
        let normal = raw.replacingOccurrences(of: "\r\n", with: "\n")
        let block = normal.components(separatedBy: "\n\n").first ?? ""
        var lines: [String] = []
        for line in block.components(separatedBy: "\n") {
            if (line.hasPrefix(" ") || line.hasPrefix("\t")), !lines.isEmpty {
                lines[lines.count - 1] += " " + line.trimmingCharacters(in: .whitespaces)
            } else { lines.append(line) }
        }
        var headers: [MailHeader] = []
        var messageId: String?
        for l in lines {
            guard let colon = l.firstIndex(of: ":") else { continue }
            let name = l[..<colon].trimmingCharacters(in: .whitespaces)
            let value = l[l.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            guard wanted.contains(name.lowercased()), !headers.contains(where: { $0.name.lowercased() == name.lowercased() }) else { continue }
            headers.append(MailHeader(name: name, value: String(value.prefix(300))))
            if name.lowercased() == "message-id" { messageId = value }
        }
        return MailRef(messageId: messageId, headers: headers, file: nil)
    }

    static func fromFacts(_ item: SourceItem) -> [MailHeader] {
        guard let e = item.email else { return [] }
        var out: [MailHeader] = []
        let from = [e.fromName, e.fromAddress.map { "<\($0)>" }].compactMap { $0 }.joined(separator: " ")
        if !from.isEmpty { out.append(MailHeader(name: "From", value: from)) }
        if !e.to.isEmpty { out.append(MailHeader(name: "To", value: e.to.joined(separator: ", "))) }
        if let d = e.date { out.append(MailHeader(name: "Date", value: d.description)) }
        if let s = e.subject { out.append(MailHeader(name: "Subject", value: s)) }
        return out
    }
}

// MARK: - Loading pictures (thumbnails, full images) for photos and image files

enum ItemImages {
    /// A PhotoKit asset's image, on device only (no iCloud download).
    static func photo(_ localId: String, size: CGSize) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localId], options: nil).firstObject else { return nil }
        let o = PHImageRequestOptions()
        o.isNetworkAccessAllowed = false
        o.deliveryMode = .highQualityFormat
        o.resizeMode = .fast
        return await withCheckedContinuation { c in
            PHImageManager.default().requestImage(for: asset, targetSize: size, contentMode: .aspectFit, options: o) { img, _ in
                c.resume(returning: img)
            }
        }
    }

    static func file(_ url: URL, scope: URL?) -> UIImage? {
        let on = scope?.startAccessingSecurityScopedResource() ?? false
        defer { if on { scope?.stopAccessingSecurityScopedResource() } }
        guard let t = ItemOpenPolicy.type(of: url), t.conforms(to: .image) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }

    static func image(for target: ItemTarget, size: CGSize) async -> UIImage? {
        switch target {
        case .photo(let id): return await photo(id, size: size)
        case .file(let url, let scope): return file(url, scope: scope)
        default: return nil
        }
    }

    static func thumbnail(for target: ItemTarget) async -> UIImage? {
        switch target {
        case .photo(let id): return await photo(id, size: CGSize(width: 160, height: 160))
        case .file(let url, let scope):
            guard ItemOpenPolicy.allows(url) else { return nil }
            let on = scope?.startAccessingSecurityScopedResource() ?? false
            defer { if on { scope?.stopAccessingSecurityScopedResource() } }
            let req = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: 80, height: 80), scale: 2, representationTypes: .thumbnail)
            return try? await QLThumbnailGenerator.shared.generateBestRepresentation(for: req).uiImage
        default: return nil
        }
    }
}

/// Id -> item for rows that only hold an item id. Rebuilt at most once a second (a screen renders
/// many rows at once; the items list is the sources' cache, never re-read from disk here).
@MainActor enum ItemIndex {
    private static var map: [String: SourceItem] = [:]
    private static var builtRevision = -1

    /// Rebuilt only when the sources change (their revision). Reading every source is a database
    /// read of all items with their text: doing it on each render, or on each miss, starved the
    /// main thread and held back the privacy check (ReviewPacksUITests timed out).
    static func item(_ id: String?) -> SourceItem? {
        guard let id, !id.isEmpty else { return nil }
        let sources = SourcesService.shared
        if sources.revision != builtRevision {
            map = Dictionary(sources.items().map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            if !map.isEmpty { builtRevision = sources.revision }   // sources not loaded yet: try again next time
        }
        return map[id]
    }
}

// MARK: - Row header: thumbnail, name, source/folder, date

extension SourceItem {
    /// "Photos", "Files · Receipts", "Sample data · documents/identity".
    var sourceAndFolder: String {
        var folder = NameHints.shared.folderOf(item: self)
        // files:<location>/… and mail:<account>/…: the first segment is Loupe's key, not a folder.
        if sourceId == "files" || sourceId == "mail" {
            folder = folder.split(separator: "/").dropFirst().joined(separator: "/")
        }
        let src: String
        switch sourceId {
        case SourcesService.sampleId: src = "Sample data"
        case "photos": src = "Photos"
        case "files": src = "Files"
        case "shared": src = "Send to Loupe"
        case "mail": src = "Mail"
        case "calendar": src = "Calendar"
        case "contacts": src = "Contacts"
        default: src = sourceLabel
        }
        return sourceId == "photos" || folder.isEmpty ? src : "\(src) · \(folder)"
    }

    /// "Modified 2026-09-01 (file modified time)" / "Taken 2026-09-01 (photo EXIF)".
    var dateLine: String? {
        guard let d = dateIso else { return nil }
        let o = dateOrigin
        let verb = o == .exif || o == .photoCreated ? "Taken" : o == .emailHeader ? "Sent"
            : o == .fileModified ? "Modified" : o == .eventStart ? "Starts" : "Dated"
        return "\(verb) \(d)" + (dateOrigin.map { " (\($0.title))" } ?? "")
    }

    var symbol: String {
        let map: [(ItemKind, String)] = [(.image, "photo"), (.pdf, "doc.richtext"), (.email, "envelope"), (.event, "calendar"),
                                         (.contact, "person.crop.circle"), (.csv, "tablecells")]
        return map.first { $0.0 == kind }?.1 ?? "doc.text"
    }
}

struct ItemRefHeader: View {
    let item: SourceItem
    var resolver: ItemResolving? = nil
    @State private var thumb: UIImage?

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(Palette.track)
                if let thumb {
                    Image(uiImage: thumb).resizable().scaledToFill()
                } else {
                    Image(systemName: item.symbol).foregroundStyle(Palette.inkSoft)
                }
            }
            .frame(width: 44, height: 44)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink).lineLimit(1)
                Text(item.sourceAndFolder).font(.caption).foregroundStyle(Palette.inkSoft).lineLimit(1)
                if let d = item.dateLine { Text(d).font(.caption2).foregroundStyle(Palette.inkSoft).lineLimit(1) }
            }
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("item.header")
        .task(id: item.id) {
            let r = resolver ?? LiveItemResolver.live
            thumb = await ItemImages.thumbnail(for: r.target(for: item))
        }
    }
}

// MARK: - Actions: Open, Share, Open in Mail, Show where

/// What the action bar presents.
enum ItemSheet: Identifiable {
    case quickLook(URL, scope: URL?)
    case photo(localId: String)
    case share([Any])
    case headers(MailRef)
    case evidence(PrivacyFinding)

    var id: String {
        switch self {
        case .quickLook(let u, _): return "ql:" + u.path
        case .photo(let id): return "photo:" + id
        case .share: return "share"
        case .headers: return "headers"
        case .evidence(let f): return "ev:" + f.key
        }
    }
}

/// Decides what each button does; a separate type so tests drive it with fakes.
struct ItemActionPlan: Equatable {
    enum Open: Equatable { case quickLook(URL, scope: URL?), photo(String), mail(URL), headers(MailRef), blocked(String) }
    let open: Open
    let share: URL?
    let sharesPhoto: String?
    let headers: MailRef?

    static func make(for item: SourceItem, target: ItemTarget) -> ItemActionPlan {
        switch target {
        case .photo(let id):
            return ItemActionPlan(open: .photo(id), share: nil, sharesPhoto: id, headers: nil)
        case .file(let url, let scope):
            guard ItemOpenPolicy.allows(url) else {
                return ItemActionPlan(open: .blocked("Loupe opens documents and pictures only; this kind of file is not opened."), share: nil, sharesPhoto: nil, headers: nil)
            }
            return ItemActionPlan(open: .quickLook(url, scope: scope), share: url, sharesPhoto: nil, headers: nil)
        case .mail(let ref):
            let open: Open = ref.mailURL.map { .mail($0) } ?? .headers(ref)
            return ItemActionPlan(open: open, share: ref.file, sharesPhoto: nil, headers: ref)
        case .unavailable(let why):
            return ItemActionPlan(open: .blocked(why), share: nil, sharesPhoto: nil, headers: nil)
        }
    }
}

struct ItemActions: View {
    let item: SourceItem
    var finding: PrivacyFinding? = nil
    var resolver: ItemResolving? = nil
    @State private var sheet: ItemSheet?
    @State private var note: String?
    @Environment(\.openURL) private var openURL

    private var plan: ItemActionPlan {
        ItemActionPlan.make(for: item, target: (resolver ?? LiveItemResolver.live).target(for: item))
    }

    var body: some View {
        let p = plan
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Button { open(p) } label: { Label(openTitle(p), systemImage: openSymbol(p)) }
                    .accessibilityIdentifier("item.open")
                if p.share != nil || p.sharesPhoto != nil {
                    Button { Task { await share(p) } } label: { Label("Share", systemImage: "square.and.arrow.up") }
                        .accessibilityIdentifier("item.share")
                }
                if let h = p.headers, case .mail = p.open {
                    Button { sheet = .headers(h) } label: { Label("Headers", systemImage: "list.bullet.rectangle") }
                        .accessibilityIdentifier("item.headers")
                }
                if let f = finding, EvidenceSupport.supports(f.ruleId) {
                    Button { sheet = .evidence(f) } label: { Label("Show where", systemImage: "scope") }
                        .accessibilityIdentifier("item.showWhere.\(f.ruleId)")
                }
            }
            .buttonStyle(.bordered).controlSize(.small).font(.caption.weight(.semibold))
            if let note { Text(note).font(.caption2).foregroundStyle(Palette.inkSoft) }
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .quickLook(let url, let scope): QuickLookSheet(url: url, scope: scope).ignoresSafeArea()
            case .photo(let id): PhotoSheet(localId: id)
            case .share(let items): ShareSheet(items: items)
            case .headers(let ref): MailHeadersSheet(ref: ref)
            case .evidence(let f): ShowWhereView(item: item, finding: f, resolver: resolver ?? LiveItemResolver.live)
            }
        }
    }

    private func openTitle(_ p: ItemActionPlan) -> String {
        switch p.open {
        case .mail: return "Open in Mail"
        case .headers: return "How to find it"
        case .photo: return "Open photo"
        default: return "Open original"
        }
    }

    private func openSymbol(_ p: ItemActionPlan) -> String {
        switch p.open {
        case .mail, .headers: return "envelope.open"
        case .photo: return "photo"
        default: return "eye"
        }
    }

    private func open(_ p: ItemActionPlan) {
        note = nil
        switch p.open {
        case .quickLook(let u, let s): sheet = .quickLook(u, scope: s)
        case .photo(let id): sheet = .photo(localId: id)
        case .mail(let url):
            openURL(url) { ok in if !ok, let h = p.headers { sheet = .headers(h) } }
        case .headers(let ref): sheet = .headers(ref)
        case .blocked(let why): note = why
        }
    }

    private func share(_ p: ItemActionPlan) async {
        if let url = p.share { sheet = .share([url]); return }
        if let id = p.sharesPhoto, let img = await ItemImages.photo(id, size: PHImageManagerMaximumSize) {
            sheet = .share([img])
        } else { note = "The photo is not on this iPhone (iCloud only)." }
    }
}

// MARK: - Presenters

struct QuickLookSheet: UIViewControllerRepresentable {
    let url: URL
    let scope: URL?

    func makeCoordinator() -> Coordinator { Coordinator(url: url, scope: scope) }

    func makeUIViewController(context: Context) -> UINavigationController {
        let ql = QLPreviewController()
        ql.dataSource = context.coordinator
        return UINavigationController(rootViewController: ql)
    }

    func updateUIViewController(_ controller: UINavigationController, context: Context) {}

    static func dismantleUIViewController(_ controller: UINavigationController, coordinator: Coordinator) { coordinator.stop() }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        let scope: URL?
        private var on = false
        init(url: URL, scope: URL?) {
            self.url = url
            self.scope = scope
            on = scope?.startAccessingSecurityScopedResource() ?? false
        }
        func stop() { if on { scope?.stopAccessingSecurityScopedResource(); on = false } }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

struct PhotoSheet: View {
    let localId: String
    @State private var image: UIImage?
    @State private var failed = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if let image { Image(uiImage: image).resizable().scaledToFit() }
                else if failed { Text("The photo is not on this iPhone (iCloud only).").foregroundStyle(Palette.inkSoft) }
                else { ProgressView() }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.black.opacity(0.92))
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .task {
            image = await ItemImages.photo(localId, size: CGSize(width: 2400, height: 2400))
            failed = image == nil
        }
    }
}

struct MailHeadersSheet: View {
    let ref: MailRef
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(ref.headers, id: \.self) { h in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(h.name).font(.caption).foregroundStyle(Palette.inkSoft)
                            Text(h.value).font(Typeface.mono(12)).textSelection(.enabled)
                        }
                    }
                } footer: {
                    Text(ref.mailURL == nil
                         ? "This message has no Message-ID Loupe can hand to Mail. Search Mail for the sender and subject above."
                         : "If Mail did not open it, the account is not in Mail on this iPhone: search for the sender and subject above.")
                }
            }
            .navigationTitle("Find this message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

// MARK: - Show where: evidence re-derived on tap, never stored

enum EvidenceSupport {
    static let rules: Set<String> = ["card_number", "iban", "egypt_national_id", "passport_number", "email", "phone", "contact_list", "payroll_headers"]
    static func supports(_ ruleId: String) -> Bool { rules.contains(ruleId) }
}

/// One recognised line of a picture: its text and Vision's normalised box (origin bottom-left).
struct OCRLine: Equatable {
    let text: String
    let box: CGRect
}

protocol OCRLineReading {
    func lines(in image: CGImage) -> [OCRLine]
}

/// Vision, on device: the same request settings as the Photos source.
struct VisionLineReader: OCRLineReading {
    func lines(in image: CGImage) -> [OCRLine] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        let handler = VNImageRequestHandler(cgImage: image, options: [:])
        do { try handler.perform([request]) } catch { return [] }
        return (request.results ?? []).compactMap { o in
            o.topCandidates(1).first.map { OCRLine(text: $0.string, box: o.boundingBox) }
        }
    }
}

/// A shown piece of evidence: the Kotlin hit, plus where on the picture it is.
struct ShownEvidence: Identifiable {
    let id: Int
    let masked: String
    let brand: String?
    let context: String
    let checks: [String]
    let box: CGRect?
}

/// Derives evidence for a finding. Memory only: the text it reads and the hits it returns are
/// never written to disk, the ledger, defaults or the log.
struct EvidenceDeriver {
    var lineReader: OCRLineReading = VisionLineReader()

    /// Text items: the rules over the item's text. Pictures: OCR again, then map each hit to its line's box.
    func derive(item: SourceItem, ruleId: String, image: CGImage?) -> [ShownEvidence] {
        let ocr = PrivacyCheck.shared.isOcr(item: item)
        if let image {
            let lines = lineReader.lines(in: image)
            if !lines.isEmpty {
                let text = lines.map(\.text).joined(separator: "\n")
                var starts: [Int] = []
                var at = 0
                for l in lines { starts.append(at); at += (l.text as NSString).length + 1 }
                let hits = PrivacyEvidence.shared.findToday(text: text, ruleId: ruleId, ocr: ocr)
                if !hits.isEmpty {
                    return hits.enumerated().map { i, h in
                        let idx = starts.lastIndex { $0 <= Int(h.start) } ?? 0
                        return ShownEvidence(id: i, masked: h.masked, brand: h.brand, context: h.context, checks: h.checks, box: lines[idx].box)
                    }
                }
            }
        }
        let hits = PrivacyEvidence.shared.findToday(text: item.text, ruleId: ruleId, ocr: ocr)
        return hits.enumerated().map { i, h in
            ShownEvidence(id: i, masked: h.masked, brand: h.brand, context: h.context, checks: h.checks, box: nil)
        }
    }
}

struct ShowWhereView: View {
    let item: SourceItem
    let finding: PrivacyFinding
    let resolver: ItemResolving
    var deriver = EvidenceDeriver()
    @State private var image: UIImage?
    @State private var shown: [ShownEvidence] = []
    @State private var loading = true
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ItemRefHeader(item: item, resolver: resolver)
                    Text(finding.title).font(.headline).foregroundStyle(Palette.ink)
                    if let image {
                        EvidenceImage(image: image, boxes: shown.compactMap(\.box))
                    }
                    if loading {
                        HStack { ProgressView(); Text("Looking again…").foregroundStyle(Palette.inkSoft) }
                    } else if shown.isEmpty {
                        Text("Loupe could not find it again: the item may have changed since the check ran. Run the check again.")
                            .font(.subheadline).foregroundStyle(Palette.inkSoft)
                    }
                    ForEach(shown) { e in evidenceCard(e) }
                    Text("Found again just now from the item itself and masked. Nothing shown here is saved.")
                        .font(.caption2).foregroundStyle(Palette.inkSoft)
                }
                .padding(16)
            }
            .background(Palette.ground.ignoresSafeArea())
            .navigationTitle("Show where")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .task { await load() }
        .onDisappear { shown = []; image = nil }
    }

    private func evidenceCard(_ e: ShownEvidence) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(e.masked).font(Typeface.mono(15, weight: .medium)).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("evidence.masked")
                if let b = e.brand { Pill(text: b, color: Palette.blue) }
            }
            Text(e.context).font(Typeface.mono(12)).foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("evidence.context")
            ForEach(e.checks, id: \.self) { c in
                Label(c, systemImage: "checkmark.circle").font(.caption).foregroundStyle(Palette.okText)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("evidence.hit")
    }

    private func load() async {
        loading = true
        let target = resolver.target(for: item)
        var cg: CGImage?
        if item.kind == .image {
            image = await ItemImages.image(for: target, size: CGSize(width: 2400, height: 2400))
            cg = image.flatMap(Self.upright)
        }
        let item = self.item, rule = finding.ruleId, deriver = self.deriver
        let found = await Task.detached(priority: .userInitiated) { deriver.derive(item: item, ruleId: rule, image: cg) }.value
        shown = found
        loading = false
    }

    /// Vision reads the pixels as stored; draw them upright first so the boxes match what is shown.
    static func upright(_ image: UIImage) -> CGImage? {
        if image.imageOrientation == .up { return image.cgImage }
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { _ in image.draw(at: .zero) }.cgImage
    }
}

/// The picture with each evidence line's box drawn over it.
struct EvidenceImage: View {
    let image: UIImage
    let boxes: [CGRect]

    var body: some View {
        Image(uiImage: image).resizable().scaledToFit()
            .accessibilityIdentifier("evidence.image")
            .overlay {
                GeometryReader { g in
                    ForEach(Array(boxes.enumerated()), id: \.offset) { i, b in
                        let r = CGRect(x: b.minX * g.size.width, y: (1 - b.maxY) * g.size.height,
                                       width: b.width * g.size.width, height: b.height * g.size.height).insetBy(dx: -4, dy: -3)
                        RoundedRectangle(cornerRadius: 4).stroke(Palette.red, lineWidth: 3)
                            .frame(width: r.width, height: r.height)
                            .position(x: r.midX, y: r.midY)
                            .accessibilityElement()
                            .accessibilityLabel("Where it is")
                            .accessibilityIdentifier("evidence.box.\(i)")
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

// MARK: - DEBUG: a rendered card photo for the UI test (never in a release build)

#if DEBUG
enum DemoItems {
    static let photoId = "files:loupe-demo/card-photo.png"
    nonisolated(unsafe) static var files: [String: URL] = [:]
    nonisolated(unsafe) static var extra: [SourceItem] = []

    /// -LoupePrivacyPhotoDemo (with -LoupeFixtures): a picture of a test card (Luhn-valid, IIN 4539,
    /// not a real card), in tmp, listed as an OCR'd photo so the privacy check raises it.
    static func installPhotoDemo() {
        let lines = ["LOUPE TEST BANK", "VISA", "4539 1488 0343 6467", "VALID THRU 09/29"]
        let size = CGSize(width: 900, height: 560)
        let img = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            for (i, l) in lines.enumerated() {
                (l as NSString).draw(at: CGPoint(x: 60, y: 60 + i * 110),
                                     withAttributes: [.font: UIFont.monospacedSystemFont(ofSize: 58, weight: .bold), .foregroundColor: UIColor.black])
            }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("card-photo.png")
        try? img.pngData()?.write(to: url)
        files[photoId] = url
        let item = PhoneItems().photo(localId: "loupe-demo", name: "card-photo.png", ocrText: lines.joined(separator: "\n"),
                                      createdIso: "2026-09-24", takenIso: nil, dimensions: "900x560", camera: nil,
                                      hasLocation: false, screenshot: false, sizeBytes: 1000)
        extra = [item.doCopy(id: photoId, sourceId: "files", kind: item.kind, path: url.path, messageIndex: nil, name: item.name,
                             text: item.text, hasText: item.hasText, textTruncated: item.textTruncated, sizeBytes: item.sizeBytes,
                             contentHash: item.contentHash, mime: "image/png", date: item.date, dateOrigin: item.dateOrigin,
                             email: nil, facts: item.facts, duplicateOf: nil)]
    }
}
#endif
