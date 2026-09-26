import LoupeKit
import SwiftUI

/// Guard → Protection: link and site protection. Browsing protection (2026-09-26, `ios/Loupe/Protection/`) first:
/// Loupe for Safari (the Safari Web Extension; a banner with one button while it is off), Check a link, and the
/// Spotted log (Safari, shared links and checks). Then what protects you in mail: the mail link checks (Mail
/// triage's `SiteCheckResult`s, from `MailTriageService`: every link in mail and in other items, scored by the
/// phishing formula shared with Loupe Station), with the way into Mail triage, and the entry to Me → Online phishing
/// checks (the opt-in online facts, which Loupe for Safari follows too).
struct GuardProtectionSection: View {
    @ObservedObject var mail: MailTriageService = .shared
    @ObservedObject var online: OnlineChecksService = .shared

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Safari, Check a link, Spotted.
            ProtectionSectionContent()
            // Link checks from mail (and web links found in other items).
            NavigationLink(value: GuardRoute.mail) { MailLinkChecksSummary(mail: mail) }
                .buttonStyle(.plain)
                .accessibilityIdentifier("guard.protection.mail")
            NavigationLink(value: GuardRoute.onlineChecks) { onlineChecksRow }
                .buttonStyle(.plain)
                .accessibilityIdentifier("guard.protection.onlineChecks")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("guard.protection")
    }

    private var onlineChecksRow: some View {
        HStack(spacing: 12) {
            NeonIcon(name: "globe", color: online.settings.anyOn ? Palette.blue : Palette.inkSoft, size: 20)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text("Online phishing checks").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                Text(online.settings.anyOn ? "On · facts that only exist online, each labelled Online" : "Off · every check runs on this iPhone")
                    .font(.caption).foregroundStyle(Palette.inkSoft)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Pill(text: online.settings.anyOn ? "Online" : "Off", color: online.settings.anyOn ? Palette.blue : Palette.inkSoft,
                 symbol: online.settings.anyOn ? "globe" : "power")
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
        }
        .frame(minHeight: 44)
        .card()
        .accessibilityElement(children: .combine)
        .accessibilityHint("Me, Online phishing checks")
    }
}

/// The mail link checks in numbers: links checked, how many warn, possible phishing mail.
struct MailLinkChecksSummary: View {
    @ObservedObject var mail: MailTriageService

    /// Every link check the last triage ran: links in mail, then web links found in other items.
    static func checks(_ s: MailSummary) -> [SiteCheckResult] {
        s.rows.flatMap(\.linkChecks) + s.webLinks.map(\.check)
    }

    var body: some View {
        let checks = mail.summary.map(Self.checks) ?? []
        let warn = checks.filter(\.warn).count
        let phishing = Int(mail.summary?.phishingCount ?? 0)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                NeonIcon(name: "envelope.badge.shield.half.filled", color: Palette.blue, size: 22).frame(width: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Link checks in your mail").font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink)
                    Text("Where each link really goes, and each sender's domain, scored on this iPhone.")
                        .font(.caption).foregroundStyle(Palette.inkSoft)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(Palette.inkSoft).accessibilityHidden(true)
            }
            if mail.summary == nil {
                Text(mail.running ? "Checking your mail's links" : "Not checked yet")
                    .font(Typeface.mono(11)).foregroundStyle(Palette.inkSoft)
            } else {
                HStack(spacing: 0) {
                    stat("\(checks.count)", "links checked", Palette.ink)
                    stat("\(warn)", warn == 1 ? "warns" : "warn", warn > 0 ? Palette.warnText : Palette.ink)
                    stat("\(phishing)", "possible phishing", phishing > 0 ? Palette.dangerText : Palette.ink)
                }
            }
        }
        .card()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(mail.summary == nil ? "Link checks in your mail: not checked yet"
                            : "Link checks in your mail: \(checks.count) links checked, \(warn) warn, \(phishing) possible phishing")
        .accessibilityAddTraits(.isButton)
    }

    private func stat(_ n: String, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(n).font(Typeface.mono(22, weight: .bold)).monospacedDigit().foregroundStyle(color)
            Text(label).font(.caption2).foregroundStyle(Palette.inkSoft)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
