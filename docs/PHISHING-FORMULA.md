# The phishing / site formula

*Formula version 1 — decided by the owner 2026-09-24 (decisions A and C). One formula for Loupe
(iPhone, desktop, engine callers) and Loupe Station. It replaces the engine's `SiteFraud` rules and
Station's `browser/signals.py` + `scoring.py` + `mail/phishing.py` where they differed.*

- **Reference implementation:** `loupe-kit` — `dev.loupe.kit.site` (`SiteSignals`, `SiteScoring`,
  `OnlineSignals`, `Hosts`, `Brands`) for pages and links, `dev.loupe.kit.mail.Phishing` for email.
  The engine keeps a thin API (`OriginFacts`, `SiteFraud`, `Impersonation`: facts only, no verdict).
- **Test vectors:** [`phishing-vectors.json`](phishing-vectors.json) — 42 pages/links and 12 emails,
  each with the expected level, score, weighted signal codes and (pages) both PSL answers. Loupe runs
  them on the JVM and the iOS simulator (`PhishingFormulaTest`); Station must run the same file.
- A verdict is **never shown as "safe"**: the lowest level is displayed as "No signal" (§4 of
  PRODUCT.md, "never blesses").

---

## 1. Input normalisation

1. A URL is split like Python's `urlsplit` (scheme, userinfo, host, path, query; fragment dropped).
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
4. **Cross-domain form posts.** A form whose action's registrable domain (mode a) differs from the
   page's: **strong** when the form has a password or card field (`password_posts_elsewhere` 30,
   risk), **weak** for any other form (`form_posts_elsewhere` 5). A password/card form on https that
   posts over http: `password_posts_http` 25. Only the first offending form counts.
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
| `userinfo_in_url` | 30 | `user@` before the host |
| `url_shortener` | 10 | host or registrable domain on the shorteners list |
| `long_url` | 5 | whole URL > 200 characters |
| `encoded_url` | 5 | > 12 `%XX` in path + query |
| `brand_in_path` | 15 | not known; a brand's domain appears (dash-bounded) in the decoded path/query and the brand does not own the host |
| `ip_host` | 25 | host is an IP (dotted, decimal, hex, short forms, IPv6); private IPs never flagged. Stops the host checks |
| `homograph_brand` | 60 | rule 2 (i), on any `xn--` label (brand checks on the registrable label are then skipped) |
| `mixed_script` | 35 | rule 2 (ii) / rule 3 |
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
| `impostor_login` | 20 | added when a password/card field is on a page with an impostor code (`homograph_brand`, `lookalike_brand`, `brand_domain_in_subdomain`, `brand_in_subdomain`, `brand_in_domain_bait`, `brand_other_tld`, `mixed_script`, `userinfo_in_url`) |

The host checks return nothing for a **known** domain (and the page claim is ignored there).
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
| `online_phish_list_url` / `_host` / `_domain` | `sender_phish_list`, `link_phish_list` | 60 | on a downloaded list (OpenPhish; PhishTank keyless): exact URL (host + path + query), else the host unless path-shared (`PATH_SHARED_HOSTS`, shorteners), else a listed bare registrable domain the host sits under (never a shared host) |
| `online_safe_browsing` | `link_safe_browsing` | 60 | Google Safe Browsing (Update API v4, the user's own key) lists the URL |

- A helper answer with `sources: []` means **no facts**: nothing is scored and it is never labelled
  "checked". A date in the future, or unparseable, is ignored.
- **Age-only cap:** when every weighted reason is an age code (`*_domain_new_*`, `*_cert_new`),
  a page is capped at 59 (gate `online_age_only_cap`: never "danger"), an email at 49 and never
  flagged. Station's `domain_expiring` (+5) is **not** in the formula.
- Online weights ≥ 15 count as risk (`online_domain_new_halfyear` does not).

## 6. Scoring

### 6.1 Page / link profile

```
det   = Σ weights of the signals (+ impostor_login) + Σ online weights (not on a known domain)
laya  = min(40, Laya points)          (25 when no deterministic signal and < 2 strong scam cues)
score = min(100, det + laya)
if score ≥ 60 and no risk code (page ≥15 or online ≥15) and no strong credential ask: score = 59
if score ≥ 60 and every weighted code is an online age code:                          score = 59
level = danger ≥ 60 · caution ≥ 30 · safe < 30        (safe is displayed "No signal")
```
A user-trusted site is safe with the reason `user_trusted`. A known-good domain with no reasons gets
the info reason `known_good` (weight 0).

### 6.2 Email profile

Sender domain → the host signals of §4 renamed `sender_*` (`homograph_brand` 60, `mixed_script` 35,
`lookalike_brand` 45, `brand_domain_in_subdomain` 45, `brand_in_subdomain` 30, `brand_in_domain_bait`
40, `brand_other_tld` 20, `brand_in_domain` 10, `suspicious_tld` 8, `ip` 25; none for free-mail);
display name (`display_brand_freemail` 50, `display_brand_mismatch` 40, `display_address_mismatch`
40); reply-to (`reply_to_impostor` 40, `reply_to_freemail` 25, `reply_to_mismatch` 15); the
receiving server (`spoofed_known_sender` 60, `auth_dmarc_fail` 45, `auth_spf_dkim_fail` 25); links
(`link_homograph_brand` 60, `link_lookalike_brand` 45, `link_brand_domain_in_subdomain` 45,
`link_brand_in_subdomain` 30, `link_brand_in_domain_bait` 35, `link_mixed_script` 35,
`link_text_mismatch` 40, `link_brand_text` 30, `link_data` 40, `link_userinfo` 30, `link_ip` 25,
`link_suspicious_tld` 8, `link_shortener` 5); contacts (rule 6, below); online (§5); Laya's text
reading 20 / 10 only next to deterministic evidence. Conditions, tables (free-mail, trackers,
service words) and order are Station's `mail/phishing.py`, unchanged except: no `sender_punycode`,
the contact and online codes.

**Contacts (rule 6).** When the display name equals (case-insensitive) a known contact's name — the
address book, or a name seen at least twice from one address in the user's other mail — and the
address is not one of theirs: if the domain is one of theirs and not webmail, nothing (a second
address of the same organisation); else if the domain is an **international** name whose skeleton
equals one of their domains' skeletons, `contact_homograph_domain` 60; else if it is within
Levenshtein 1–2 of one of their domains, `contact_lookalike_domain` 45; else
`contact_name_other_address` 30.

```
score = min(100, Σ weights + laya)
flag (danger) = score ≥ 50 and at least one risk code (weight ≥ 25, Laya excluded)
level = danger if flagged · caution if score ≥ 25 · safe otherwise
age-only cap: score ≤ 49, never flagged
```
A sender on a known-good or trusted domain is safe (`known_sender` / `trusted_sender`) unless the
receiving server says DMARC failed (`spoofed_known_sender` 60).

## 7. What changed (formula v1 vs the two old rule sets)

| # | Engine `SiteFraud` (old) | Station (old) | Formula v1 |
|---|---|---|---|
| 1 | brand name = registrable label | brand domain list | list first; name check only for unlisted brands |
| 2 | any `xn--` host warns | `punycode` 10 on any IDN | no signal unless brand-confusable or mixed-script |
| 3 | mixed scripts on the raw (often ASCII) host | Unicode character-name scripts | script property, per decoded label |
| 4 | any cross-domain form | password forms only | password/card strong (30), others weak (5) |
| 5 | full PSL | trimmed `psl.py`, shared-hosting list | full pinned PSL; the 12 non-PSL names add `shared_hosting` 10; online facts use ICANN-only |
| 6 | `Impersonation` apart | brands only | contact signals in the email score |

**Station must:** use the pinned Mozilla PSL for mode (a) and ICANN-only for (b); drop `punycode` /
`sender_punycode`; treat card forms like password forms and add `form_posts_elsewhere`; add
`shared_hosting`, the free-text brand fallback, the contact codes; drop `domain_expiring`; read
Unicode hosts in IDNA ASCII; then pass `phishing-vectors.json`.

## 8. Test vectors

`docs/phishing-vectors.json`: `{formula, version, psl, now, pages: [...], emails: [...]}`. A page
vector has `url` and optionally `title`, `password`, `card`, `forms: [{action, password, card}]`,
`claimedBrand`, `online: {facts: {domain: {sources, created, first_seen, fetched_at}}, feeds: {list:
[urls]}, safeBrowsing: [urls]}`. An email vector has `from`, `body`, optional `replyTo`, `auth`,
`links: [[href, text]]`, `contacts: [{name, addresses}]`, `online`. `expect` gives `level`, `score`,
`signals` (the weighted codes, any order) and `hostControl` / `onlineDomain` (pages) or `flag`
(emails). Ages are measured against `now`. A change to the formula changes the version and the file.

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
