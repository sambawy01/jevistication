import Foundation

/// Me's row values, pure for the tests.
enum MeModel {
    static func readsLine(on: Int) -> String { on == 0 ? "none on" : "\(on) on" }

    static func mailLine(account: String?, on: Bool) -> String {
        guard let account else { return "No mailbox yet" }
        return on ? account : "\(account) · off"
    }

    /// The Personal Assistant row (spec §3): a placeholder in phase 1 that opens today's assistant settings; the
    /// paid tier (step 2 on `agent-tier`) replaces it.
    static func assistantLine(enabled: Bool, ready: Bool) -> String {
        guard enabled else { return "Unlock" }
        return ready ? "On · Online" : "Needs setup"
    }
}
