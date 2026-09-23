package dev.loupe.kit.site

/*
 * Default reference lists for site protection: frequently phished brands, URL shorteners and TLDs
 * that phishing kits favour.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/browser/brands.py`, commit ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). The brand list
 * (names, domains, tokens, cctld flags), SHORTENERS and SUSPICIOUS_TLDS are copied verbatim; only
 * the language changed.
 *
 * A brand is its display name, its registrable domains, the words that stand for it in a host name,
 * and `cctld` when it runs sites on many country domains (amazon.de, google.com.eg). Its domains are
 * also the built-in known-good list: a page on one of them is the real brand. Tokens shorter than 4
 * letters are only matched as a whole hyphen/dot-separated word, never by edit distance.
 */

/** One well-known brand. */
data class Brand(
    val name: String,
    val domains: List<String>,
    val tokens: List<String>,
    val cctld: Boolean = false,
)

object Brands {
    val BRANDS: List<Brand> = listOf(
        // payments and wallets
        Brand("PayPal", listOf("paypal.com", "paypal.me", "paypalobjects.com"), listOf("paypal")),
        Brand("Stripe", listOf("stripe.com"), listOf("stripe")),
        Brand("Visa", listOf("visa.com"), listOf("visa"), cctld = true),
        Brand("Mastercard", listOf("mastercard.com"), listOf("mastercard"), cctld = true),
        Brand("American Express", listOf("americanexpress.com", "aexp.com"), listOf("americanexpress", "amex")),
        Brand("Wise", listOf("wise.com", "transferwise.com"), listOf("transferwise")),
        Brand("Western Union", listOf("westernunion.com", "wu.com"), listOf("westernunion")),
        Brand("Coinbase", listOf("coinbase.com"), listOf("coinbase")),
        Brand("Binance", listOf("binance.com"), listOf("binance")),
        Brand("MetaMask", listOf("metamask.io"), listOf("metamask")),
        // banks (international)
        Brand("Chase", listOf("chase.com", "jpmorganchase.com"), listOf("chase")),
        Brand("Bank of America", listOf("bankofamerica.com", "bofa.com"), listOf("bankofamerica")),
        Brand("Wells Fargo", listOf("wellsfargo.com"), listOf("wellsfargo")),
        Brand("Citibank", listOf("citi.com", "citibank.com"), listOf("citibank"), cctld = true),
        Brand("HSBC", listOf("hsbc.com", "hsbc.co.uk"), listOf("hsbc"), cctld = true),
        Brand("Barclays", listOf("barclays.co.uk", "barclays.com"), listOf("barclays")),
        // email, cloud and software accounts
        Brand(
            "Microsoft",
            listOf(
                "microsoft.com", "microsoftonline.com", "live.com", "outlook.com", "office.com",
                "office365.com", "sharepoint.com", "onedrive.com", "msn.com", "hotmail.com",
                "azure.com", "windows.net", "microsoft365.com", "skype.com", "bing.com",
            ),
            listOf("microsoft", "outlook", "office365", "hotmail", "onedrive", "sharepoint", "microsoftonline"),
        ),
        Brand(
            "Google",
            listOf(
                "google.com", "gmail.com", "googleusercontent.com", "youtube.com", "gstatic.com",
                "googleapis.com", "withgoogle.com", "goo.gl",
            ),
            listOf("google", "gmail", "youtube"),
            cctld = true,
        ),
        Brand("Apple", listOf("apple.com", "icloud.com", "me.com", "apple.co"), listOf("apple", "icloud", "appleid", "itunes")),
        Brand("Yahoo", listOf("yahoo.com"), listOf("yahoo"), cctld = true),
        Brand("Dropbox", listOf("dropbox.com", "dropboxusercontent.com"), listOf("dropbox")),
        Brand("DocuSign", listOf("docusign.com", "docusign.net"), listOf("docusign")),
        Brand("Adobe", listOf("adobe.com", "adobelogin.com"), listOf("adobe")),
        Brand("Zoom", listOf("zoom.us", "zoom.com"), listOf("zoom")),
        Brand("Proton", listOf("proton.me", "protonmail.com"), listOf("protonmail")),
        // shopping and streaming
        Brand(
            "Amazon",
            listOf(
                "amazon.com", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it", "amazon.es",
                "amazon.ca", "amazon.in", "amazon.co.jp", "amazon.com.au", "amazon.ae", "amazon.sa",
                "amazon.eg", "amazon.com.br", "amazon.com.mx", "amazon.nl", "amazon.se", "amazon.pl",
                "amazon.com.tr", "amazon.sg", "amazonaws.com", "media-amazon.com",
            ),
            listOf("amazon"),
            cctld = true,
        ),
        Brand("eBay", listOf("ebay.com"), listOf("ebay"), cctld = true),
        Brand("Netflix", listOf("netflix.com"), listOf("netflix")),
        Brand("Spotify", listOf("spotify.com"), listOf("spotify")),
        Brand("Steam", listOf("steampowered.com", "steamcommunity.com"), listOf("steampowered", "steamcommunity")),
        // social and messaging
        Brand("Facebook", listOf("facebook.com", "fb.com", "meta.com", "messenger.com", "fbcdn.net"), listOf("facebook", "messenger")),
        Brand("Instagram", listOf("instagram.com", "cdninstagram.com"), listOf("instagram")),
        Brand("WhatsApp", listOf("whatsapp.com", "whatsapp.net", "wa.me"), listOf("whatsapp")),
        Brand("LinkedIn", listOf("linkedin.com", "licdn.com"), listOf("linkedin")),
        Brand("X (Twitter)", listOf("x.com", "twitter.com", "twimg.com", "t.co"), listOf("twitter")),
        Brand("TikTok", listOf("tiktok.com"), listOf("tiktok")),
        Brand("Telegram", listOf("telegram.org", "t.me"), listOf("telegram")),
        // shipping
        Brand("DHL", listOf("dhl.com", "dhl.de"), listOf("dhl"), cctld = true),
        Brand("FedEx", listOf("fedex.com"), listOf("fedex")),
        Brand("UPS", listOf("ups.com"), listOf("ups")),
        Brand("USPS", listOf("usps.com"), listOf("usps")),
        Brand("Aramex", listOf("aramex.com"), listOf("aramex")),
    )

    /** Redirectors: the destination is hidden until you click. */
    val SHORTENERS: Set<String> = """
        bit.ly bitly.com tinyurl.com t.ly is.gd v.gd ow.ly buff.ly rebrand.ly cutt.ly shorturl.at rb.gy
        tiny.cc bl.ink s.id lnkd.in trib.al qrco.de linktr.ee t2m.io shorte.st adf.ly
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** TLDs with a high share of abuse in public phishing feeds. Low weight: most sites on them are fine. */
    val SUSPICIOUS_TLDS: Set<String> = """
        zip mov tk ml ga cf gq xyz top click country kim work rest fit loan men date racing download review
        stream gdn bid win party science cricket accountant faith support icu cyou buzz monster sbs cfd
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /**
     * Station's `psl.SHARED_HOSTING` (browser/psl.py, verbatim): shared hosting, free site builders,
     * tunnels and dynamic DNS. Only the *list* is kept, to mean "a login form here is worth a second
     * look"; registrable domains come from the engine's pinned Mozilla PSL, never from Station's
     * trimmed snapshot.
     */
    val SHARED_HOSTING: Set<String> = """
        github.io gitlab.io bitbucket.io codeberg.page pages.dev workers.dev r2.dev trycloudflare.com
        netlify.app vercel.app now.sh herokuapp.com onrender.com fly.dev railway.app
        web.app firebaseapp.com appspot.com run.app cloudfunctions.net
        azurewebsites.net azurestaticapps.net cloudapp.net blob.core.windows.net web.core.windows.net
        cloudfront.net s3.amazonaws.com amplifyapp.com elasticbeanstalk.com
        glitch.me repl.co replit.app replit.dev surge.sh ngrok.io ngrok.app ngrok-free.app loca.lt serveo.net
        blogspot.com wordpress.com wixsite.com weebly.com webflow.io framer.app framer.website
        square.site myshopify.com godaddysites.com 000webhostapp.com carrd.co jimdosite.com site123.me
        strikingly.com yolasite.com tilda.ws notion.site gitbook.io readthedocs.io
        duckdns.org no-ip.org ddns.net hopto.org zapto.org sytes.net freemyip.com
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    fun brandDomains(brands: List<Brand>): Set<String> = brands.flatMap { b -> b.domains.map { it.lowercase() } }.toSet()
}
