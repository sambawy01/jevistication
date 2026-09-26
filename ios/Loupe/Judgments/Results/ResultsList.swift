import LoupeKit
import Photos
import SwiftUI

// The filtered list under the dashboard: a sticky bar (search, active filters as removable chips, sort, select),
// rows grouped by answer, swipe or the answer pill to correct, multi-select for bulk answers, and an Undo bar.
// Rows are List cells (recycled), so 10,000 items scroll like 50.

/// The sticky bar over the list.
struct ResultsFilterBar: View {
    @ObservedObject var model: ResultsModel
    @FocusState private var searching: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass").foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
                    TextField("Search names", text: $model.filter.query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .focused($searching)
                        .foregroundStyle(Palette.ink)
                        .accessibilityIdentifier("results.search")
                    if !model.filter.query.isEmpty {
                        Button { model.filter.query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.inkSoft) }
                            .buttonStyle(.plain)
                            .frame(minWidth: 32, minHeight: 44)
                            .accessibilityLabel("Clear search")
                    }
                }
                .padding(.horizontal, 10)
                .frame(minHeight: 44)
                .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(searching ? Palette.borderActive : Palette.border))
                Menu {
                    Picker("Sort", selection: $model.sort) {
                        ForEach(ResultsSort.allCases) { Text($0.title).tag($0) }
                    }
                } label: {
                    Image(systemName: "arrow.up.arrow.down").font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .accessibilityLabel("Sort: \(model.sort.title)")
                .accessibilityIdentifier("results.sort")
                Button {
                    model.selecting.toggle()
                } label: {
                    Text(model.selecting ? "Done" : "Select").font(.subheadline.weight(.semibold))
                        .frame(minWidth: 56, minHeight: 44)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Palette.cyan)
                .accessibilityIdentifier("results.select")
            }
            let chips = model.chips
            if !chips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(chips) { chip in
                            Button { model.remove(chip: chip.id) } label: {
                                HStack(spacing: 5) {
                                    Text(chip.label).font(Typeface.mono(12, weight: .medium)).lineLimit(1)
                                    Image(systemName: "xmark").font(.system(size: 10, weight: .bold))
                                }
                                .foregroundStyle(Palette.ink)
                                .padding(.horizontal, 12)
                                .frame(minHeight: 36)
                                .background(Palette.accentSoft, in: Capsule())
                                .overlay(Capsule().stroke(Palette.borderActive.opacity(0.6)))
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Filter: \(chip.label). Remove")
                            .accessibilityIdentifier("results.chip.\(chip.id)")
                        }
                        if chips.count > 1 {
                            Button("Clear all") { model.clearFilters() }
                                .font(.footnote.weight(.semibold)).foregroundStyle(Palette.cyan)
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("results.clearFilters")
                        }
                    }
                }
            }
            Text(model.filter.isEmpty
                 ? "\(ResultsNames.count(model.shown)) items · \(model.sort.title.lowercased())"
                 : "Showing \(ResultsNames.count(model.shown)) of \(ResultsNames.count(model.index?.count ?? 0)) · \(model.sort.title.lowercased())")
                .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
                .accessibilityIdentifier("results.shown")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Palette.ground)
        .overlay(alignment: .bottom) { Rectangle().fill(Palette.hairline).frame(height: 1) }
    }
}

/// A group's title in the list: the answer, its colour and how many rows it holds here.
struct ResultsGroupHeader: View {
    let title: String
    let color: Color
    let count: Int
    let key: String

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 4, height: 16)
            Text(title.uppercased()).font(Typeface.mono(11, weight: .semibold)).tracking(0.6).foregroundStyle(Palette.ink)
                .lineLimit(2)
            Spacer()
            Text(ResultsNames.count(count)).font(Typeface.mono(11)).monospacedDigit().foregroundStyle(Palette.inkSoft)
        }
        .padding(.top, 14).padding(.bottom, 2)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("results.group.\(key)")
    }
}

/// One result: thumbnail or glyph, name, source · kind · date, the answer pill (a menu to correct), confidence.
struct ResultRowCell: View {
    let record: ResultRecord
    let item: SourceItem?
    let options: [String]
    let answerTitle: String
    let answerColor: Color
    let optionTitle: (Int) -> String
    let selecting: Bool
    let selected: Bool
    let open: () -> Void
    let correct: (Int?) -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            if selecting {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3).foregroundStyle(selected ? Palette.cyan : Palette.inkSoft)
                    .accessibilityHidden(true)
            }
            RowThumb(record: record, item: item).onTapGesture(perform: open)
            VStack(alignment: .leading, spacing: 5) {
                // The name gets the full width (middle-truncated, so the dates and extensions stay readable).
                Button(action: open) {
                    Text(record.name).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                        .lineLimit(1).truncationMode(.middle)
                        .frame(maxWidth: .infinity, minHeight: 22, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("\(record.name). \(answerTitle), \(confidence). \(meta)")
                .accessibilityHint(selecting ? "Selects this item" : "Opens the item")
                .accessibilityAddTraits(selected ? .isSelected : [])
                .accessibilityIdentifier("results.row")
                HStack(spacing: 8) {
                    pill
                    Text(confidence).font(Typeface.mono(11, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                        .accessibilityHidden(true)
                    Text(meta).font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft).lineLimit(1)
                        .accessibilityHidden(true)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
                .onTapGesture(perform: open)
            }
        }
        .padding(.vertical, 8)
        .frame(minHeight: 60)
    }

    private var pill: some View {
        Menu {
            ForEach(options.indices, id: \.self) { i in
                if record.canCorrect(to: i) {
                    Button {
                        correct(i)
                    } label: {
                        if record.answer == i { Label(optionTitle(i), systemImage: "checkmark") } else { Text(optionTitle(i)) }
                    }
                }
            }
            if record.correction == nil, record.answer != nil || (record.top >= 0 && !record.unusable) {
                Divider()
                Button { correct(nil) } label: { Label(record.answer == nil ? "Accept the model's lean" : "Confirm", systemImage: "checkmark.seal") }
            }
        } label: {
            HStack(spacing: 4) {
                if record.correction != nil { Image(systemName: "person.fill.checkmark").font(.system(size: 9, weight: .bold)) }
                Text(answerTitle).font(Typeface.mono(11, weight: .semibold)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 8, weight: .bold))
            }
            .foregroundStyle(answerColor)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(answerColor.opacity(0.14), in: Capsule())
            .overlay(Capsule().stroke(answerColor.opacity(0.45)))
            .frame(maxWidth: 170, minHeight: 30, alignment: .leading)
            .fixedSize(horizontal: true, vertical: false)
        }
        .disabled(selecting)
        .accessibilityLabel("Answer: \(answerTitle). Change")
        .accessibilityIdentifier("results.answer")
    }

    private var meta: String {
        ([ResultsNames.source(record.source), record.kind] + [record.day].compactMap { $0 }).joined(separator: " · ")
    }

    private var confidence: String {
        if record.unusable { return "—" }
        if record.check != nil { return "rule" }
        return pct(record.mass)
    }
}

/// A row's picture: a thumbnail for photos and documents (cached; Photos only when access was given), else a
/// glyph of the item kind on its source's hue.
struct RowThumb: View {
    let record: ResultRecord
    let item: SourceItem?
    @State private var image: UIImage?

    var body: some View {
        let hue = SourceLook.hue(ResultsLook.glyphSource(record.source))
        ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(LinearGradient(colors: [hue.opacity(0.26), hue.opacity(0.06)], startPoint: .topLeading, endPoint: .bottomTrailing))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: ResultsLook.kindSymbol(record.kind))
                    .symbolRenderingMode(.hierarchical)
                    .font(.system(size: 17, weight: .semibold)).foregroundStyle(hue)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(hue.opacity(0.35), lineWidth: 1))
        .accessibilityHidden(true)
        .task(id: record.itemId) {
            image = ThumbCache.shared.object(forKey: record.itemId as NSString)
            guard image == nil, let item, Self.wantsThumb(item) else { return }
            let target = LiveItemResolver.live.target(for: item)
            if let img = await ItemImages.thumbnail(for: target) {
                ThumbCache.shared.setObject(img, forKey: record.itemId as NSString)
                if !Task.isCancelled { image = img }
            }
        }
    }

    /// Pictures and PDFs only; a Photos asset only when the library is already open to Loupe (never prompts).
    static func wantsThumb(_ item: SourceItem) -> Bool {
        guard item.kind == .image || item.kind == .pdf else { return false }
        #if DEBUG
        if item.id.hasPrefix("fixture:") { return false }
        #endif
        if item.sourceId == "photos" {
            let s = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            return s == .authorized || s == .limited
        }
        return true
    }
}

enum ThumbCache {
    static let shared: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 400
        return c
    }()
}

/// The bottom bar while selecting: how many, select all shown, confirm, mark as….
struct ResultsBulkBar: View {
    @ObservedObject var model: ResultsModel
    let apply: (Int?) -> Void

    var body: some View {
        let n = model.selected.count
        VStack(spacing: 8) {
            HStack {
                Text(n == 1 ? "1 selected" : "\(ResultsNames.count(n)) selected")
                    .font(Typeface.mono(13, weight: .semibold)).monospacedDigit().foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("results.selectedCount")
                Spacer()
                Button(model.selected.count == model.shown && n > 0 ? "Select none" : "Select all \(ResultsNames.count(model.shown)) shown") {
                    if model.selected.count == model.shown && n > 0 { model.selected.removeAll() } else { model.selected = Set(model.shownIndices) }
                }
                .font(.footnote.weight(.semibold)).foregroundStyle(Palette.cyan)
                .frame(minHeight: 44)
                .accessibilityIdentifier("results.selectAll")
            }
            HStack(spacing: 10) {
                Button { apply(nil) } label: { Label("Confirm", systemImage: "checkmark.seal").frame(maxWidth: .infinity) }
                    .buttonStyle(.neonPrimary)
                    .disabled(n == 0)
                    .accessibilityIdentifier("results.bulkConfirm")
                Menu {
                    ForEach(model.options.indices, id: \.self) { i in
                        Button("Mark as \(model.optionTitle(i))") { apply(i) }
                    }
                } label: {
                    Label("Mark as…", systemImage: "tag").font(.body.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Palette.groundMid, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Palette.border))
                }
                .disabled(n == 0)
                .accessibilityIdentifier("results.bulkMark")
            }
            Text("Each answer is recorded as a correction, exactly as in the Unsure queue.")
                .font(.caption2).foregroundStyle(Palette.inkSoft)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Palette.groundMid.opacity(0.98))
        .overlay(alignment: .top) { Rectangle().fill(Palette.border).frame(height: 1) }
    }
}

/// "Marked as no: 12 items · Undo".
struct ResultsUndoBar: View {
    let text: String
    let undo: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.okText).accessibilityHidden(true)
            Text(text).font(.footnote.weight(.medium)).foregroundStyle(Palette.ink).lineLimit(2)
                .accessibilityIdentifier("results.lastChange")
            Spacer(minLength: 4)
            Button("Undo", action: undo)
                .font(.subheadline.weight(.bold)).foregroundStyle(Palette.cyan)
                .frame(minWidth: 44, minHeight: 44)
                .accessibilityIdentifier("results.undo")
            Button(action: dismiss) { Image(systemName: "xmark").foregroundStyle(Palette.inkSoft) }
                .frame(minWidth: 44, minHeight: 44)
                .accessibilityLabel("Dismiss")
        }
        .padding(.horizontal, 14)
        .background(Palette.cardHigh, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).stroke(Palette.border))
        .padding(.horizontal, 12).padding(.bottom, 6)
    }
}
