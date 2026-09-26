import os
import UIKit

/// The Loupe keyboard (2026-09-26): English and Arabic letters, numbers and symbols, and a Loupe strip
/// that checks what is on the clipboard or was just pasted, **on this iPhone only**.
///
/// - Without Full Access it types normally and the strip says "Allow Full Access to check pasted links".
/// - With Full Access it asks iOS whether the clipboard holds a link (`detectPatterns`, no prompt) each
///   time the clipboard changes; the strip then offers "Copied a link · tap to check it". A tap reads it
///   (iOS may ask "Allow Paste", like any app: measured in the simulator, a keyboard's read prompts too,
///   and it blocks the reading thread until answered, so the read runs off the main thread) and runs
///   `KeyboardCheck` (the shared phishing formula and the lists already in the App Group). It keeps the copied text in memory only while the keyboard is
///   up, to tell "Pasted link" from "Copied link"; a suspicious or dangerous site's name goes to Spotted.
/// - No network code is compiled into this target (see project.yml; the build checks the binary).
/// - It never logs keystrokes and never stores what is typed. It reads the text before the cursor
///   only for sentence capitals, the double-space full stop and "was it just pasted?", in memory.
final class KeyboardViewController: UIInputViewController {
    private let strip = LoupeStripView()
    private let keysView = KeysView()
    private let infoView = KeyboardInfoView()
    private var heightConstraint: NSLayoutConstraint?
    private let clipboard = KeyboardClipboard()

    private var language: KeyboardLanguage = {
        KeyboardLanguage(rawValue: UserDefaults.standard.string(forKey: "keyboard.language") ?? "") ?? .english
    }() { didSet { UserDefaults.standard.set(language.rawValue, forKey: "keyboard.language") } }
    private var page: KeyboardPage = .letters
    private var lastShiftTap: Date?
    private var lastSpaceTap: Date?

    override func viewDidLoad() {
        super.viewDidLoad()
        [strip, keysView, infoView].forEach { $0.translatesAutoresizingMaskIntoConstraints = false; view.addSubview($0) }
        NSLayoutConstraint.activate([
            strip.topAnchor.constraint(equalTo: view.topAnchor),
            strip.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            strip.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            strip.heightAnchor.constraint(equalToConstant: 40),
            keysView.topAnchor.constraint(equalTo: strip.bottomAnchor),
            keysView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keysView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keysView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            infoView.topAnchor.constraint(equalTo: strip.bottomAnchor),
            infoView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            infoView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            infoView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        infoView.isHidden = true
        strip.info.addTarget(self, action: #selector(toggleInfo), for: .touchUpInside)
        infoView.done.addTarget(self, action: #selector(toggleInfo), for: .touchUpInside)
        keysView.onKey = { [weak self] spec, alternate in self?.press(spec, alternate: alternate) }
        keysView.nextKeyboardTarget = { [weak self] control in
            guard let self else { return }
            control.addTarget(self, action: #selector(self.handleInputModeList(from:with:)), for: .allTouchEvents)
        }
        clipboard.onChange = { [weak self] state in self?.strip.set(state) }
        strip.onTap = { [weak self] in self?.clipboard.check() }
        reloadKeys()
        Footprint.note("load")
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        applyTheme()
        reloadKeys()
        updateShiftForContext()
        clipboard.fullAccess = hasFullAccess
        clipboard.reportStatus()
        clipboard.refresh()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Footprint.note("appear")
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        clipboard.forgetText()
    }

    override func viewWillLayoutSubviews() {
        super.viewWillLayoutSubviews()
        let compact = traitCollection.verticalSizeClass == .compact
        keysView.rowHeight = compact ? 32 : 43
        let h = 40 + keysView.preferredHeight
        if let c = heightConstraint {
            if c.constant != h { c.constant = h }
        } else {
            let c = view.heightAnchor.constraint(equalToConstant: h)
            c.priority = UILayoutPriority(999)
            c.isActive = true
            heightConstraint = c
        }
    }

    override func textWillChange(_ textInput: UITextInput?) {}

    override func textDidChange(_ textInput: UITextInput?) {
        applyTheme()
        reloadKeys()
        updateShiftForContext()
        // A copy made while the keyboard was up, or the copied text now just before the cursor ("Pasted").
        clipboard.refresh()
        clipboard.notePaste(before: textDocumentProxy.documentContextBeforeInput)
    }

    // MARK: keys

    private func reloadKeys() {
        let type = textDocumentProxy.returnKeyType ?? .default
        let name: String
        switch type {
        case .go: name = "go"
        case .search, .google, .yahoo: name = "search"
        case .send: name = "send"
        case .done: name = "done"
        case .next: name = "next"
        case .join: name = "join"
        case .route: name = "route"
        default: name = "return"
        }
        let shifted = language == .english && page == .letters && keysView.shift != .off
        keysView.set(rows: KeyboardLayouts.rows(language, page: page, shifted: shifted, nextKeyboard: needsInputModeSwitchKey,
                                                returnLabel: KeyboardLayouts.returnLabel(name, language: language)))
        view.semanticContentAttribute = .forceLeftToRight
    }

    private func applyTheme() {
        let theme = KeyboardTheme.current(appearance: textDocumentProxy.keyboardAppearance, traits: traitCollection)
        view.backgroundColor = theme.background
        keysView.theme = theme
        strip.theme = theme
        infoView.theme = theme
    }

    private func press(_ spec: KeySpec, alternate: String?) {
        let proxy = textDocumentProxy
        switch spec.action {
        case .text(let s):
            var out = alternate ?? s
            if language == .english, page == .letters, keysView.shift != .off { out = out.uppercased() }
            proxy.insertText(out)
            if keysView.shift == .once { keysView.shift = .off; reloadKeys() }
            if page == .numbers, out == "'" || out == " " { page = .letters; reloadKeys() }
        case .space:
            // Double space: a full stop, as the system keyboard does.
            let now = Date()
            if let last = lastSpaceTap, now.timeIntervalSince(last) < 0.45,
               let before = proxy.documentContextBeforeInput, before.hasSuffix(" "),
               let prev = before.dropLast().last, prev.isLetter || prev.isNumber {
                proxy.deleteBackward()
                proxy.insertText(". ")
                lastSpaceTap = nil
            } else {
                proxy.insertText(" ")
                lastSpaceTap = now
            }
            if page != .letters { page = .letters; reloadKeys() }
            updateShiftForContext()
        case .delete:
            proxy.deleteBackward()
            updateShiftForContext()
        case .returnKey:
            proxy.insertText("\n")
            updateShiftForContext()
        case .shift:
            let now = Date()
            if let last = lastShiftTap, now.timeIntervalSince(last) < 0.35 {
                keysView.shift = .locked
            } else {
                keysView.shift = keysView.shift == .off ? .once : .off
            }
            lastShiftTap = now
            reloadKeys()
        case .language:
            language = language == .english ? .arabic : .english
            page = .letters
            keysView.shift = .off
            reloadKeys()
            updateShiftForContext()
        case .page(let p):
            page = p
            reloadKeys()
        case .nextKeyboard:
            advanceToNextInputMode()
        }
    }

    /// A capital at the start of a sentence (English letters only), as the field asks.
    private func updateShiftForContext() {
        guard language == .english, page == .letters, keysView.shift != .locked else { return }
        let auto = textDocumentProxy.autocapitalizationType ?? .sentences
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        var want = false
        switch auto {
        case .allCharacters: want = true
        case .words: want = before.isEmpty || before.last?.isWhitespace == true
        case .sentences:
            let t = before.trimmingCharacters(in: .whitespaces)
            want = before.isEmpty || before.hasSuffix("\n") || ((t.hasSuffix(".") || t.hasSuffix("!") || t.hasSuffix("?")) && before.last == " ")
        default: want = false
        }
        let next: KeysView.Shift = want ? .once : .off
        if keysView.shift != next { keysView.shift = next; reloadKeys() }
    }

    @objc private func toggleInfo() {
        infoView.isHidden.toggle()
        keysView.isHidden = !infoView.isHidden
        if !infoView.isHidden {
            #if DEBUG
            let mem = "Memory: \(Footprint.format(Footprint.mb())) now, \(Footprint.format(Footprint.peak)) peak (DEBUG)."
            #else
            let mem: String? = nil
            #endif
            infoView.set(fullAccess: hasFullAccess, memoryLine: mem)
        }
    }
}

/// The keyboard's clipboard side: once per copy (the pasteboard's change count), with Full Access only.
final class KeyboardClipboard {
    var fullAccess = false
    var onChange: ((StripState) -> Void)?
    private var lastChangeCount = -1
    /// The copied text, in memory only while the keyboard is up (for "Pasted" vs "Copied").
    private var text: String?
    private var result: KeyboardCheck.Result?
    private var pasted = false
    /// What the clipboard holds, as iOS described it (nothing read).
    private var offered: ClipboardKind?
    private let queue = DispatchQueue(label: "com.loupe-ai.ios.keyboard.check", qos: .userInitiated)

    /// Tells the app (through the App Group, which the keyboard can write only with Full Access) that
    /// Full Access is on, and when the keyboard last ran.
    func reportStatus() {
        guard fullAccess else { return }
        let d = ProtectionGroup.defaults
        d.set(true, forKey: ClipboardShared.Keys.keyboardFullAccess)
        d.set(Date().timeIntervalSince1970, forKey: ClipboardShared.Keys.keyboardSeenAt)
        ProtectionGroup.post(ClipboardShared.keyboardChanged)
    }

    func forgetText() {
        text = nil
        pasted = false
    }

    /// Once per copy: asks iOS what kind of thing is on the clipboard **without reading it** (no paste
    /// prompt). A link or an email address makes the strip offer a check.
    func refresh() {
        guard fullAccess else { onChange?(.needsFullAccess); return }
        let pb = UIPasteboard.general
        let count = pb.changeCount
        guard count != lastChangeCount else { return }
        lastChangeCount = count
        text = nil
        result = nil
        pasted = false
        offered = nil
        guard pb.hasStrings || pb.hasURLs else { onChange?(.idle); return }
        Task { @MainActor [weak self] in
            let keys: Set<PartialKeyPath<UIPasteboard.DetectedValues>> = [\.probableWebURL, \.links, \.emailAddresses]
            let found = (try? await pb.detectedPatterns(for: keys)) ?? []
            guard let self, pb.changeCount == count else { return }
            let kind: ClipboardKind? = found.contains(\.probableWebURL) || found.contains(\.links) ? .link : found.contains(\.emailAddresses) ? .email : nil
            self.offered = kind
            self.onChange?(kind.map { .offer($0) } ?? .idle)
        }
    }

    /// The strip was tapped: read the clipboard (iOS may ask "Allow Paste"; the read waits on a
    /// background queue, so typing never freezes), then the on-device verdict.
    func check() {
        guard fullAccess, offered != nil, text == nil else { return }
        let count = lastChangeCount
        onChange?(.checking)
        queue.async { [weak self] in
            let pb = UIPasteboard.general
            let copied = pb.string ?? pb.url?.absoluteString
            let r = copied.flatMap { KeyboardCheck.check($0, lists: KeyboardClipboard.lists) }
            DispatchQueue.main.async {
                Footprint.note("verdict")
                defer { Footprint.save() }
                guard let self, self.lastChangeCount == count else { return }
                self.text = copied
                self.result = r
                guard r != nil else { self.onChange?(copied == nil ? .offer(self.offered ?? .link) : .idle); return }
                self.show()
                if let v = r?.verdict, v.level.flagged {
                    if SpottedLog().record(v) != nil { ProtectionGroup.post(ProtectionGroup.spottedChanged) }
                }
            }
        }
    }

    /// One set of mapped lists for the keyboard's life (the index is re-mapped only when the app wrote a new one).
    static let lists = ProtectionLists()

    /// The copied text just appeared before the cursor: say "Pasted".
    func notePaste(before: String?) {
        guard let text, result != nil, !pasted, let before, !text.isEmpty else { return }
        let tail = String(text.trimmingCharacters(in: .whitespacesAndNewlines).suffix(32))
        guard !tail.isEmpty, before.hasSuffix(tail) || before.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix(tail) else { return }
        pasted = true
        show()
    }

    private func show() {
        guard let r = result else { onChange?(.idle); return }
        onChange?(.verdict(level: r.verdict.level, text: ClipboardWords.strip(r.verdict, kind: r.target.kind, pasted: pasted)))
    }
}

/// The keyboard's memory footprint (what iOS counts against an extension's limit), with its peak,
/// logged (numbers only) and saved for the app's card and the report.
enum Footprint {
    private(set) static var peak: Double = 0
    private static let log = Logger(subsystem: "com.loupe-ai.ios.keyboard", category: "memory")

    static func mb() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : -1
    }

    static func note(_ at: String) {
        let now = mb()
        peak = max(peak, now)
        log.notice("footprint \(at, privacy: .public) \(now, privacy: .public) MB peak \(peak, privacy: .public) MB")
    }

    static func save() {
        let d = ProtectionGroup.defaults
        if peak > d.double(forKey: ClipboardShared.Keys.keyboardPeakMB) { d.set(peak, forKey: ClipboardShared.Keys.keyboardPeakMB) }
    }

    static func format(_ v: Double) -> String { String(format: "%.1f MB", v) }
}
