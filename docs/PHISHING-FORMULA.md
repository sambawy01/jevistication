# The phishing / site formula

*Formula version 1.3 (self-vouching, email profile only) — **proposed by Loupe Station under the owner's
approval of the eight model fixes (fix 6), 2026-09-27; implemented in Station (0ff886d); mobile ported
2026-09-27** (`dev.loupe.kit.mail.Phishing` + `PhishingOwnWords`: all 8 v1.3 vectors pass on the JVM and the
iOS simulator, every v1.1 and v1.2 vector keeps its verdict).*

*Formula version 1.2 (site facts) — **approved by the owner 2026-09-24; implemented in Loupe Station;
mobile ported** (`loupe-kit` `dev.loupe.kit.site`, 2026-09-24: all 51 v1.2 vectors pass on the JVM and
the iOS simulator). Version 1.1 was decided by the owner 2026-09-24 (decisions A
and C); 55 of the 57 v1.1 vectors give the same verdict under v1.2; the two list-strength vectors (`online-feed-host`, `online-feed-domain`) move from danger to caution by design. One formula for Loupe
(iPhone, desktop, engine callers) and Loupe Station. It replaces the engine's `SiteFraud` rules and
Station's `browser/signals.py` + `scoring.py` + `mail/phishing.py` where they differed.*

- **Reference implementation:** `loupe-kit` — `dev.loupe.kit.site` (`SiteSignals`, `SiteScoring`,
  `OnlineSignals`, `SiteContext` (v1.2 tiers, payment context, facts), `Dns` (v1.2 DNS facts and
  blocklists), `PhishingLists` (list matching, `shared_hosts.json`, Phishing.Database), `Hosts`,
  `Brands`) for pages and links, `dev.loupe.kit.mail.Phishing` for email.
  The engine keeps a thin API (`OriginFacts`, `SiteFraud`, `Impersonation`: facts only, no verdict).
- **Test vectors:** [`phishing-vectors.json`](phishing-vectors.json) — 45 pages/links and 12 emails,
  each with the expected level, score, weighted signal codes and (pages) both PSL answers. Loupe runs
  them on the JVM and the iOS simulator (`PhishingFormulaTest`); Station must run the same file.
- A verdict is **never shown as "safe"**: the lowest level is displayed as **"No warning signs
  found"** on every surface (§4 of PRODUCT.md, "never blesses"; owner decision, v1.1). The wire
  code stays `safe`.

**Changelog**
- **v1.3 (2026-09-27, owner-approved model fix 6):** *a message that vouches for itself.* One new email
  signal, `self_vouching` **15** (§6.2): a sentence in the sender's own words says the message, its sender
  or its request is verified, approved, trusted or safe (English, MSA, Egyptian Arabic keyword rule), and
  the sender is unknown (not a known brand, not a trusted sender, not one of your contacts' addresses).
  Not a risk code: alone it never flags and never reaches caution. The page profile is unchanged. The
  version is recorded as `1.3` on page and email verdicts. New vectors
  [`phishing-vectors-v1.3.json`](phishing-vectors-v1.3.json) (8 emails; extends v1.2). Related input
  change, not a formula change: the decision model's `is_phishing` probability enters the formula
  calibrated (Station's `docs/model-calibration.md`); mobile's seam is `PhishingCalibration` (raw until
  the calibration file ships on the phone). Station's write-up: `docs/formula/phishing-formula-v1.3.md`.
- **v1.2 (approved 2026-09-24):** *site facts that reduce false alarms and add real evidence.* New opt-in
  inputs: DNS / email-authentication facts (§5b), domain blocklists (DNSBL, §5c), the hosts of a page's
  frames and scripts (§5d). New rules: a blocklist listing is a risk signal (`online_dnsbl_phish` 60,
  `online_dnsbl_spam` 35; error codes are never a listing); legitimacy tiers (§5d); the payment-context
  rule (a card request is expected on an established domain or a known payment processor's checkout);
  Laya's content cues need corroboration (§6.1); rule 4 exception: a card-only form posting to a known
  payment processor is not "elsewhere". Outputs: `facts` (reassuring / neutral, weight 0) and
  `notCounted` (cues that did not count, with the reason). New vectors
  [`phishing-vectors-v1.2.json`](phishing-vectors-v1.2.json) (51 pages); `phishing-vectors.json` (v1.1, 57)
  stays the v1.1 file. **List strength** (§5): only an exact-URL list hit is "danger" alone; a host hit
  counts 45 and a registrable-domain hit 30 unless corroborated, and an uncorroborated domain hit never
  reaches "danger" (the big domain lists, e.g. Phishing.Database's ~450k names, are noisy). This changes
  two v1.1 vectors, `online-feed-host` (danger 60 → caution 45) and `online-feed-domain` (danger 60 →
  caution 30); the other 55 give the same verdict. Email (`link_phish_list`, `sender_phish_list`) is
  unchanged in v1.2. Trigger: the owner's report on a restaurant site (`mazibeach.com`,
  2026-09-24) that showed "No warning signs found" with "won something or money back" and "asks for card
  details" listed under *Why*.
- **v1.1 (2026-09-24):** rule 3 treats Japanese (Han + Hiragana + Katakana) and Korean (Han +
  Hangul) as one script set each; the lowest level is displayed "No warning signs found"; the
  shared-hosting count is 12 non-PSL names (`run.app` is already a PSL suffix). New vectors
  `idn-japanese-mixed-kana`, `idn-korean-han-hangul`, `mixed-script-latin-cyrillic-brand`.
- **v1 (2026-09-24):** first shared formula.

---

## 1. Input normalisation

1. A URL is split like Python's `urlsplit` (scheme, userinfo, host, path, query; fragment dropped), with
   WHATWG's rules for the special schemes (http, https, ws, wss, ftp, file): C0 controls and spaces at
   both ends are stripped, the authority follows the scheme however many `/` or `\` come first
   (`https:\\x`, `https:/x`, `https:x`, `HTTPS:x` are all host `x`), a backslash is a slash, and the
   userinfo ends at the **last** `@` of the authority (brackets there are ordinary characters:
   `https://www.paypal.com[@evil.xyz` is evil.xyz). A mail link with any other scheme and no `//` is
   read as a bare host (`http://` is added) only when it starts with a letter or digit; anything else
   (`/x`, `?utm=1`, `*|UNSUB|*`, a no-break space first) is relative and opens no host. A `file:` URL
   has a host only after exactly `//` (*fix loops 3–4, 2026-09-27; Station to port*).
   A URL in running text ends at the first ideographic or full-width punctuation mark (U+3000–303F,
   U+FF01–FF0F, U+FF1A–FF20, U+FF3B–FF40, U+FF5B–FF65), Arabic `، ؛ ؟`, `|`, or `^` in its host; a
   `。．｡` in the host is a dot only when the label after it ends with a top-level domain on the Public
   Suffix List (`日本語。jp`, through further stand-in dots: `www.paypal.com．evil．xyz`) or, for `．`/`｡`,
   an ASCII letter or digit follows; else it ends the sentence (`详见www.example.com。谢谢`) (*fix loop 4*).
   A cut before the authority's last `@` (or a full-width `＠`) does not hide the host after it: when that
   host is another registrable domain, the URL keeps its userinfo (`https://www.paypal.com|login@paypa1-secure.xyz`
   is paypa1-secure.xyz); when it is the same, it is judged too. After any cut, every later piece of the
   token that starts with a URL or a host with a listed TLD is judged (`https://paypal.com，evil.com/login`),
   and a known or brand URL whose host a stop runs straight into another domain is `link_stitched` (30); a stop after the
   first URL's path began (`www.facebook.com/nileshoes｜www.nileshoes.com`) is two links side by side, not a stitch (*fix loops 5–6*).
   Hrefs are read as the browser reads them (*fix loops 5–7*): the HTML is tokenised in full with a stack of
   open elements and their namespaces, so SVG and MathML end where the tree builder ends them (breakout
   tags, `</p>`, `</br>`, an HTML ancestor's end tag) and integration points (`foreignObject`, `desc`,
   `title`, `mi` `mo` `mn` `ms` `mtext`, `annotation-xml`) hold HTML; comments, bogus comments and the text of
   script/style/textarea/title/xmp/iframe/noembed/noframes/plaintext in HTML hide no anchor and make none
   up. Every doubt leans toward finding more links: `<![CDATA[` always ends at the first `>`, `<template>`
   content is read, and a relative href is also judged as written and against every other absolute
   `<base href>`. Since fix loop 9 the links judged are the union of that tree-aware reading and a no-skip
   reading of the same tokens (no raw text, RCDATA, plaintext or SVG/MathML state skips anything), so every
   link start tag is judged: a wrong guess about the tree can only over-find. Since fix loop 10 the no-skip
   reading skips nothing at all, comments included (whether `<!--` opens a comment depends on the tree:
   inside an HTML `<style>` it is text). A link only the no-skip reading finds, and a reading resolved
   against a `<base href>` only it finds, is judged with no link text and raises only risk-weight codes
   (25 or more: a look-alike host, not a TLD note). With the raw mail at hand, the pattern fallback reads
   only text/plain parts and the visible text of HTML parts (as a client linkifies it), never the markup:
   a URL in a hidden MSO block, a comment or an image CDN is not a pattern link. Since fix loop 11 the
   tree-aware reading also judges, as verified links, what else a reader can open: a form's `action`
   (with no text) and each submit button's or submit/image input's target (`formaction`, else its form's
   action; its label is the link text), an SVG `<animate>`/`<set>` that sets `href`/`xlink:href` (`to`,
   `from`, `values`), `<iframe>`/`<frame>`/`<embed>` `src` and `<object data>` (no text), URLs in an
   `on…` event handler (no text), and the documents framed by `srcdoc` or a data:text/html URL (read in
   turn, three deep, with their own link text). The visible text follows the tree (an SVG/MathML
   `<style>` is not raw text; a breakout shows its text) and inline tags, comments and `<wbr>` do not
   split a URL. The links judged are ordered verified first, deduplicated (an unverified link whose host is
   already judged is dropped) and only then capped: 300 distinct hosts, 1,000 links. `<area href>` and
   `<meta http-equiv=refresh>` are links; a link's text joins across
   comments and leaves out an SVG `<title>`/`<desc>`'s own text; every HTML character reference in the attribute decoded (numeric and the
   full WHATWG named table), `href` only as a whole attribute name (`xlink:href` for an `<a>` inside
   `<svg>`), a relative href resolved against the document's first `<base href>` outside SVG/MathML;
   C0 controls, spaces and no-break spaces trimmed and tabs and newlines removed **before** the scheme
   is read (`ht\ntps://x` is https://x); two leading `/` or `\` in any mix are scheme-relative
   (`\\x`, `/\x`, `///x` are host x); one leading `/` or `\` before a host name with a listed TLD is
   judged as that host (stricter than a browser, as origin/main read scheme-less hrefs).
2. The host is lowercased, one trailing dot removed, and **converted to IDNA ASCII** when it contains
   non-ASCII (NFKC + lowercase + Punycode, IDNA 2003 without STD3). `https://pаypal.com` and
   `https://xn--pypal-4ve.com` are the same input.
3. A label is *decoded* back to Unicode (Punycode) for the script and confusable checks.
4. An email address's domain is the text after the last `@`, trimmed of `.` and `>`, lowercased,
   IDNA ASCII.

## 2. The two PSL modes (never conflated)

The Public Suffix List is Mozilla's, pinned (`engine/src/commonMain/resources/.../public_suffix_list.dat`,
version in the vectors file's `psl` field; refreshed only by `tools/update-psl.sh`).

| Mode | Used for | PSL sections | Result for `x.github.io` | `storage.googleapis.com` | `my-shop.wordpress.com` |
|---|---|---|---|---|---|
| **(a) Host control** | every scoring rule: who controls a host, brand ownership, "same site" for forms | ICANN **+ PRIVATE** | `x.github.io` (the account owner) | `storage.googleapis.com` (a customer bucket, not Google) | `wordpress.com` (the PSL does not list it; rule 5 adds a caution) |
| **(b) Online facts** | the one domain sent to the web helper for registration / certificate facts | ICANN only | **none** | **none** | **none** |

Rule (b): `onlineDomain(host)` = the ICANN-only eTLD+1, **or none** when the host is an IP, a private
name (`localhost`, `*.local`, RFC 1918, link-local), on a shared-hosting name (§3.5), or when the
full-PSL registrable domain differs from the ICANN one (the host sits under a PRIVATE suffix). So
x.github.io and storage.googleapis.com get **no online facts** — asking about github.io's or
googleapis.com's age says nothing about who runs the page. Vectors: `github-pages`,
`google-storage-bucket`, `netlify-deep`, `wordpress-customer`, `online-private-suffix-no-facts`,
`co-uk-subdomain` (`shop.example.co.uk` → `example.co.uk`).

## 3. Rules (owner decision A)

1. **Brand ownership.** Brands are Station's list (`Brands.BRANDS`, 45 brands, with official
   domains; `cctld` brands also own `token.<country TLD>`, e.g. `amazon.de`, `google.com.eg`). A
   brand owns exactly its listed domains: Microsoft owns `live.com`, `outlook.com`, Google owns
   `youtube.com`. A domain a listed brand owns is **known-good** (§5 guard rails). Only when a
   claimed brand is *not* on the list does the engine's name-vs-domain check apply
   (`OriginFacts.brandMatchesOrigin`: the registrable domain's first label, letters and digits only,
   equals the name). Mail adds `MAIL_BRANDS` and extra sender domains (Station's tables).
2. **Punycode / IDN.** An international name is **not** a signal by itself (`مثال.مصر`, `例子.中国`,
   `bücher.de` score 0). It is a signal only when (i) its decoded label's *skeleton* equals a brand
   token's skeleton and it contains non-ASCII (`homograph_brand` 60), or (ii) one label mixes scripts
   (`mixed_script` 35). Skeleton = NFKC, lowercase, the confusables table (Cyrillic/Greek/Latin
   look-alikes and digits: `а→a`, `о→o`, `1→l`, `0→o`, `i→l`, …), NFKD with combining marks
   removed, `rn→m`, `vv→w`, `cl→d`, `nn→m`, hyphens and underscores removed. Station's former
   `punycode` (10) and mail `sender_punycode` (10) are **removed**.
3. **Mixed scripts.** Per label, on the decoded label: the label mixes scripts when its letters have
   more than one Unicode Script property value (Common/Inherited ignored; the engine's
   `OriginFacts.hasMixedScripts`, generated script table). Digits of another script do not count.
   **Allowed script sets (v1.1):** Han + Hiragana + Katakana (any two or all three: Japanese) and
   Han + Hangul (Korean) are one writing system, not a mix, as is Latin with Common/Inherited.
   Any of them together with Latin, Cyrillic, Greek or any other script in one label is still
   mixed (`漢字abc`, `한국어ひらがな`). Vectors: `idn-japanese-mixed-kana`, `idn-korean-han-hangul`
   (score 0), `mixed-script-latin-cyrillic-brand` (`micrоsoft.com`, danger 60 — `homograph_brand`
   takes precedence over `mixed_script` on a brand confusable).
4. **Cross-domain form posts.** A form whose action's registrable domain (mode a) differs from the
   page's: **strong** when the form has a password or card field (`password_posts_elsewhere` 30,
   risk), **weak** for any other form (`form_posts_elsewhere` 5). A password/card form on https that
   posts over http: `password_posts_http` 25. Only the first offending form counts.
   **v1.2 exception:** a form with a card field and **no** password field whose action's registrable
   domain is a known payment processor (§5d `PAYMENT_PROCESSORS`) is that processor's checkout, not
   "elsewhere" (the next form is checked). A password posting to a processor still counts.
5. **Host control.** Mode (a) decides who controls a host. Station's shared-hosting list keeps two
   uses: `shared_hosting_login` 15 (a password/card form on any shared-hosting host), and — for the
   **twelve names the PSL does not list** (`000webhostapp.com`, `godaddysites.com`, `strikingly.com`,
   `jimdosite.com`, `wordpress.com`, `railway.app`, `serveo.net`, `weebly.com`, `site123.me`,
   `glitch.me`, `tilda.ws`, `loca.lt`) — `shared_hosting` 10 on any customer site under them
   (a subdomain other than `www`). A test fails if the PSL ever starts listing one of them.
6. **Contact impersonation and brand look-alikes** stay separate signals and feed one score (email
   profile, §6.2): `contact_homograph_domain` 60, `contact_lookalike_domain` 45,
   `contact_name_other_address` 30.

## 4. Page / link signals (weights)

"Risk" = weight ≥ 15. `known` = the registrable domain is known-good (rule 1). Checks run in this
order; each code counts once.

| Code | W | Condition |
|---|---|---|
| `data_url` | 40 | scheme `data:` or `blob:` (then no host checks) |
| `userinfo_in_url` | 30 | `user@` before the host, or a `\` before an `@` in the authority (`https://evil.com\@paypal.com` opens evil.com but reads as paypal.com) |
| `unreadable_url` | 30 | a URL with an explicit web scheme (http, https, ws, wss, ftp; or a scheme-relative href) or a `file:` URL with credentials or a port whose host is not empty, not only dots, and holds a forbidden host code point after percent-decoding (`https://paypal.com%40evil.com`, `%00`, `%09`, `%20`, DEL, `^`, `\|`) or a bracket that does not pair or does not hold an IPv6 address, or a port that is not 0–65535 in digits. Never for a relative or scheme-less href (`/`, `?utm=1`, a template tag such as `*\|UNSUB\|*`, one behind a no-break space) nor an empty or dots-only host (`https://`, `https://./`, `https://%2e/`), which open nothing (`Hosts.unreadableUrl`, *fix loops 3–4; Station to port*) |
| `url_shortener` | 10 | host or registrable domain on the shorteners list |
| `long_url` | 5 | whole URL > 200 characters |
| `encoded_url` | 5 | > 12 `%XX` in path + query |
| `brand_in_path` | 15 | not known; a brand's domain appears (dash-bounded) in the decoded path/query and the brand does not own the host |
| `ip_host` | 25 | host is an IP (dotted, decimal, hex, short forms, IPv6); private IPs never flagged. Stops the host checks |
| `homograph_brand` | 60 | rule 2 (i), on any `xn--` label (brand checks on the registrable label are then skipped) |
| `mixed_script` | 35 | rule 2 (ii) / rule 3 |
| `disguised_host` | 45 | checked **before** the known-good return, so also on a known domain: the host as written (or a decoded `xn--` label) has a stand-in character, i.e. a compatibility character (NFKC ≠ NFC: full-width, mathematical, enclosed, ligatures, superscripts; or the Kelvin, Ohm or Ångström sign) or a default ignorable IDNA removes (soft hyphen, variation selectors; ZWJ/ZWNJ excepted). `https://ｐａｙｐａｌ.com` reaches paypal.com but no real link is written that way: caution, and an impostor code (with a password field, danger). The full-width and halfwidth full stops (`．` `｡`) are stand-ins too, and a percent-encoded host when it decodes to a known or brand domain (`%70aypal.com`; `ex%61mple.com` is not flagged, and a host that is also `unreadable_url` counts once). A character only *canonically* equivalent to another (NFC changes it, NFKC adds nothing: Devanagari U+0958–095F, Bengali U+09DC/09DD/09DF, Gurmukhi U+0A33/0A36/0A59–0A5E, Greek oxia U+1F71) is **not** a stand-in: those scripts' keyboards type them (fix loop 4); the ideographic full stop `。` is not, as the host (`paypal。com` is paypal.com in every browser), but is in a mail address (§6.2) (`PortableText.disguisedCodePoints`, *added 2026-09-27, 45 and impostor since fix loop 3 to stay at origin/main's score; Station to port*) |
| `deviation_host` | 10 | the host contains `ß`, `ς`, ZWJ or ZWNJ, so IDNA 2003 (transitional) software reads another name than browsers (UTS #46 non-transitional, which every host decision uses). A note: real German, Greek and Persian names have them (*2026-09-27; Station to port*) |
| `deviation_known_host` | 45 | as `deviation_host`, and the transitional reading is a known-good, brand or configured domain (`americanexpreß.com`, `faß.de` when fass.de is known): an impostor code (*2026-09-27; Station to port*) |
| `unicode_drift_host` | 30 | checked before the known-good return too: the host as written (before lower-casing), or a decoded `xn--` label, has a character whose IDNA mapping differs between Unicode 3.2 nameprep (RFC 3491, IDNA 2003; a character unassigned in 3.2 passes through unchanged) and the pinned Unicode 16.0 mapping (B.1, then NFKC_Casefold), other than a `disguised_host` character (one character counts once). About 5,600 code points; new scripts and emoji that map to themselves are not drift. One written name can reach two websites on old and new software. Not an impostor code (`PortableText.unicode32Drift`, *added 2026-09-27; Station to port*) |
| `lookalike_brand` | 45 | registrable label (decoded) skeleton equals a token's, or a near miss: tokens ≤ 4 letters never; 5–7 letters one insertion, deletion or adjacent swap; ≥ 8 letters OSA distance 1, ≥ 10 letters 2 (whole label or any hyphen/dot token) |
| `brand_other_tld` | 20 | the registrable label **is** a token on a domain the brand does not own (`google.xyz`, `paypal.github.io`) |
| `brand_in_domain_bait` | 40 | a token glued to other words, one of them a PHISHY_WORD (`paypal-secure-login.com`) |
| `brand_in_domain` | 20 | a token glued to other words, none phishy (`apple-farm.com`) |
| `brand_domain_in_subdomain` | 45 | a brand's domain in the subdomain of a domain it does not own |
| `brand_in_subdomain` | 30 | otherwise a token (≥ 3 letters, by skeleton) in that subdomain |
| `many_subdomains` | 10 | ≥ 4 subdomain labels |
| `suspicious_tld` | 8 | last label on the suspicious-TLD list |
| `http_password` / `http_card` | 30 / 30 | the field over `http://` (not private hosts) |
| `password_posts_elsewhere` | 30 | rule 4, strong |
| `password_posts_http` | 25 | rule 4 |
| `form_posts_elsewhere` | 5 | rule 4, weak |
| `brand_mismatch_login` | 50 | the page presents itself as a brand (favicon host, `og:site_name`, title, or a caller's free-text claim) that does not own it (rule 1), and has a password/card field |
| `brand_mismatch` | 15 | the same without such a field |
| `shared_hosting` | 10 | rule 5, the twelve |
| `shared_hosting_login` | 15 | rule 5, any shared host, with a password/card field |
| `impostor_login` | 20 | added when a password/card field is on a page with an impostor code (`homograph_brand`, `lookalike_brand`, `brand_domain_in_subdomain`, `brand_in_subdomain`, `brand_in_domain_bait`, `brand_other_tld`, `mixed_script`, `userinfo_in_url`, `deviation_known_host`, `disguised_host`) |

Hosts are read as a browser reads them: WHATWG URL parsing for http(s) (a backslash is a slash, the
host is percent-decoded, `。．｡` separate labels) and UTS #46 **non-transitional** mapping; never the
transitional (IDNA 2003) form, which is only compared (`deviation_*`). The skeleton folds Latin small
capitals, maps `ß` to `ss` and drops joiners and default ignorables.
The host checks return nothing for a **known** domain (and the page claim is ignored there), except
`disguised_host` and `unicode_drift_host`, which are about how the name is written, not whose it is.
Laya's optional page answers (Station's `LAYA_POINTS`, `pressure_login` 10, caps 40 / 25) are
unchanged and not part of the vectors: the phone runs no model on pages.

## 5. Online facts (opt-in; PRODUCT.md §4a)

Only while the user turned the source on. Online reasons count as deterministic evidence; they are
**never applied to a known-good domain** (nor a trusted sender); each carries `online_source` and
`fetched_at` and is shown as "Online · <source> · fetched <time>".

| Page code | Mail codes | W | Condition |
|---|---|---|---|
| `online_domain_new_week` | `sender_domain_new_week`, `link_domain_new_week` | 35 | RDAP `created` < 7 days before now |
| `online_domain_new_month` | `…_domain_new_month` | 20 | < 30 days |
| `online_domain_new_halfyear` | `…_domain_new_halfyear` | 8 | < 180 days (only the strongest age bucket counts) |
| `online_cert_new` | `sender_cert_new`, `link_cert_new` | 20 | CT `first_seen` < 7 days before now |
| `online_phish_list_url` / `_host` / `_domain` | `sender_phish_list`, `link_phish_list` | 60 | on a downloaded list (Phishing.Database, on by default once the lists are on; OpenPhish, off by default; PhishTank keyless): exact URL (normalised as Station's `feeds.normalize`: trailing "/" dropped) (host + path + query), else the host unless path-shared (`PATH_SHARED_HOSTS`, shorteners), else a listed bare registrable domain the host sits under (never a shared host) |
| `online_safe_browsing` | `link_safe_browsing` | 60 | Google Safe Browsing (API v5 local-list mode: hash-prefix lists on the phone, `hashes:search` only on a prefix hit; the user's own key) lists the URL |

- A helper answer with `sources: []` means **no facts**: nothing is scored and it is never labelled
  "checked". A date in the future, or unparseable, is ignored.
- **Age-only cap:** when every weighted reason is an age code (`*_domain_new_*`, `*_cert_new`),
  a page is capped at 59 (gate `online_age_only_cap`: never "danger"), an email at 49 and never
  flagged. Station's `domain_expiring` (+5) is **not** in the formula.
- Online weights ≥ 15 count as risk (`online_domain_new_halfyear` does not).
- **List strength (v1.2, pages).** `online_phish_list_url` stays 60. `online_phish_list_host` counts **45**
  and `online_phish_list_domain` **30** unless corroborated; corroborated they count 60. Corroboration:
  a password or card field on the page, or any of the impostor codes of §4, `brand_mismatch`,
  `brand_mismatch_login`, `brand_in_domain`, `brand_in_path`, `online_domain_new_week`,
  `online_domain_new_month`, `online_cert_new`, `online_safe_browsing`, `online_dnsbl_phish`,
  `online_dnsbl_spam`. An uncorroborated domain hit with no other risk code is capped at 59 (gate
  `list_domain_uncorroborated_cap`), so Laya's cues next to it reach "caution" at most. Both remain
  risk codes (they corroborate Laya and disqualify the legitimacy tiers of §5d). Which hosts may match by
  host or domain at all is still decided by the shared-host suppression (`PATH_SHARED_HOSTS`, the
  `shared_hosts.json` list, shorteners, brands' own domains): there only the exact URL counts.

## 5b. DNS and email-authentication facts (v1.2, opt-in, off by default)

Asked of the device's **own system resolver** only (no DNS-over-HTTPS provider, no third party), for the
online-facts domain `d` of §2 mode (b) (none → no DNS facts), cached per domain (6 h; 15 min when four or
more questions failed), 1.5 s per resolver, 3 s per question:

| Question | Fact | Value |
|---|---|---|
| `d A` with the **AD** bit requested (RFC 6840) | `exists`, `dnssec` | NXDOMAIN → the name does not exist (then mx = spf = false, dmarc = absent); AD set on NOERROR → `dnssec` true |
| `d MX` | `mx` | at least one MX that is not the null MX `0 .` (RFC 7505) |
| `d TXT` | `spf` | exactly one record starting `v=spf1` (case-insensitive); two or more is an SPF error → false |
| `_dmarc.d TXT` | `dmarc` | the one `v=DMARC1` record's `p=` → `reject` / `quarantine` / `none`; no record → `absent`; several records or a missing / unknown `p=` → `invalid` (RFC 7489 §6.6.3) |
| `default._bimi.d TXT` | `bimi` | a record starting `v=BIMI1` |

A failed question (timeout, SERVFAIL, REFUSED, a malformed answer) makes that fact **unknown**, never
"no". **mail-ready** = `mx` ∧ `spf` ∧ `dmarc ∈ {quarantine, reject}`. These are **weak** legitimacy
facts: registrars publish MX + SPF + `p=quarantine` for new domains by default (GoDaddy did for
`mazibeach.com`), and a scammer can publish them too. They never add or remove points by themselves and
never clear a list hit, a blocklist listing or a look-alike (§5d).

## 5c. Domain blocklists (DNSBL, v1.2, opt-in, off by default, one switch per list)

`<d>.<zone>` A questions through the same resolver, for the online-facts domain `d`:

| List | Zone | Test point | Listed: phish class | Listed: spam class | Not a listing |
|---|---|---|---|---|---|
| Spamhaus DBL | `dbl.spamhaus.org` | `dbltest.com` → `127.0.1.2` | `127.0.1.4` phish, `.5` malware, `.6` botnet C&C, `.104/.105/.106` abused-legit variants | `127.0.1.2` spam, `.102`, `.103` | `127.0.1.255` (IP queries prohibited), `127.255.255.252/254/255` (typo, **public resolver**, excessive queries), any other `127.0.1.x` |
| SURBL | `multi.surbl.org` | `test.surbl.org` → any listing (seen: `127.0.0.254`) | bits 8 PH, 16 MW | bits 64 ABUSE, 128 CR | `127.0.0.1` (access blocked), bit 1, last octet 0; bits 4 DM / 32 CT alone are informational → clean |
| URIBL | `multi.uribl.com` | `test.uribl.com` → exactly `127.0.0.14` | — | bits 2 black, 8 red | `127.0.0.1` (query refused), `127.0.0.255`, bits above 8; bit 4 grey alone → clean |

- **NXDOMAIN → clean.** SERVFAIL, REFUSED, timeout, NOERROR without an A record, an answer outside
  `127.0.0.0/8` (a hijacking resolver), or any code not in the table → **unavailable**. Unavailable is
  **never** listed.
- Each zone's **test point is asked first** (cached 1 h). When it does not answer as documented, the zone
  is unavailable for every domain and no domain is asked (URIBL refused `1.1.1.1` / `8.8.8.8` with
  `127.0.0.1` on 2026-09-24; Spamhaus answered `127.255.255.254`).
- Page codes: `online_dnsbl_phish` **60** (any phish-class listing), else `online_dnsbl_spam` **35**;
  one code per page, the strongest; source "Online", with the list as `online_source`. Both are risk
  codes. Never applied to a known-good domain (like every online fact).
- Licences (checked 2026-09-24): Spamhaus DNSBL public mirrors — free for non-commercial use by small and
  medium organisations, from your own resolver or an ECS resolver
  (https://www.spamhaus.org/blocklists/dnsbl-fair-use-policy/); SURBL — free under 1,000 users /
  250,000 messages a day, never embedded in a paid product (https://www.surbl.org/usage-policy); URIBL —
  low-volume public lookups, heavy users refused (https://uribl.com/about.shtml,
  https://uribl.com/refused.shtml). A product that is sold needs each operator's paid feed; the switch
  says so, like OpenPhish.

## 5d. Legitimacy tiers and the payment context (v1.2)

Inputs: the page codes so far (§4, §5, §5c, `impostor_login`), the scheme, the online-facts domain `d`
and its registration age `age` (days from RDAP `created` to `now`; unknown when the source is off, the
date is unreadable or in the future), the DNS facts (§5b), and `processor`:

- **`PAYMENT_PROCESSORS`** (registrable domain, mode a → name): `stripe.com`, `stripe.network` Stripe;
  `paypal.com`, `paypalobjects.com` PayPal; `braintreegateway.com`, `braintree-api.com`,
  `braintreepayments.com` Braintree; `adyen.com`, `adyenpayments.com` Adyen; `checkout.com`
  Checkout.com; `paymob.com` Paymob; `atfawry.com`, `fawry.com` Fawry; `squareup.com`,
  `squarecdn.com` Square; `shopify.com`, `shopifyinc.com` Shopify; `kashier.io` Kashier;
  `geidea.net` Geidea; `paytabs.com` PayTabs. (WooCommerce is covered by its gateway's frames.)
- **`processor`** = the first processor among the page's **embeds** (the host names of its `iframe[src]`
  and `script[src]` on other hosts, at most 40, as the extension sends them), else among its card
  forms' action hosts; only on https and only when no disqualifying code is present.
- **Disqualifying codes (`DISQUALIFY`)**: the impostor codes of §4 (`homograph_brand`, `lookalike_brand`,
  `brand_domain_in_subdomain`, `brand_in_subdomain`, `brand_in_domain_bait`, `brand_other_tld`,
  `mixed_script`, `userinfo_in_url`) and `brand_in_domain`, `brand_in_path`, `brand_mismatch`,
  `brand_mismatch_login`, `impostor_login`, `shared_hosting`, `shared_hosting_login`, `data_url`,
  `ip_host`, `disguised_host`, `unicode_drift_host`, `http_password`, `http_card`, `password_posts_elsewhere`, `password_posts_http`,
  `online_cert_new`, `online_domain_new_week`, `online_domain_new_month`, every list / Safe Browsing
  code and both blocklist codes.
- **disqualified** = a DISQUALIFY code, or not https, or no online-facts domain.
- **tier** = `established` when not disqualified and `age ≥ 365`; else `weak` when not disqualified,
  `age` unknown or `≥ 180`, and (mail-ready or `dnssec`); else `none`.
- **payment expected** = no DISQUALIFY code ∧ https ∧ (tier `established` ∨ `processor`).
- **sign-in expected** = tier `established`.

## 6. Scoring

### 6.1 Page / link profile

```
det   = Σ weights of the signals (+ impostor_login) + Σ online weights (not on a known domain)
        + the blocklist code (v1.2, §5c)
laya  = min(40, counted Laya points)  (v1.2: see "Laya cues" below; v1.1 capped at 25 when no
                                       deterministic signal and < 2 strong scam cues)
score = min(100, det + laya)
if score ≥ 60 and no risk code (page ≥15 or online ≥15) and no strong credential ask: score = 59
if score ≥ 60 and every weighted code is an online age code:                          score = 59
level = danger ≥ 60 · caution ≥ 30 · safe < 30        (safe is displayed "No warning signs found")
```
A user-trusted site is safe with the reason `user_trusted`. A known-good domain with no reasons gets
the info reason `known_good` (weight 0).

**Laya cues (v1.2).** Laya's page answers (`laya_*` and `pressure_login`; strong = confident) are
content-only cues. In order:
1. *Payment context:* when **payment expected**, `laya_asks_payment` is not counted (why
   `payment_expected_age` {years} on an established domain, else `payment_expected_processor`
   {provider}); when **sign-in expected**, `laya_asks_sign_in` is not counted (`sign_in_expected_age`).
2. *Corroboration:* the remaining cues count only when backed by something else — any **risk code**
   (page ≥ 15, online ≥ 15, blocklist) always; otherwise by tier: `established` → never; `weak` → two
   or more strong scam cues (`laya_urgency`, `laya_prize`, `laya_asks_payment`, `laya_asks_install`,
   `laya_asks_claim`, `pressure_login`); `none` → any weighted signal or two or more strong scam cues.
   Uncorroborated cues are not counted (why `uncorroborated`, or `uncorroborated_established` {years}),
   gate `laya_uncorroborated`. v1.1 counted them up to 25, which never reached "caution" either: no
   level changes.
3. The strong-credential-ask exception to the no-risk cap uses counted cues only.

**Outputs (v1.2).** `reasons` = counted reasons (+ info reasons of weight 0); `notCounted` =
`[{code, why, why_params}]`; `facts` = `[{code, tone: good | neutral, params}]`, weight 0:

| Fact | When | Tone |
|---|---|---|
| `fact_domain_age_years` {years, date} | `age ≥ 365` | good |
| `fact_domain_age_months` {months, date} | `180 ≤ age < 365` (younger: the age *warning* covers it) | neutral |
| `fact_cert_issuer` {issuer, year} / `fact_cert_first_seen` {year} | a first certificate is known (issuer = the helper's first `issuers` entry) | good when ≥ 90 days old, else neutral |
| `fact_payment_processor` {provider} | `processor` | good |
| `fact_mail_setup` | `mx` ∧ `spf` | good |
| `fact_dmarc_enforced` {policy} / `fact_dmarc_monitor` | `dmarc` reject/quarantine / none | good / neutral |
| `fact_dnssec`, `fact_bimi` | true | good |
| `fact_dnsbl_clean` {lists} / `fact_dnsbl_unavailable` {lists} | lists answering clean / unavailable | good / neutral |

Every fact is **neutral** (never reassurance) when a DISQUALIFY code is present or the page is not on
https: "Registered 12 years ago" next to a look-alike is true but must not reassure. Display: a
"No warning signs found" verdict shows its counted reasons as *small things noticed*, not as warning
signs; reassuring facts and other facts get their own sections; `notCounted` goes in a collapsed "Also
noticed (not counted)" with the why text.

### 6.2 Email profile

Sender domain → the host signals of §4 renamed `sender_*` (`homograph_brand` 60, `mixed_script` 35,
`lookalike_brand` 45, `brand_domain_in_subdomain` 45, `brand_in_subdomain` 30, `brand_in_domain_bait`
40, `brand_other_tld` 20, `brand_in_domain` 10, `suspicious_tld` 8, `ip` 25, `unicode_drift` 30,
`disguised_domain` 45, `deviation_domain` 10, `deviation_known` 45 (also when the transitional reading
is a trusted sender), read on the domain as written and, for look-alikes, on its literal Punycode
(nothing folded); none for free-mail). A sender domain
written with any non-ASCII character is never known, trusted or free-mail and never owns the brand
its display name claims (`"PayPal" <service@ｐａｙｐａｌ.com>`: danger 85); a sender or reply address
written with an ideographic, full-width or halfwidth full stop (`service@paypal。com`,
`service@paypal.com。`) is `sender_disguised_domain` / `reply_to_impostor`, never trusted (fix loop 3);
display name (`display_brand_freemail` 50, `display_brand_mismatch` 40, `display_address_mismatch`
40); reply-to (`reply_to_impostor` 40, `reply_to_freemail` 25, `reply_to_mismatch` 15); the
receiving server (`spoofed_known_sender` 60, `auth_dmarc_fail` 45, `auth_spf_dkim_fail` 25); links
(`link_homograph_brand` 60, `link_lookalike_brand` 45, `link_brand_domain_in_subdomain` 45,
`link_brand_in_subdomain` 30, `link_brand_in_domain_bait` 35, `link_mixed_script` 35, `link_unicode_drift` 30,
`link_disguised` 45 (both also on links to known domains, trackers and the sender's own),
`link_unreadable` 30 (a link no browser opens, `unreadable_url`; its host as written is still judged, as the site check does; `link_text_mismatch` still applies when its text names a known domain, as it does for a relative href whose text names one),
`link_text_mismatch` 40, `link_brand_text` 30, `link_data` 40, `link_userinfo` 30, `link_ip` 25,
`link_suspicious_tld` 8, `link_shortener` 5); contacts (rule 6, below); online (§5); self-vouching
(v1.3, below) `self_vouching` 15; the decision model's text reading (calibrated `is_phishing` ≥ 0.8 / ≥ 0.5)
20 / 10 only next to deterministic evidence. Conditions, tables (free-mail, trackers,
service words) and order are Station's `mail/phishing.py`, unchanged except: no `sender_punycode`,
the contact and online codes.

**Contacts (rule 6).** When the display name equals (case-insensitive) a known contact's name — the
address book, or a name seen at least twice from one address in the user's other mail — and the
address is not one of theirs: if the domain is one of theirs and not webmail, nothing (a second
address of the same organisation); else if the domain is an **international** name whose skeleton
equals one of their domains' skeletons, `contact_homograph_domain` 60; else if it is within
Levenshtein 1–2 of one of their domains, `contact_lookalike_domain` 45; else
`contact_name_other_address` 30.

**Self-vouching (v1.3).** After the known / trusted early return and unless the From address is one of a
contact's `addresses` (a contact's *name* on another address still counts as unknown): the sender's own
words — the subject plus the body cut at the first quoted-reply marker (`On … wrote:`, `Le … a écrit :`,
`El … escribió:`, `-----Original Message-----` / `Forwarded message`, `From: … Sent:`, `Sent from my …`, a
`-- ` signature line), lines starting with `>` dropped, everything from the first bulk-mail footer phrase
on dropped, URLs replaced by a space — split into sentences on `.`, `!`, `?`, `؟` followed by white space
and on new lines. The first sentence matching (case-insensitive)
`\b(verified|approved|authori[sz]ed|whitelisted|trusted|genuine|legitimate|confirmed safe|safe to open|signed off|not phishing|security check passed)\b|✓|✔|تم التحقق|موثق|موثّق|موثوق|معتمد|اعتماد|وافق|متأكد منها|آمن|اطمن|متراجع`
gives `self_vouching` 15, source `text`, param `phrase` = that sentence cut to 80 characters (79 + `…`),
shown in its own direction. Weight 15 < 25: not a risk code.

```
score = min(100, Σ weights + laya)
flag (danger) = score ≥ 50 and at least one risk code (weight ≥ 25, Laya excluded)
level = danger if flagged · caution if score ≥ 25 · safe otherwise
age-only cap: score ≤ 49, never flagged
```
A sender on a known-good or trusted domain is safe (`known_sender` / `trusted_sender`) unless the
receiving server says DMARC failed (`spoofed_known_sender` 60).

## 7. What changed (formula v1.1 vs the two old rule sets)

| # | Engine `SiteFraud` (old) | Station (old) | Formula v1.1 |
|---|---|---|---|
| 1 | brand name = registrable label | brand domain list | list first; name check only for unlisted brands |
| 2 | any `xn--` host warns | `punycode` 10 on any IDN | not a signal unless brand-confusable or mixed-script |
| 3 | mixed scripts on the raw (often ASCII) host | Unicode character-name scripts | script property, per decoded label; Japanese and Korean sets allowed (v1.1) |
| 4 | any cross-domain form | password forms only | password/card strong (30), others weak (5) |
| 5 | full PSL | trimmed `psl.py`, shared-hosting list | full pinned PSL; the 12 non-PSL names add `shared_hosting` 10; online facts use ICANN-only |
| 6 | `Impersonation` apart | brands only | contact signals in the email score |

**Station must:** use the pinned Mozilla PSL for mode (a) and ICANN-only for (b); drop `punycode` /
`sender_punycode`; treat card forms like password forms and add `form_posts_elsewhere`; add
`shared_hosting` (the 12 non-PSL names — not `run.app`, already a PSL suffix), the free-text brand
fallback, the contact codes; drop `domain_expiring`; read Unicode hosts in IDNA ASCII; treat Han +
Hiragana + Katakana and Han + Hangul as one script set each in the mixed-script rule (v1.1); label
the lowest level **"No warning signs found"** in every UI and string (never "safe", "No signal" or
"Looks safe"); then pass `phishing-vectors.json` (version 1.1).

## 8. Test vectors

`docs/phishing-vectors.json`: `{formula, version, psl, now, pages: [...], emails: [...]}`. A page
vector has `url` and optionally `title`, `password`, `card`, `forms: [{action, password, card}]`,
`claimedBrand`, `online: {facts: {domain: {sources, created, first_seen, fetched_at}}, feeds: {list:
[urls]}, safeBrowsing: [urls]}`. An email vector has `from`, `body`, optional `replyTo`, `auth`,
`links: [[href, text]]`, `contacts: [{name, addresses}]`, `online`. `expect` gives `level`, `score`,
`signals` (the weighted codes, any order) and `hostControl` / `onlineDomain` (pages) or `flag`
(emails). Ages are measured against `now`. A change to the formula changes the version and the file.

**v1.2 vectors** (`docs/phishing-vectors-v1.2.json`): `{formula, version: "1.2", extends: {version: "1.1",
file, vectors: 57}, psl, now, testPoints, pages}`. A v1.2 page vector may add `embeds: [hosts]`,
`laya: {cue code: "strong" | "weak"}` (a port without a page model injects the cues as given),
`online.facts[d].issuers`, `online.dns: true` (the DNS source is on), `online.dnsbl: [list ids]` and
`online.answers: {"<name> <TYPE>": {rcode: NOERROR | NXDOMAIN | SERVFAIL | REFUSED | TIMEOUT, answers,
ad}}` — the raw resolver answers, so a port runs its own DNS-fact and blocklist parsing; a question not
listed is NXDOMAIN, except the zones' test points, which default to the file's `testPoints`. `expect`
adds `notCounted`, `facts`, `reassuring` (the good-tone facts), `tier`, `paymentExpected`.

**v1.3 vectors** (`docs/phishing-vectors-v1.3.json`, byte-identical to Station's
`tests/fixtures/phishing-vectors-v1.3.json`): `{formula, version: "1.3", extends: {version: "1.2", file},
psl, now, emails}` in the v1.1 email vector format; 8 self-vouching vectors. All 12 v1.1 email vectors and
all 51 v1.2 page vectors are unchanged (`PhishingFormulaV13Test`).

## 9. Before / after (2026-09-24, pinned PSL `2026-09-21_18-50-07_UTC`)

On the shipped sample (24 emails, the fraud watcher, web links) and every existing fixture
(`SiteSignalsTest` URLs, Station's 21 `email_cases`, the engine's `SiteFraudTest` URLs), only these
changed; every other verdict is identical.

| Input | Before | After |
|---|---|---|
| sample `phishing-paypal.eml` | danger 100 | danger 100 (unchanged) |
| Microsoft title on `login.live.com` + password | Station safe · engine `brand-origin-mismatch` | safe 0 |
| Google claim on `youtube.com` | Station safe · engine mismatch | safe 0 |
| `مثال.مصر`, `例子.中国`, `bücher.de` | safe 10 `punycode` · engine `punycode-host` | safe 0 |
| homograph `pаypal.com`, `аррlе.com` | danger 60 · engine `punycode-host` | danger 60 |
| card form posting to another domain | safe 0 · engine silent | caution 30 |
| search form posting to another domain | safe 0 · engine `cross-origin-form-post` | safe 5 |
| `paypal.wordpress.com` | caution 30 | caution 40 (+ `shared_hosting`) |
| `my-shop.wordpress.com` | safe 0 | safe 10 |
| login on `*.wordpress.com` | safe 15 | safe 25 |
| sample `mum-new-number.eml` ("Mum" from a new address) | safe 0 | caution 30 `contact_name_other_address` (not flagged) |
| fraud watcher: sample PayPal sender / link | engine `brand-origin-mismatch` ×2 | danger 60 / caution 45 |
