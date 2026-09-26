import UIKit

/// What the strip on top of the Loupe keyboard says.
enum StripState: Equatable {
    /// No Full Access: the keyboard types, but cannot read the clipboard.
    case needsFullAccess
    /// Nothing to check on the clipboard.
    case idle
    /// A link (or address) is on the clipboard; nothing read yet. Tapping the strip checks it.
    case offer(ClipboardKind)
    case checking
    case verdict(level: ProtectionLevel, text: String)
}

/// The Loupe strip: a lens, one line ("Pasted link: no warning signs" / "⚠ Copied link looks like a
/// fake PayPal page"), and an info button (what the keyboard does and does not do).
final class LoupeStripView: UIView {
    private let lens = UIImageView()
    private let label = UILabel()
    private let spinner = UIActivityIndicatorView(style: .medium)
    let info = UIButton(type: .system)
    /// Tapping the line (while it offers a check).
    var onTap: (() -> Void)?
    var theme: KeyboardTheme = .dark { didSet { apply() } }
    private(set) var state: StripState = .idle

    override init(frame: CGRect) {
        super.init(frame: frame)
        lens.image = UIImage(systemName: "magnifyingglass.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold))
        lens.contentMode = .center
        label.font = .systemFont(ofSize: 14, weight: .semibold)
        label.lineBreakMode = .byTruncatingTail
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.8
        label.accessibilityIdentifier = "kb.strip.text"
        info.setImage(UIImage(systemName: "info.circle", withConfiguration: UIImage.SymbolConfiguration(pointSize: 17)), for: .normal)
        info.accessibilityLabel = "About the Loupe keyboard"
        info.accessibilityIdentifier = "kb.strip.info"
        spinner.hidesWhenStopped = true
        [lens, label, spinner, info].forEach { $0.translatesAutoresizingMaskIntoConstraints = false; addSubview($0) }
        NSLayoutConstraint.activate([
            lens.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 10),
            lens.centerYAnchor.constraint(equalTo: centerYAnchor),
            lens.widthAnchor.constraint(equalToConstant: 24),
            label.leadingAnchor.constraint(equalTo: lens.trailingAnchor, constant: 8),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
            spinner.leadingAnchor.constraint(equalTo: label.trailingAnchor, constant: 6),
            spinner.centerYAnchor.constraint(equalTo: centerYAnchor),
            info.leadingAnchor.constraint(greaterThanOrEqualTo: spinner.trailingAnchor, constant: 4),
            info.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4),
            info.centerYAnchor.constraint(equalTo: centerYAnchor),
            info.widthAnchor.constraint(equalToConstant: 44),
            info.heightAnchor.constraint(equalToConstant: 38),
        ])
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        isAccessibilityElement = false
        label.isUserInteractionEnabled = true
        label.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
        set(.idle)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    @objc private func tapped() {
        if case .offer = state { onTap?() }
    }

    override func accessibilityActivate() -> Bool { tapped(); return true }

    func set(_ s: StripState) {
        state = s
        switch s {
        case .needsFullAccess: label.text = "Allow Full Access to check pasted links"
        case .idle: label.text = "Loupe checks links you copy or paste · on this iPhone"
        case .offer(let kind): label.text = kind == .link ? "Copied a link · tap to check it" : "Copied an email address · tap to check it"
        case .checking: label.text = "Checking the link you copied…"
        case .verdict(_, let text): label.text = text
        }
        if s == .checking { spinner.startAnimating() } else { spinner.stopAnimating() }
        label.accessibilityLabel = label.text
        if case .offer = s { label.accessibilityTraits = .button } else { label.accessibilityTraits = .staticText }
        apply()
    }

    private func apply() {
        backgroundColor = theme.strip
        info.tintColor = theme.softText
        spinner.color = theme.softText
        switch state {
        case .verdict(let level, _):
            let c = level == .dangerous ? theme.danger : level == .suspicious ? theme.warn : theme.ok
            label.textColor = c
            lens.tintColor = c
            lens.image = UIImage(systemName: level == .dangerous ? "xmark.shield.fill" : level == .suspicious ? "exclamationmark.shield.fill" : "checkmark.shield.fill",
                                 withConfiguration: UIImage.SymbolConfiguration(pointSize: 17, weight: .semibold))
        case .needsFullAccess:
            label.textColor = theme.softText
            lens.tintColor = theme.softText
            lens.image = UIImage(systemName: "lock.circle", withConfiguration: UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold))
        case .offer:
            label.textColor = theme.accent
            lens.tintColor = theme.accent
            lens.image = UIImage(systemName: "doc.on.clipboard", withConfiguration: UIImage.SymbolConfiguration(pointSize: 16, weight: .semibold))
        default:
            label.textColor = theme.softText
            lens.tintColor = theme.accent
            lens.image = UIImage(systemName: "magnifyingglass.circle.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold))
        }
    }
}

/// The info panel over the keys: what the keyboard does, what it never does, Full Access.
final class KeyboardInfoView: UIView {
    private let text = UITextView()
    let done = UIButton(type: .system)
    var theme: KeyboardTheme = .dark { didSet { apply() } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        text.isEditable = false
        text.isSelectable = false
        text.backgroundColor = .clear
        text.font = .systemFont(ofSize: 13)
        text.textContainerInset = UIEdgeInsets(top: 10, left: 12, bottom: 4, right: 12)
        text.accessibilityIdentifier = "kb.info.text"
        done.setTitle("Done", for: .normal)
        done.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        done.accessibilityIdentifier = "kb.info.done"
        [text, done].forEach { $0.translatesAutoresizingMaskIntoConstraints = false; addSubview($0) }
        NSLayoutConstraint.activate([
            text.topAnchor.constraint(equalTo: topAnchor),
            text.leadingAnchor.constraint(equalTo: leadingAnchor),
            text.trailingAnchor.constraint(equalTo: trailingAnchor),
            text.bottomAnchor.constraint(equalTo: done.topAnchor),
            done.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            done.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -8),
            done.heightAnchor.constraint(equalToConstant: 40),
        ])
        apply()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func set(fullAccess: Bool, memoryLine: String?) {
        var lines = [
            "Loupe keyboard",
            "It checks the link you copied or just pasted for phishing, on this iPhone only: the same checks as Loupe's Check a link, and the lists Loupe already downloaded.",
            "It has no network code at all: nothing you type or copy can leave the phone. It never logs keystrokes and never stores what you type. Of a link it warns about, it keeps only the website name, in Loupe's Spotted list.",
            fullAccess ? "Allow Full Access: on. When you copy a link, the strip offers to check it; the keyboard reads the clipboard only when you tap it (iOS may ask \"Allow Paste\")."
                       : "Allow Full Access: off. The keyboard types normally but cannot read the clipboard. To turn it on: Settings → Apps → Loupe → Keyboards → Allow Full Access.",
        ]
        if let memoryLine { lines.append(memoryLine) }
        let s = NSMutableAttributedString(string: lines[0] + "\n", attributes: [.font: UIFont.systemFont(ofSize: 15, weight: .bold), .foregroundColor: theme.text])
        s.append(NSAttributedString(string: lines.dropFirst().joined(separator: "\n\n"), attributes: [.font: UIFont.systemFont(ofSize: 13), .foregroundColor: theme.text]))
        text.attributedText = s
    }

    private func apply() {
        backgroundColor = theme.background
        done.tintColor = theme.accent
    }
}
