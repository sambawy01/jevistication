// Loupe for Safari: the background script (2026-09-26).
//
// The content script sends the website name of each top-level page (never the address or the page).
// This asks Loupe's native side for a verdict, caches it per website name, badges the toolbar
// button on flagged pages and remembers "Continue anyway" per website name for this Safari session
// (browser.storage.session: memory only, gone when Safari closes).
//
// Two steps when Loupe's online checks are on in the app: the on-device verdict first (the
// mechanical checks and the lists on this iPhone, answered in milliseconds), then the one with the
// online facts, which updates the page's warning if it changes.

const TTL_MS = 30 * 60 * 1000;
const cache = new Map();          // "https://host" -> { v, at }
const tabVerdicts = new Map();    // tabId -> verdict (for the toolbar popup)
let memoryAllowed = new Set();    // fallback when storage.session is missing

function native(message) {
    // Safari routes every native message to this extension's own handler; the id is ignored.
    return browser.runtime.sendNativeMessage("com.loupe-ai.ios", message);
}

async function allowedHosts() {
    try {
        const r = await browser.storage.session.get("allowed");
        return new Set(r.allowed || []);
    } catch (e) {
        return memoryAllowed;
    }
}

async function allow(host) {
    const set = await allowedHosts();
    set.add(host);
    memoryAllowed = set;
    try { await browser.storage.session.set({ allowed: Array.from(set) }); } catch (e) { /* memory fallback */ }
}

function badge(tabId, v) {
    if (tabId === undefined || tabId === null) return;
    if (v) tabVerdicts.set(tabId, v);
    const level = v && v.ok ? v.level : "safe";
    const text = level === "dangerous" ? "!" : level === "suspicious" ? "?" : "";
    try {
        browser.action.setBadgeText({ tabId, text });
        if (text) browser.action.setBadgeBackgroundColor({ tabId, color: level === "dangerous" ? "#EF4444" : "#F59E0B" });
    } catch (e) { /* older Safari: no badge */ }
}

async function check(msg, sender) {
    const host = String(msg.host || "").toLowerCase();
    const scheme = msg.scheme === "http" ? "http" : "https";
    if (!host) return { ok: false };
    const tabId = sender && sender.tab ? sender.tab.id : undefined;
    const allowed = (await allowedHosts()).has(host);
    const key = scheme + "://" + host;
    const hit = cache.get(key);
    if (hit && Date.now() - hit.at < TTL_MS && !hit.v.onlinePending) {
        badge(tabId, hit.v);
        return Object.assign({}, hit.v, { allowed });
    }
    let v;
    try {
        v = await native({ type: "verdict", host, scheme, allowed, online: false });
    } catch (e) {
        return { ok: false, error: "native_unavailable" };
    }
    if (!v || !v.ok) return v || { ok: false };
    cache.set(key, { v, at: Date.now() });
    badge(tabId, v);
    if (v.onlinePending) {
        native({ type: "verdict", host, scheme, allowed, online: true }).then((v2) => {
            if (!v2 || !v2.ok) return;
            cache.set(key, { v: v2, at: Date.now() });
            badge(tabId, v2);
            if (tabId !== undefined) {
                browser.tabs.sendMessage(tabId, { type: "loupe.update", verdict: Object.assign({}, v2, { allowed }) }).catch(() => {});
            }
        }).catch(() => {});
    }
    return Object.assign({}, v, { allowed });
}

browser.runtime.onMessage.addListener((msg, sender) => {
    if (!msg || typeof msg.type !== "string") return undefined;
    switch (msg.type) {
    case "loupe.check":
        return check(msg, sender);
    case "loupe.continue":
        return allow(String(msg.host || "").toLowerCase())
            .then(() => native({ type: "action", host: msg.host, action: "continue" }))
            .catch(() => ({ ok: false }));
    case "loupe.back":
        return native({ type: "action", host: msg.host, action: "back" }).catch(() => ({ ok: false }));
    case "loupe.tabVerdict":
        return Promise.resolve(tabVerdicts.get(msg.tabId) || null);
    default:
        return undefined;
    }
});

browser.tabs.onRemoved.addListener((tabId) => tabVerdicts.delete(tabId));

// Tell Loupe the extension runs (the app's "on" hint). A time only is kept, never a site.
native({ type: "hello" }).catch(() => {});
