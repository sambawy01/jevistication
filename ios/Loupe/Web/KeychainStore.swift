import Foundation
import Security

protocol KeyStore: AnyObject {
    func read() -> String?
    func save(_ value: String) throws
    func delete() throws
}

/// Generic-password Keychain item, readable only while unlocked, never synced or backed up
/// off this device (kSecAttrAccessibleWhenUnlockedThisDeviceOnly).
final class KeychainStore: KeyStore {
    struct Failure: Error, Equatable { let status: OSStatus }

    let service: String
    let account: String

    init(service: String = "com.loupe-ai.ios.duffel", account: String = "duffel-access-token") {
        self.service = service
        self.account = account
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    func read() -> String? {
        var q = baseQuery
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    /// Status of a plain existence query; `errSecItemNotFound` after `delete()`.
    func queryStatus() -> OSStatus {
        var q = baseQuery
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        return SecItemCopyMatching(q as CFDictionary, nil)
    }

    func accessibility() -> String? {
        var q = baseQuery
        q[kSecReturnAttributes as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let a = out as? [String: Any] else { return nil }
        return a[kSecAttrAccessible as String] as? String
    }

    func save(_ value: String) throws {
        SecItemDelete(baseQuery as CFDictionary)
        var q = baseQuery
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let s = SecItemAdd(q as CFDictionary, nil)
        guard s == errSecSuccess else { throw Failure(status: s) }
    }

    func delete() throws {
        let s = SecItemDelete(baseQuery as CFDictionary)
        guard s == errSecSuccess || s == errSecItemNotFound else { throw Failure(status: s) }
    }
}

/// In-memory store for UI tests and fixture screenshots (DEBUG launch flags only).
final class MemoryKeyStore: KeyStore {
    private var value: String?
    init(_ value: String? = nil) { self.value = value }
    func read() -> String? { value }
    func save(_ value: String) throws { self.value = value }
    func delete() throws { value = nil }
}

enum DuffelKey {
    /// Duffel access tokens start with duffel_test_ or duffel_live_.
    static func validate(_ raw: String) -> String? {
        let k = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        for p in ["duffel_test_", "duffel_live_"] where k.hasPrefix(p) && k.count > p.count + 8 {
            if k.allSatisfy({ !$0.isWhitespace }) { return k }
        }
        return nil
    }
    static func isLive(_ k: String) -> Bool { k.hasPrefix("duffel_live_") }
}
