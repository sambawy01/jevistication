// Loupe for Safari: the content script (2026-09-26). Top frame only, at document start.
//
// Sends this page's website name (location.hostname, never the address, path or content) to the
// background script and, for a suspicious or dangerous site, covers the page with Loupe's warning:
// the reasons, "Go back" and "Continue anyway" (remembered for this website for the Safari session).
// Websites with no warning signs get no UI at all.
(() => {
    if (window.top !== window) return;
    const scheme = location.protocol.replace(":", "");
    const host = (location.hostname || "").toLowerCase();
    if (!host || (scheme !== "http" && scheme !== "https")) return;

    let root = null;
    let savedOverflow = null;

    function remove() {
        if (root) { root.remove(); root = null; }
        if (savedOverflow !== null) { document.documentElement.style.overflow = savedOverflow; savedOverflow = null; }
    }

    function el(tag, attrs, text) {
        const e = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
        if (text !== undefined) e.textContent = text;
        return e;
    }

    function goBack() {
        browser.runtime.sendMessage({ type: "loupe.back", host }).catch(() => {});
        const here = location.href;
        if (history.length > 1) history.back();
        setTimeout(() => { if (location.href === here) location.replace("about:blank"); }, 400);
    }

    function proceed() {
        browser.runtime.sendMessage({ type: "loupe.continue", host }).catch(() => {});
        remove();
    }

    const STYLE = `
        :host { all: initial; }
        .scrim { position: fixed; inset: 0; z-index: 2147483647; background: #070B18; color: #EAF0FF;
            font: 16px/1.45 -apple-system, BlinkMacSystemFont, "Helvetica Neue", sans-serif; overflow-y: auto;
            -webkit-text-size-adjust: 100%; }
        .card { max-width: 560px; margin: 0 auto; padding: max(28px, env(safe-area-inset-top)) 22px 32px; }
        .badge { display: inline-block; font: 600 12px/1 ui-monospace, Menlo, monospace; letter-spacing: .08em;
            padding: 6px 10px; border-radius: 999px; margin-bottom: 14px; }
        .dangerous .badge { color: #FCA5A5; background: rgba(239,68,68,.18); }
        .suspicious .badge { color: #FBBF24; background: rgba(245,158,11,.16); }
        h1 { font-size: 28px; line-height: 1.15; margin: 0 0 8px; font-weight: 700; }
        .site { font: 15px ui-monospace, Menlo, monospace; color: #A7B4D4; word-break: break-all; margin: 0 0 18px; }
        h2 { font-size: 13px; text-transform: uppercase; letter-spacing: .08em; color: #A7B4D4; margin: 18px 0 8px; font-weight: 600; }
        ul { margin: 0; padding-left: 20px; }
        li { margin: 6px 0; }
        .back { display: block; width: 100%; margin: 26px 0 12px; padding: 15px; border: 0; border-radius: 14px;
            font: 600 17px -apple-system, sans-serif; color: #041026; background: linear-gradient(90deg, #22D3EE, #6B95FF); }
        .go { display: block; width: 100%; padding: 12px; border: 1px solid rgba(59,130,246,.35); border-radius: 14px;
            font: 500 15px -apple-system, sans-serif; color: #A7B4D4; background: transparent; }
        .foot { margin-top: 22px; font-size: 13px; color: #A7B4D4; }
    `;

    function render(v) {
        if (!v || !v.ok || v.allowed || v.level === "safe" || v.host !== host) { if (v && v.ok && v.host === host) remove(); return; }
        remove();
        root = el("loupe-warning");
        const shadow = root.attachShadow({ mode: "closed" });
        const style = el("style");
        style.textContent = STYLE;
        const scrim = el("div", { class: "scrim " + v.level, role: "alertdialog", "aria-modal": "true", "aria-labelledby": "t" });
        const card = el("div", { class: "card" });
        card.append(el("div", { class: "badge" }, v.level === "dangerous" ? "LOUPE · DANGEROUS" : "LOUPE · SUSPICIOUS"));
        const title = v.level === "dangerous"
            ? (v.brand ? "This looks like a fake " + v.brand + " site" : "This site looks dangerous")
            : "This site looks suspicious";
        card.append(el("h1", { id: "t" }, title));
        card.append(el("p", { class: "site" }, v.domain && v.domain !== v.host ? v.domain + " (" + v.host + ")" : v.host));
        if (v.reasons && v.reasons.length) {
            card.append(el("h2", {}, "Why Loupe warns you"));
            const ul = el("ul");
            for (const r of v.reasons.slice(0, 5)) ul.append(el("li", {}, r));
            card.append(ul);
        }
        const back = el("button", { class: "back", type: "button" }, "Go back");
        back.addEventListener("click", goBack);
        const go = el("button", { class: "go", type: "button" }, "Continue anyway");
        go.addEventListener("click", proceed);
        card.append(back, go);
        card.append(el("p", { class: "foot" }, "Checked by Loupe on this iPhone. " + (v.privacy || "")));
        scrim.append(card);
        shadow.append(style, scrim);
        (document.documentElement || document).append(root);
        savedOverflow = document.documentElement.style.overflow || "";
        document.documentElement.style.overflow = "hidden";
        setTimeout(() => back.focus(), 0);
    }

    browser.runtime.onMessage.addListener((msg) => {
        if (msg && msg.type === "loupe.update") render(msg.verdict);
    });

    browser.runtime.sendMessage({ type: "loupe.check", host, scheme }).then(render).catch(() => {});
})();
