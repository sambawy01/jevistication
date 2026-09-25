import os

/// Device logs (Console / `log stream --device`), subsystem `dev.loupe.app`. Counts, states and server
/// codes only: never an item's name, text, an address or a secret.
enum Log {
    static let mail = Logger(subsystem: "dev.loupe.app", category: "mail")
    static let laya = Logger(subsystem: "dev.loupe.app", category: "laya")
    static let sources = Logger(subsystem: "dev.loupe.app", category: "sources")
    static let items = Logger(subsystem: "dev.loupe.app", category: "items")
}
