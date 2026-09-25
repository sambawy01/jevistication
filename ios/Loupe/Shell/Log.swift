import os

/// Device logs (Console / `log stream --device`), subsystem `com.loupe-ai.ios`. Counts, states and server
/// codes only: never an item's name, text, an address or a secret.
enum Log {
    static let mail = Logger(subsystem: "com.loupe-ai.ios", category: "mail")
    static let laya = Logger(subsystem: "com.loupe-ai.ios", category: "laya")
    static let sources = Logger(subsystem: "com.loupe-ai.ios", category: "sources")
    static let items = Logger(subsystem: "com.loupe-ai.ios", category: "items")
}
