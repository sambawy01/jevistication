// Loupe for Safari: the toolbar popup. Shows the verdict Loupe gave the page in the current tab.
(async () => {
    const out = document.getElementById("out");
    const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; };
    let v = null;
    try {
        const [tab] = await browser.tabs.query({ active: true, currentWindow: true });
        if (tab) v = await browser.runtime.sendMessage({ type: "loupe.tabVerdict", tabId: tab.id });
    } catch (e) { v = null; }
    if (!v || !v.ok) return;
    out.textContent = "";
    out.append(el("span", "level " + v.level, (v.title || v.level).toUpperCase()));
    out.append(el("h1", "", v.domain || v.host));
    if (v.reasons && v.reasons.length) {
        const ul = el("ul");
        for (const r of v.reasons.slice(0, 5)) ul.append(el("li", "", r));
        out.append(ul);
    }
    out.append(el("p", "muted", "Checked by Loupe on this iPhone. " + (v.privacy || "")));
})();
