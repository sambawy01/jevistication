import Foundation
import Security

/// Deletes a Keychain service's every item (all accounts). The app's is `SystemKeychainWiper`; tests pass a fake.
protocol KeychainWiping {
    /// True when the service has no item left (deleted, or there was none).
    func deleteAll(service: String) -> Bool
}

struct SystemKeychainWiper: KeychainWiping {
    func deleteAll(service: String) -> Bool {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        let s = SecItemDelete(q as CFDictionary)
        return s == errSecSuccess || s == errSecItemNotFound
    }
}

/// "Delete all my Loupe data" (Me → Your data; audit P1-4, 2026-09-27), the part that touches storage. Pure over
/// what it is given, so the tests run it on throwaway folders, suites and a fake Keychain. `LoupeDataReset` runs it
/// over the app's real places and then resets the services that hold the same data in memory.
///
/// What goes: every file in Loupe's home (Application Support/Loupe: the decision ledger, judgments, corrections,
/// the review queue, the sources' caches and indexes, file bookmarks, the Inbox, the mail cache and account, held
/// privacy copies, model settings); the App Group's Loupe folders (the Spotted log, recent link checks, the shared
/// inbox, downloaded phishing lists); the app's and the App Group's settings; every Loupe Keychain item (mail
/// passwords and tokens, the writing assistant's key, the Google Safe Browsing key, the flights key). The decision
/// model's files, its download and its own settings stay when `keepModel` is set.
enum DataEraser {
    /// Every Keychain service Loupe writes (KeychainStore callers).
    static let keychainServices = ["com.loupe-ai.ios.mail", "com.loupe-ai.ios.assistant",
                                   "com.loupe-ai.ios.safebrowsing", "com.loupe-ai.ios.duffel"]
    /// The decision model's folders inside Loupe's home (`LayaModelStore`: the files and the partial download).
    static let modelFolders: Set<String> = ["laya-multilingual", "laya-download"]
    /// The App Group's Loupe folders (`ProtectionGroup`, `SharedInbox`).
    static let groupFolders = ["online-phishing", "protection", "SharedInbox"]

    /// The model's own settings (variant, mobile data, consent, download state), kept with the model.
    static func isModelKey(_ key: String) -> Bool { key.hasPrefix("laya.") }

    struct Plan {
        /// Loupe's home (on a phone the ledger's and the sources' are the same folder; under DEBUG fixtures each is
        /// its own throwaway folder, so every one is listed).
        var homes: [URL]
        /// The App Group container (nil without the entitlement: then those folders are in `home`).
        var group: URL?
        /// Settings domains to empty: (the defaults, its persistent-domain name).
        var defaults: [(UserDefaults, String)]
        var keepModel: Bool
        var keychain: KeychainWiping
        var fileManager: FileManager = .default
    }

    struct Report: Equatable {
        var removed: [String] = []
        var failed: [String] = []
        var keychainFailed: [String] = []
        var ok: Bool { failed.isEmpty && keychainFailed.isEmpty }
    }

    /// Runs the plan. Never throws: what could not be removed is listed in the report.
    static func erase(_ plan: Plan) -> Report {
        var report = Report()
        let keep = plan.keepModel ? modelFolders : []
        var seen = Set<String>()
        let homes = plan.homes.filter { seen.insert($0.standardizedFileURL.path).inserted }
        for home in homes { removeContents(of: home, keeping: keep, fm: plan.fileManager, into: &report) }
        if let group = plan.group, !homes.contains(where: { $0.standardizedFileURL == group.standardizedFileURL }) {
            for name in groupFolders {
                let url = group.appendingPathComponent(name, isDirectory: true)
                guard plan.fileManager.fileExists(atPath: url.path) else { continue }
                do { try plan.fileManager.removeItem(at: url); report.removed.append("group/" + name) } catch { report.failed.append("group/" + name) }
            }
        }
        for (d, domain) in plan.defaults { wipe(d, domain: domain, keepModel: plan.keepModel) }
        for service in keychainServices where !plan.keychain.deleteAll(service: service) {
            report.keychainFailed.append(service)
        }
        return report
    }

    /// Removes every child of `dir` except the names in `keep`.
    static func removeContents(of dir: URL, keeping keep: Set<String>, fm: FileManager, into report: inout Report) {
        guard let names = try? fm.contentsOfDirectory(atPath: dir.path) else { return }
        for name in names.sorted() where !keep.contains(name) {
            do {
                try fm.removeItem(at: dir.appendingPathComponent(name))
                report.removed.append(name)
            } catch {
                report.failed.append(name)
            }
        }
    }

    /// Empties a settings domain, keeping the model's own keys when the model stays.
    static func wipe(_ d: UserDefaults, domain: String, keepModel: Bool) {
        let kept = keepModel ? (d.persistentDomain(forName: domain) ?? [:]).filter { isModelKey($0.key) } : [:]
        d.removePersistentDomain(forName: domain)
        for (k, v) in kept { d.set(v, forKey: k) }
    }
}

extension Notification.Name {
    /// Posted after "Delete all my Loupe data": RootView goes back to onboarding.
    static let loupeDataErased = Notification.Name("com.loupe-ai.ios.dataErased")
}

/// Runs `DataEraser` over the app's real places and resets every service that holds the same data in memory, so
/// the app continues as a fresh install (back to onboarding) without a relaunch.
@MainActor
enum LoupeDataReset {
    static func eraseEverything(keepModel: Bool) async -> DataEraser.Report {
        // Stop what is writing first: a judgment run, the sort.
        JudgmentsService.shared.cancelSweep()
        RunCoordinator.shared.cancel()
        SortService.shared.cancel()
        if !keepModel { LayaModel.shared.remove() }
        // Protection's own stores clear through their APIs (the extensions are told), then the folders go.
        ProtectionStore.shared.clearSpotted()
        ProtectionStore.shared.clearRecent()
        var domains: [(UserDefaults, String)] = []
        if let id = Bundle.main.bundleIdentifier { domains.append((.standard, id)) }
        if let group = UserDefaults(suiteName: ProtectionGroup.id) { domains.append((group, ProtectionGroup.id)) }
        let plan = DataEraser.Plan(homes: [LedgerService.shared.home, SourcesService.shared.home], group: ProtectionGroup.container(), defaults: domains,
                                   keepModel: keepModel, keychain: SystemKeychainWiper())
        // On the ledger's queue, so no write lands between the delete and the reopen.
        let report = LedgerService.shared.eraseAndReopen { DataEraser.erase(plan) }
        // What the services hold in memory.
        SourcesService.shared.reloadAfterErase()
        JudgmentsService.shared.reloadAfterErase()
        WatchersService.shared.forgetAfterErase()
        PrivacyService.shared.forgetAfterErase()
        MailTriageService.shared.forgetAfterErase()
        SortService.shared.forgetAfterErase()
        // The last run, the nightly checkpoint and "the first check has run" went with the home (run/state.json).
        RunCoordinator.shared.forgetAfterErase()
        ReviewService.shared.reopenAfterErase()
        ProtectionStore.shared.reload()
        ClipboardMonitor.shared.enabled = ClipboardShared.enabled(ProtectionGroup.defaults)
        ModelSettingsService.shared.restoreAll()
        AssistService.shared.removeAll()
        OnlineChecksService.shared.deleteKey()
        OnlineChecksService.shared.update(OnlinePhishingSettings.load(.standard))
        ModelReadiness.shared.recheck()
        // Privacy and mail triage re-run over the (now empty) sources: their results clear.
        await PrivacyService.shared.run()
        await MailTriageService.shared.run()
        NotificationCenter.default.post(name: .loupeDataErased, object: nil)
        return report
    }
}
