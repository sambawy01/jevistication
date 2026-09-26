import UIKit

/// One key's face (drawn only; the keys view handles every touch, so fast typing with two thumbs rolls
/// over from key to key the way the system keyboard does).
final class KeyView: UIView {
    let spec: KeySpec
    private let label = UILabel()
    private let icon = UIImageView()
    var activate: (() -> Void)?
    var pressed = false { didSet { applyColors() } }
    var theme: KeyboardTheme = .dark { didSet { applyColors() } }
    var shiftState: KeysView.Shift = .off { didSet { applyContent() } }

    init(spec: KeySpec) {
        self.spec = spec
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        layer.cornerRadius = 7
        layer.cornerCurve = .continuous
        layer.shadowOffset = CGSize(width: 0, height: 1)
        layer.shadowRadius = 0
        layer.shadowOpacity = 1
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.5
        icon.contentMode = .center
        addSubview(label)
        addSubview(icon)
        isAccessibilityElement = true
        accessibilityTraits = .keyboardKey
        applyContent()
        applyColors()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    var isSpecial: Bool {
        switch spec.action {
        case .text, .space: return false
        default: return true
        }
    }

    private func symbol(_ name: String, _ size: CGFloat = 18) {
        icon.image = UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .regular))
        label.text = nil
    }

    private func applyContent() {
        icon.image = nil
        switch spec.action {
        case .shift:
            symbol(shiftState == .locked ? "capslock.fill" : shiftState == .once ? "shift.fill" : "shift")
            accessibilityLabel = shiftState == .locked ? "Caps lock" : "Shift"
        case .delete:
            symbol("delete.left")
            accessibilityLabel = "Delete"
        case .nextKeyboard:
            symbol("globe")
            accessibilityLabel = "Next keyboard"
        case .text(let s):
            label.text = s
            label.font = .systemFont(ofSize: s.count > 1 ? 16 : 23, weight: .regular)
            accessibilityLabel = s
        case .space:
            label.text = spec.label
            label.font = .systemFont(ofSize: 15)
            accessibilityLabel = "Space"
        case .returnKey:
            label.text = spec.label
            label.font = .systemFont(ofSize: 15)
            accessibilityLabel = spec.label.capitalized
        case .language:
            label.text = spec.label
            label.font = .systemFont(ofSize: 17, weight: .semibold)
            accessibilityLabel = spec.label == "EN" ? "English" : "Arabic"
        case .page:
            label.text = spec.label
            label.font = .systemFont(ofSize: 15)
            accessibilityLabel = spec.label == "ABC" || spec.label == "أ ب ج" ? "Letters" : spec.label == "#+=" ? "More symbols" : "Numbers"
        }
        accessibilityIdentifier = "kb.key.\(accessibilityLabel ?? spec.label)"
    }

    private func applyColors() {
        backgroundColor = pressed ? theme.pressed : (isSpecial ? theme.specialKey : theme.key)
        if case .shift = spec.action, shiftState != .off { backgroundColor = theme.key }
        label.textColor = theme.text
        icon.tintColor = theme.text
        layer.shadowColor = theme.shadow.cgColor
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        label.frame = bounds.insetBy(dx: 2, dy: 0)
        icon.frame = bounds
    }

    override func accessibilityActivate() -> Bool {
        activate?()
        return true
    }
}

/// The key area: lays out the rows and turns touches into key presses (with a popup over letters,
/// held-key alternatives such as أ إ آ, and a repeating delete).
final class KeysView: UIView {
    enum Shift: Equatable { case off, once, locked }

    var onKey: ((KeySpec, String?) -> Void)?
    /// The globe key's control, which iOS drives (`handleInputModeList(from:with:)`).
    var nextKeyboardTarget: ((UIControl) -> Void)?
    var theme: KeyboardTheme = .dark { didSet { applyTheme() } }
    var shift: Shift = .off { didSet { keys.forEach { $0.shiftState = shift } } }
    var rowHeight: CGFloat = 43

    private(set) var rows: [[KeySpec]] = []
    private var keys: [KeyView] = []
    private var rowOf: [Int] = []
    private var globe: UIButton?
    private let popup = KeyPopup()
    private let alternatesView = AlternatesView()

    private struct Tracked {
        let key: KeyView
        var alternates = false
        var repeatTimer: Timer?
        var holdTimer: Timer?
    }
    private var tracked: [ObjectIdentifier: Tracked] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        clipsToBounds = false
        addSubview(popup)
        addSubview(alternatesView)
        popup.isHidden = true
        alternatesView.isHidden = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func set(rows: [[KeySpec]]) {
        guard rows != self.rows else { return }
        self.rows = rows
        keys.forEach { $0.removeFromSuperview() }
        globe?.removeFromSuperview()
        globe = nil
        keys = []
        rowOf = []
        for (r, row) in rows.enumerated() {
            for spec in row {
                let k = KeyView(spec: spec)
                k.theme = theme
                k.shiftState = shift
                k.activate = { [weak self, weak k] in if let k { self?.onKey?(k.spec, nil) } }
                insertSubview(k, belowSubview: popup)
                keys.append(k)
                rowOf.append(r)
                if case .nextKeyboard = spec.action {
                    let b = UIButton(type: .custom)
                    b.accessibilityLabel = "Next keyboard"
                    b.accessibilityIdentifier = "kb.key.Next keyboard"
                    k.isAccessibilityElement = false
                    nextKeyboardTarget?(b)
                    addSubview(b)
                    globe = b
                }
            }
        }
        setNeedsLayout()
    }

    private func applyTheme() {
        keys.forEach { $0.theme = theme }
        popup.theme = theme
        alternatesView.theme = theme
    }

    // MARK: layout

    private let side: CGFloat = 3
    private let gap: CGFloat = 6
    private let rowGap: CGFloat = 11
    private let top: CGFloat = 8

    var preferredHeight: CGFloat { top + CGFloat(rows.count) * rowHeight + CGFloat(max(rows.count - 1, 0)) * rowGap + 6 }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard !rows.isEmpty else { return }
        // One key unit from the row with the most letters; a row of letters only is centred at that
        // unit, a row with shift, delete or the bottom keys stretches to both edges.
        let avail = bounds.width - 2 * side
        let unit = rows.filter { $0.allSatisfy(\.isCharacter) }
            .map { (avail - CGFloat($0.count - 1) * gap) / CGFloat($0.reduce(0) { $0 + $1.width }) }.min()
            ?? (avail - 9 * gap) / 10
        var i = 0
        for (r, row) in rows.enumerated() {
            let gaps = CGFloat(row.count - 1) * gap
            let charUnits = CGFloat(row.filter(\.isCharacter).reduce(0) { $0 + $1.width })
            let otherUnits = CGFloat(row.filter { !$0.isCharacter }.reduce(0) { $0 + $1.width })
            // Letters keep the unit; shift, delete and the bottom keys share what is left.
            let otherU = otherUnits > 0 ? max((avail - gaps - charUnits * unit) / otherUnits, unit * 0.8) : 0
            let width = charUnits * unit + otherUnits * otherU + gaps
            var x = (bounds.width - width) / 2
            let y = top + CGFloat(r) * (rowHeight + rowGap)
            for spec in row {
                let w = CGFloat(spec.width) * (spec.isCharacter ? unit : otherU)
                let k = keys[i]
                k.frame = CGRect(x: x, y: y, width: w, height: rowHeight)
                if case .nextKeyboard = spec.action { globe?.frame = k.frame.insetBy(dx: -gap / 2, dy: -rowGap / 2) }
                x += w + gap
                i += 1
            }
        }
    }

    /// The key a touch at [p] means: its row by height, then the nearest key in the row (the gaps
    /// between keys belong to the nearer key, and the row's ends to its outer keys).
    func key(at p: CGPoint) -> KeyView? {
        guard !rows.isEmpty else { return nil }
        let r = min(max(Int((p.y - top + rowGap / 2) / (rowHeight + rowGap)), 0), rows.count - 1)
        var best: (KeyView, CGFloat)?
        for (i, k) in keys.enumerated() where rowOf[i] == r {
            let d = p.x < k.frame.minX ? k.frame.minX - p.x : p.x > k.frame.maxX ? p.x - k.frame.maxX : 0
            if best == nil || d < best!.1 { best = (k, d) }
        }
        return best?.0
    }

    // MARK: touches

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            // A new touch while another is down commits the earlier one (rollover typing).
            for (id, other) in tracked where !other.alternates {
                if case .text = other.key.spec.action { finish(id, commit: true) }
            }
            guard let k = key(at: t.location(in: self)), !(k.spec.action == .nextKeyboard) else { continue }
            var tr = Tracked(key: k)
            k.pressed = true
            UIDevice.current.playInputClick()
            switch k.spec.action {
            case .text:
                showPopup(for: k)
                if !k.spec.alternates.isEmpty {
                    tr.holdTimer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { [weak self] _ in
                        self?.showAlternates(for: t)
                    }
                }
            case .delete:
                onKey?(k.spec, nil)
                tr.holdTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
                    guard let self else { return }
                    let rep = Timer.scheduledTimer(withTimeInterval: 0.085, repeats: true) { [weak self] _ in self?.onKey?(k.spec, nil) }
                    self.tracked[ObjectIdentifier(t)]?.repeatTimer = rep
                }
            default:
                break
            }
            tracked[ObjectIdentifier(t)] = tr
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard let tr = tracked[ObjectIdentifier(t)] else { continue }
            if tr.alternates {
                alternatesView.select(at: t.location(in: alternatesView))
                continue
            }
            // Sliding onto another letter moves the press (as the system keyboard does).
            if case .text = tr.key.spec.action, let k = key(at: t.location(in: self)), k !== tr.key, case .text = k.spec.action {
                tr.key.pressed = false
                tr.holdTimer?.invalidate()
                k.pressed = true
                showPopup(for: k)
                var next = Tracked(key: k)
                if !k.spec.alternates.isEmpty {
                    next.holdTimer = Timer.scheduledTimer(withTimeInterval: 0.42, repeats: false) { [weak self] _ in self?.showAlternates(for: t) }
                }
                tracked[ObjectIdentifier(t)] = next
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { finish(ObjectIdentifier(t), commit: true) }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches { finish(ObjectIdentifier(t), commit: false) }
    }

    private func finish(_ id: ObjectIdentifier, commit: Bool) {
        guard let tr = tracked.removeValue(forKey: id) else { return }
        tr.holdTimer?.invalidate()
        tr.repeatTimer?.invalidate()
        tr.key.pressed = false
        popup.isHidden = true
        if tr.alternates {
            let pick = alternatesView.selected
            alternatesView.isHidden = true
            if commit, let pick { onKey?(tr.key.spec, pick) }
            return
        }
        guard commit else { return }
        if case .delete = tr.key.spec.action { return }   // deleted on touch-down
        onKey?(tr.key.spec, nil)
    }

    private func showPopup(for k: KeyView) {
        guard case .text(let s) = k.spec.action else { return }
        popup.show(text: shift == .off ? s : s.uppercased(), over: k.frame)
        bringSubviewToFront(popup)
    }

    private func showAlternates(for t: UITouch) {
        let id = ObjectIdentifier(t)
        guard var tr = tracked[id], !tr.key.spec.alternates.isEmpty else { return }
        tr.alternates = true
        tracked[id] = tr
        popup.isHidden = true
        alternatesView.show([tr.key.label(shift: shift)] + tr.key.spec.alternates, over: tr.key.frame, in: bounds)
        bringSubviewToFront(alternatesView)
    }
}

private extension KeyView {
    func label(shift: KeysView.Shift) -> String {
        guard case .text(let s) = spec.action else { return spec.label }
        return shift == .off ? s : s.uppercased()
    }
}

/// The enlarged letter above a pressed key.
final class KeyPopup: UIView {
    private let label = UILabel()
    var theme: KeyboardTheme = .dark { didSet { backgroundColor = theme.key; label.textColor = theme.text } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        layer.cornerRadius = 9
        layer.cornerCurve = .continuous
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.35
        layer.shadowRadius = 5
        label.font = .systemFont(ofSize: 32)
        label.textAlignment = .center
        addSubview(label)
        backgroundColor = theme.key
        label.textColor = theme.text
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show(text: String, over key: CGRect) {
        label.text = text
        let w = max(key.width + 14, 44)
        frame = CGRect(x: key.midX - w / 2, y: key.minY - 54, width: w, height: 52)
        label.frame = bounds
        isHidden = false
    }
}

/// Held-key alternatives (ا → أ إ آ ٱ): slide to one and let go.
final class AlternatesView: UIView {
    private var labels: [UILabel] = []
    private(set) var selected: String?
    var theme: KeyboardTheme = .dark { didSet { backgroundColor = theme.key } }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        layer.cornerRadius = 9
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.35
        layer.shadowRadius = 5
        backgroundColor = theme.key
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func show(_ options: [String], over key: CGRect, in bounds: CGRect) {
        labels.forEach { $0.removeFromSuperview() }
        let cell: CGFloat = 40
        let w = cell * CGFloat(options.count) + 8
        var x = key.midX - cell / 2 - 4
        x = min(max(x, 2), bounds.width - w - 2)
        frame = CGRect(x: x, y: key.minY - 54, width: w, height: 50)
        labels = options.enumerated().map { i, s in
            let l = UILabel(frame: CGRect(x: 4 + CGFloat(i) * cell, y: 5, width: cell, height: 40))
            l.text = s
            l.font = .systemFont(ofSize: 24)
            l.textAlignment = .center
            l.textColor = theme.text
            l.layer.cornerRadius = 7
            l.clipsToBounds = true
            addSubview(l)
            return l
        }
        select(index: options.count > 1 ? 1 : 0)
        isHidden = false
    }

    func select(at p: CGPoint) {
        guard !labels.isEmpty else { return }
        let i = min(max(Int((p.x - 4) / 40), 0), labels.count - 1)
        select(index: i)
    }

    private func select(index: Int) {
        for (j, l) in labels.enumerated() {
            l.backgroundColor = j == index ? theme.accent : .clear
            l.textColor = j == index ? .black : theme.text
        }
        selected = labels[index].text
    }
}
