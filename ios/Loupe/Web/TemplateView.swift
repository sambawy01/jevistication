import SwiftUI
import LoupeKit

/// One template: its source switch, inputs, ready-made questions, the custom question box, and
/// the answer rendered in place (the live run view while Laya reads, then the rows).
struct TemplateView: View {
    let sector: WebSector
    @EnvironmentObject private var library: WebLibraryModel
    @EnvironmentObject private var web: WebModel

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    sourceCard
                    if library.isEnabled(sector) && web.helperEnabled {
                        inputsCard
                        questionsCard
                        askButton
                        WebAnswerSection(sector: sector).id("answer")
                    }
                }
                .padding(16)
            }
            .onChange(of: library.phase[sector]) { _, p in
                if p == .done || p == .fetching { withAnimation { proxy.scrollTo("answer", anchor: .top) } }
            }
        }
        .neonGround()
        .navigationTitle(sector.title)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("web.template.screen.\(sector.rawValue)")
    }

    // MARK: Source switch (§4a: off by default, labelled, one per source)

    @ViewBuilder private var sourceCard: some View {
        if !web.helperEnabled || !web.isOnline {
            OfflineCard(helperOff: !web.helperEnabled)
        }
        if library.isEnabled(sector) {
            HStack {
                Pill(text: "Online · \(sector.provider)", color: Palette.cyan, symbol: "globe")
                Spacer()
                Toggle(WS.t("source.toggle", ["sector": sector.title]), isOn: Binding(
                    get: { library.isEnabled(sector) }, set: { library.setEnabled(sector, $0) }))
                    .labelsHidden()
                    .accessibilityIdentifier("web.source.\(sector.rawValue)")
            }
            Text(sector.sends).font(.caption).foregroundStyle(Palette.inkSoft)
        } else {
            VStack(alignment: .leading, spacing: 10) {
                Label(WS.t("source.off", ["sector": sector.title]), systemImage: "power").font(.headline).foregroundStyle(Palette.ink)
                Text(WS.t("source.offText", ["provider": sector.provider, "sends": sector.sends]))
                    .font(.footnote).foregroundStyle(Palette.inkSoft)
                Button(WS.t("source.turnOn", ["sector": sector.title])) { library.setEnabled(sector, true) }
                    .buttonStyle(.neonPrimary)
                    .disabled(!web.helperEnabled)
                    .accessibilityIdentifier("web.source.turnOn")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .card()
        }
    }

    // MARK: Inputs

    private var inputsCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            switch sector {
            case .currency:
                HStack(spacing: 12) {
                    code(WS.t("input.base"), $library.inputs.base, "web.input.base")
                    code(WS.t("input.quote"), $library.inputs.quote, "web.input.quote")
                    code(WS.t("input.home"), $library.inputs.home, "web.input.home")
                }
                field(WS.t("input.basket"), $library.inputs.basket, "web.input.basket")
            case .weather:
                field(WS.t("input.place"), $library.inputs.place, "web.input.place")
            case .trains:
                HStack(spacing: 12) {
                    code(WS.t("input.from"), $library.inputs.fromCrs, "web.input.from")
                    code(WS.t("input.to"), $library.inputs.toCrs, "web.input.to")
                }
                HStack(spacing: 12) {
                    field(WS.t("input.by"), $library.inputs.arriveBy, "web.input.by")
                    field(WS.t("input.target"), $library.inputs.targetTime, "web.input.target")
                }
                Stepper(WS.t("input.journey", ["m": "\(library.inputs.journeyMinutes)"]), value: $library.inputs.journeyMinutes, in: 5...240, step: 5)
                    .font(.footnote).foregroundStyle(Palette.ink)
            case .flights:
                EmptyView()
            }
            if let p = library.inputs.problem(for: sector) {
                Text(p).font(.caption).foregroundStyle(Palette.red)
            }
        }
        .card()
    }

    private func code(_ label: String, _ text: Binding<String>, _ id: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(Palette.inkSoft)
            TextField("", text: Binding(get: { text.wrappedValue },
                                        set: { text.wrappedValue = String($0.uppercased().filter(\.isLetter).prefix(3)) }))
                .font(Typeface.display(24))
                .textInputAutocapitalization(.characters).autocorrectionDisabled()
                .foregroundStyle(Palette.ink)
                .accessibilityIdentifier(id)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func field(_ label: String, _ text: Binding<String>, _ id: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(Palette.inkSoft)
            TextField("", text: text)
                .autocorrectionDisabled()
                .padding(8)
                .background(Palette.ground.opacity(0.6), in: RoundedRectangle(cornerRadius: 8))
                .foregroundStyle(Palette.ink)
                .accessibilityIdentifier(id)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Questions: the variants and the custom box

    private var questionsCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Caption(text: WS.t("questions"))
            ForEach(WebCatalog.variants(sector)) { v in
                choiceRow(selected: library.choiceFor(sector) == .variant(v.id), title: v.question(library.inputs), type: v.type)
                    .onTapGesture { library.choice[sector] = .variant(v.id) }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityIdentifier("web.variant.\(v.id)")
            }
            choiceRow(selected: library.choiceFor(sector) == .custom, title: WS.t("custom"), type: nil)
                .onTapGesture { library.choice[sector] = .custom }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("web.variant.custom")
            if library.choiceFor(sector) == .custom { customBox }
        }
        .card()
    }

    private func choiceRow(selected: Bool, title: String, type: AnswerType?) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                .foregroundStyle(selected ? Palette.cyan : Palette.inkSoft)
            Text(title).font(.subheadline).foregroundStyle(Palette.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let type { Pill(text: type.label, color: Palette.blue) }
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }

    private var customBox: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField(WS.t("custom.placeholder"), text: Binding(
                get: { library.customText[sector] ?? "" }, set: { library.customText[sector] = $0 }), axis: .vertical)
                .lineLimit(2...4)
                .padding(10)
                .background(Palette.ground.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                .foregroundStyle(Palette.ink)
                .accessibilityIdentifier("web.custom.text")
            Text(WS.t("custom.type")).font(.caption).foregroundStyle(Palette.inkSoft)
            Picker(WS.t("custom.type"), selection: Binding(
                get: { library.typeFor(sector) }, set: { library.customType[sector] = $0 })) {
                ForEach(AnswerType.allCases) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("web.custom.type")
            let text = library.customText[sector] ?? ""
            let findings = library.customFindings(sector)
            if !text.trimmingCharacters(in: .whitespaces).isEmpty {
                if findings.isEmpty {
                    Label(WS.t("custom.ok"), systemImage: "checkmark.circle").font(.caption).foregroundStyle(Palette.okText)
                        .accessibilityIdentifier("web.custom.ok")
                } else {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(findings, id: \.self) { Text("• \($0)").font(.caption).foregroundStyle(Palette.warnText) }
                    }
                    .accessibilityIdentifier("web.custom.findings")
                }
            }
        }
        .padding(.top, 4)
    }

    private var askButton: some View {
        Button {
            Task { await library.ask(sector, online: web.isOnline && web.helperEnabled) }
        } label: {
            HStack {
                if library.phase[sector] == .fetching { ProgressView().tint(Palette.onAccent) }
                Text(library.phase[sector] == .fetching ? WS.t("asking") : WS.t("ask"))
            }
            .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.neonPrimary)
        .disabled(!library.canAsk(sector) || !web.helperEnabled)
        .accessibilityIdentifier("web.ask")
    }
}

/// The answer in place: labelled Online with source, fetch time and attribution, the live run
/// view while Laya reads, the headline from Laya and the rules, then every row with its source.
struct WebAnswerSection: View {
    let sector: WebSector
    @EnvironmentObject private var library: WebLibraryModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if case .failed(let e) = library.phase[sector] {
                failure(e)
            }
            if let run = library.runs[sector] {
                onlineHeader(run)
                if library.isFixtureMode {
                    Label(WS.t("fixture"), systemImage: "flask").font(.footnote.weight(.medium)).foregroundStyle(Palette.ink)
                        .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                        .background(Palette.amber.opacity(0.18), in: RoundedRectangle(cornerRadius: 10))
                        .accessibilityIdentifier("web.fixtureBanner")
                }
                answerCard(run)
                LiveRunSection(view: "web", whileRunning: true)
                ForEach(library.shown(sector)) { WebRowCard(item: $0, sector: sector) }
                attribution(run.result.attribution)
            }
        }
    }

    private func failure(_ e: HelperError) -> some View {
        let text: String
        switch e {
        case .notConfigured: text = WS.t("notConfigured.\(sector.rawValue)")
        case .notDeployed: text = WS.t("notDeployed", ["sector": sector.title])
        case .noResults: text = WS.t("noRows")
        default: text = e.message
        }
        let title = e == .notDeployed ? WS.t("notDeployed", ["sector": sector.title]).components(separatedBy: ":").first ?? e.title : e.title
        return ErrorBanner(title: title, message: text)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier(e == .notConfigured ? "web.error.notConfigured" : e == .notDeployed ? "web.error.notDeployed" : "web.error")
    }

    private func onlineHeader(_ run: WebRun) -> some View {
        HStack {
            let online = WS.t("online", ["provider": sector.provider, "time": WebDates.fetched(run.result.fetchedAt)])
            Pill(text: online, color: Palette.cyan, symbol: "globe")
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(online)
                .accessibilityIdentifier("web.onlineBadge")
            Spacer()
            MascotView(state: isRunning(run) ? .scanning : .found, size: 40)
        }
    }

    private func isRunning(_ run: WebRun) -> Bool { if case .running = run.ranking { return true }; return false }

    private func answerCard(_ run: WebRun) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(run.question).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                .accessibilityIdentifier("web.answer.question")
            Caption(text: WS.t("answer.by", ["who": library.answeredByLaya(sector) ? WS.t("answer.laya") : WS.t("answer.rules")]))
                .accessibilityIdentifier("web.answer.by")
            if let laya = run.laya {
                Text(WebLibraryModel.headline(laya, run: run, who: "Laya")).font(.headline).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("web.answer.laya")
            }
            Text(WebLibraryModel.headline(run.rules, run: run, who: WS.t("answer.rules"))).font(run.laya == nil ? .headline : .subheadline)
                .foregroundStyle(run.laya == nil ? Palette.ink : Palette.inkSoft)
                .accessibilityIdentifier("web.answer.rules")
            if run.variantId == nil {
                Text(WS.t("answer.customRule", ["sector": sector.title, "rule": WS.t("rule.name.\(run.rule.rawValue)")]))
                    .font(.caption).foregroundStyle(Palette.inkSoft)
            }
            status(run)
        }
        .card()
    }

    @ViewBuilder private func status(_ run: WebRun) -> some View {
        switch run.ranking {
        case let .running(done, total):
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: Double(done), total: Double(max(total, 1))).tint(Palette.blue)
                HStack {
                    Text(WS.t("answer.running", ["i": "\(min(done + 1, total))", "n": "\(total)"])).font(.caption).foregroundStyle(Palette.inkSoft)
                    Spacer()
                    Button(WS.t("answer.cancel")) { library.cancel(sector) }.font(.caption.weight(.semibold))
                        .accessibilityIdentifier("web.answer.cancel")
                }
                Text(WS.t("answer.runningRules")).font(.caption2).foregroundStyle(Palette.inkSoft)
            }
        case .laya:
            Toggle(isOn: Binding(get: { library.showRules[sector] ?? false }, set: { library.showRules[sector] = $0 })) {
                Text(WS.t("answer.showRules")).font(.footnote)
            }
            .accessibilityIdentifier("web.answer.showRules")
            if let d = WebLibraryModel.disagreement(run) {
                Label(WS.t("answer.disagree", ["rules": d]), systemImage: "arrow.left.arrow.right").font(.footnote).foregroundStyle(Palette.amber)
                    .accessibilityIdentifier("web.answer.disagree")
            } else {
                Text(WS.t("answer.agree")).font(.footnote).foregroundStyle(Palette.inkSoft)
            }
            let unsure = (run.laya ?? []).filter(\.unsure).count
            Text(unsure == 0 ? WS.t("answer.sure") : WS.t("answer.unsure", ["n": "\(unsure)"])).font(.caption).foregroundStyle(Palette.inkSoft)
        case .rulesOnly(let why):
            VStack(alignment: .leading, spacing: 4) {
                Text(rulesText(why)).font(.footnote).foregroundStyle(Palette.ink)
                    .accessibilityIdentifier("web.answer.rulesOnly")
                switch why {
                case .layaOff: LayaOffBanner(feature: Features.shared.FLIGHTS)
                case .refused: EmptyView()
                case .modelNotInstalled, .modelFailed:
                    GetLayaButton(title: WS.t("getModel"), id: "web.answer.getModel")
                }
            }
        case .cancelled:
            HStack {
                Text(WS.t("answer.cancelled")).font(.footnote).foregroundStyle(Palette.inkSoft)
                Spacer()
                Button(WS.t("answer.retry")) { library.retryLaya(sector) }.font(.footnote.weight(.semibold))
            }
        }
    }

    private func rulesText(_ why: WebRun.Reason) -> String {
        switch why {
        case .modelNotInstalled: WS.t("rulesOnly.notInstalled")
        case .modelFailed(let m): WS.t("rulesOnly.failed", ["m": m])
        case .refused(let r): WS.t("rulesOnly.refused", ["m": r.joined(separator: "; ")])
        case .layaOff: WS.t("rulesOnly.off")
        }
    }

    /// The provider's required attribution, linked, wherever its data is shown.
    private func attribution(_ a: SearchAttribution) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let url = URL(string: a.url) {
                Link(a.text, destination: url).font(.caption).foregroundStyle(Palette.cyan)
                    .accessibilityIdentifier("web.attribution")
            } else {
                Text(a.text).font(.caption).foregroundStyle(Palette.inkSoft).accessibilityIdentifier("web.attribution")
            }
            if let p = a.providersUrl, let url = URL(string: p) {
                Link(WS.t("attribution.providers"), destination: url).font(.caption2).foregroundStyle(Palette.cyan)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One answered row: the verdict, the facts, the provider record and a link to check it.
struct WebRowCard: View {
    let item: WebAnswerRow
    let sector: WebSector

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("#\(item.rank)").font(Typeface.display(20)).foregroundStyle(item.value >= 0.5 ? Palette.blue : Palette.inkSoft)
                Text(item.row.title).font(.headline).foregroundStyle(Palette.ink).lineLimit(1)
                Spacer()
                if item.unsure {
                    Pill(text: WS.t("row.unsure"), color: Palette.amber, symbol: "questionmark").accessibilityIdentifier("web.row.unsure")
                }
            }
            Text(item.row.detail).font(Typeface.mono(12)).foregroundStyle(Palette.ink)
            Text(item.label).font(.caption.weight(.semibold)).foregroundStyle(item.value >= 0.5 ? Palette.mint : Palette.inkSoft)
            if let note = item.note { Label(note, systemImage: "scissors").font(.caption2).foregroundStyle(Palette.amber) }
            HStack(alignment: .firstTextBaseline) {
                Text(WS.t("row.source", ["item": item.row.sourceItem])).font(Typeface.mono(10)).foregroundStyle(Palette.inkSoft)
                    .textSelection(.enabled)
                Spacer()
                if let link = item.row.link {
                    Link(WS.t("row.open", ["provider": sector.provider]), destination: link).font(.caption2.weight(.semibold))
                        .accessibilityIdentifier("web.row.link")
                }
            }
        }
        .card()
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("web.row")
    }
}
