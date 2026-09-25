#if DEBUG
import Foundation
import LoupeKit

/// `-LoupeDiag` (DEBUG only): checks on a real device what the owner reported, printing `LOUPE-DIAG` lines to
/// stdout (read with `xcrun devicectl device process launch --console`). Counts, states and server codes only.
/// 1. the bundled sample resolves to a file that exists (Open original);
/// 2. a sample rescan drives a `source_scan` live run with real counters;
/// 3. Laya opens (status, load time);
/// 4. Gmail's real refusal for a made-up address maps to the specific sentence (no real account is used).
@MainActor
enum DeviceDiag {
    static var enabled: Bool { ProcessInfo.processInfo.arguments.contains("-LoupeDiag") }

    static func say(_ s: String) { print("LOUPE-DIAG \(s)"); fflush(stdout) }

    /// Every job that finishes while the app runs: who answered (Laya by model, rules, baseline).
    static func watchJobs() {
        Task { @MainActor in
            var seen = Set<String>()
            var lastRunning = ""
            for _ in 0..<3600 {
                try? await Task.sleep(nanoseconds: 500_000_000)
                let snap = ActivityCenter.shared.snapshot
                let running = snap.running.map { "\($0.kind):read=\($0.counters["read"]?.intValue ?? 0):laya=\($0.sharesSource["laya"]?.intValue ?? 0):rule=\($0.sharesSource["rule"]?.intValue ?? 0)" }.joined(separator: " ")
                if running != lastRunning, !running.isEmpty, Int.random(in: 0..<6) == 0 { say("running \(running)"); lastRunning = running }
                for j in snap.finished where !seen.contains(j.id) {
                    seen.insert(j.id)
                    let src = ["laya", "rule", "baseline", "personal"].map { "\($0)=\(j.sharesSource[$0]?.intValue ?? 0)" }.joined(separator: " ")
                    say("job.finished kind=\(j.kind) view=\(j.view ?? "-") state=\(j.state) read=\(j.counters["read"]?.intValue ?? 0) multilingual=\(j.sharesModel["multilingual"]?.intValue ?? 0) \(src)")
                }
            }
        }
    }

    static func run(_ sources: SourcesService) {
        guard enabled else { return }
        watchJobs()
        guard !ProcessInfo.processInfo.arguments.contains("-LoupeDiagJobsOnly") else { return }
        Task { @MainActor in
            // 1. Open original for the sample
            let items = sources.sampleScan?.result.items ?? []
            say("sample.items=\(items.count) bundle=\(SourcesService.bundledSample()?.path ?? "nil")")
            if let item = items.first(where: { $0.path.contains("passport-scan-SPECIMEN") }) ?? items.first(where: { $0.kind != .email }) {
                say("sample.cachedPathExists=\(FileManager.default.fileExists(atPath: item.path))")
                switch LiveItemResolver.live.target(for: item) {
                case .file(let url, _): say("sample.open=file exists=\(FileManager.default.fileExists(atPath: url.path)) quicklook=\(ItemOpenPolicy.allows(url))")
                case .unavailable(let why): say("sample.open=unavailable \(why)")
                case .photo: say("sample.open=photo")
                case .mail: say("sample.open=mail")
                }
            }
            // 3. Laya
            let laya = LayaModel.shared
            say("laya.installed=\(laya.isInstalled) status=\(laya.status)")
            let began = Date()
            let backend = await laya.backend()
            say("laya.open=\(backend != nil ? "ok" : "failed") seconds=\(String(format: "%.1f", Date().timeIntervalSince(began))) status=\(laya.status)")
            // 2. The Sources live run
            sources.scanSample()
            var last = ""
            for _ in 0..<600 {
                try? await Task.sleep(nanoseconds: 100_000_000)
                guard let j = ActivityCenter.shared.latest("sources") else { continue }
                let line = "sources.run kind=\(j.kind) state=\(j.state) done=\(j.progress?.done ?? -1)/\(j.progress?.total?.doubleValue ?? -1) read=\(j.counters["read"]?.intValue ?? 0) skipped=\(j.gates["skipped"]?.intValue ?? 0) ocr=\((j.meta["ocr_files"] as? NSNumber)?.intValue ?? 0) eta=\(j.etaS?.doubleValue ?? -1)"
                if line != last { say(line); last = line }
                if !j.running { break }
            }
            // 4. Gmail, with a made-up address: never a real account.
            let client = IMAPClient(transport: NWIMAPTransport(host: "imap.gmail.com"))
            do {
                _ = try await client.sync(username: "loupe.probe.nonexistent.9f3k2@gmail.com",
                                          credential: .password(MailInput.secret("abcd efgh ijkl mnop", host: "imap.gmail.com")),
                                          knownUidValidity: nil, afterUid: 0, maxMessages: 1)
                say("gmail=unexpected success")
            } catch let f as IMAPClient.Failure {
                say("gmail.kind=\(f.kind) server=\(IMAPClient.Failure.brief(f.detail))")
                say("gmail.shown=\(f.recovery(host: "imap.gmail.com"))")
            } catch {
                say("gmail.error=\(error.localizedDescription)")
            }
            say("done")
        }
    }
}
#endif
