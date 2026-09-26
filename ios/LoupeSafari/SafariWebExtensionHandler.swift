import Foundation
import LoupeKit
import os
import SafariServices

/// Loupe for Safari's native side (2026-09-26): Safari hands every `browser.runtime.sendNativeMessage`
/// from the extension's background script to this handler; the work is `SafariVerdictEngine` (shared
/// with the app, where tests drive it without Safari). Only website names arrive here.
final class SafariWebExtensionHandler: NSObject, NSExtensionRequestHandling {
    /// One engine per extension process, so verdicts and domain facts stay cached while Safari keeps
    /// the process alive (memory only; nothing about sites is written except the Spotted log).
    private static let engine = SafariVerdictEngine(online: ExtensionOnlineLookups())
    private static let logger = Logger(subsystem: "com.loupe-ai.ios.safari", category: "verdict")

    func beginRequest(with context: NSExtensionContext) {
        let item = context.inputItems.first as? NSExtensionItem
        guard let message = item?.userInfo?[SFExtensionMessageKey] as? [String: Any] else {
            Self.reply(context, ["ok": false, "error": "bad_message"])
            return
        }
        Task {
            let out = await Self.engine.handle(message)
            if let level = out["level"] as? String {
                // Timing and memory for the report; the website name is private in the log.
                Self.logger.notice("verdict \(level, privacy: .public) host=\(out["host"] as? String ?? "", privacy: .private) ms=\(out["ms"] as? Double ?? -1, privacy: .public) memoryMB=\(out["memoryMB"] as? Double ?? -1, privacy: .public) cached=\(out["cached"] as? Bool ?? false, privacy: .public) online=\(message["online"] as? Bool ?? true, privacy: .public)")
            }
            Self.reply(context, out)
        }
    }

    private static func reply(_ context: NSExtensionContext, _ out: [String: Any]) {
        let response = NSExtensionItem()
        response.userInfo = [SFExtensionMessageKey: out]
        context.completeRequest(returningItems: [response], completionHandler: nil)
    }
}

/// The two online lookups Loupe for Safari may make, each for a registrable domain only and only
/// while its switch is on in the app: the web helper's domain facts (3 s at most) and the domain
/// blocklists through this iPhone's own DNS resolver. Safe Browsing is not used here (the user's key
/// stays in the app's Keychain).
final class ExtensionOnlineLookups: SafariOnlineLookups {
    private let client: DomainFactsClient
    private let site = SiteFactsLookup()

    init() {
        let cfg = URLSessionConfiguration.ephemeral          // no cookies, no cache on disk
        cfg.urlCache = nil
        cfg.waitsForConnectivity = false
        cfg.timeoutIntervalForRequest = SafariVerdictEngine.onlineTimeout
        client = DomainFactsClient(session: URLSession(configuration: cfg))
    }

    func domainFacts(_ domain: String) async throws -> DomainFacts {
        try await client.facts(for: domain, timeout: SafariVerdictEngine.onlineTimeout)
    }

    func blocklists(_ domain: String, lists: [String], at: String) async -> [String: DnsblResult] {
        let site = site
        return await Task.detached(priority: .userInitiated) {
            site.lookup(domains: [domain], dns: false, lists: lists, at: at).dnsbl[domain] ?? [:]
        }.value
    }
}
