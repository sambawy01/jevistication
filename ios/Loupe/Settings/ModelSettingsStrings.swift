import Foundation
import SwiftUI

/// English and Arabic wording for Model settings and the "Laya was off" banners. The app has no
/// string catalogue yet, so this screen carries its own; the Arabic reuses Loupe Station's wording
/// (`laya_studio/static/js/i18n.js`, `eng.*`, `dash.*`, `mem.*`) wherever Station has the line, and
/// adds the iPhone's own lines (one model, memory on a phone, the mobile features) in the same voice.
///
/// Arabic is chosen from the phone's preferred languages; the views then lay out right to left.
enum MS {
    enum Lang: String { case en, ar }

    /// `-LoupeLanguage ar|en` (DEBUG) overrides the phone's languages, for tests.
    static var lang: Lang = {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-LoupeLanguage"), i + 1 < args.count, let l = Lang(rawValue: args[i + 1]) { return l }
        #endif
        return (Locale.preferredLanguages.first ?? "en").hasPrefix("ar") ? .ar : .en
    }()

    static var direction: LayoutDirection { lang == .ar ? .rightToLeft : .leftToRight }

    /// The line for [key] in the current language, with `{name}` placeholders filled from [args].
    static func t(_ key: String, _ args: [String: String] = [:]) -> String {
        let pair = table[key]
        var s = (lang == .ar ? pair?.1 : pair?.0) ?? pair?.0 ?? key
        for (k, v) in args { s = s.replacingOccurrences(of: "{\(k)}", with: v) }
        return s
    }

    static func has(_ key: String) -> Bool { table[key] != nil }

    /// Numbers stay Western digits in both languages (as Station), wrapped for RTL in Arabic units.
    static func number(_ v: Double) -> String {
        if v == v.rounded(), abs(v) < 1e12 {
            let f = NumberFormatter()
            f.numberStyle = .decimal
            f.locale = Locale(identifier: "en_US")
            return f.string(from: NSNumber(value: Int(v))) ?? "\(Int(v))"
        }
        return String(format: "%.2f", v).replacingOccurrences(of: "0+$", with: "", options: .regularExpression)
    }

    static let table: [String: (String, String)] = [
        // Screen
        "title": ("Model settings", "إعدادات النموذج"),
        "intro": ("How Laya, the decision model on this iPhone, is used: which model reads, how much text, and when an answer is accepted. Changes are saved at once and apply to the next run — no restart.",
                  "كيف يُستخدم Laya، نموذج القرار على هذا الـ iPhone: أي نموذج يقرأ، وكم من النص، ومتى يُعتمد الجواب. تُحفظ التغييرات فورًا وتسري على التشغيل التالي، دون إعادة تشغيل."),
        "noEnv": ("iPhone has no environment variables or device management for these, so nothing here is set from outside the app.",
                  "لا توجد على الـ iPhone متغيرات بيئة أو إدارة أجهزة لهذه الإعدادات، فلا يُحدَّد شيء هنا من خارج التطبيق."),
        "global": ("Everywhere", "في كل مكان"),
        "globalHint": ("These apply to every feature unless a feature below says otherwise.", "تسري هذه على كل الميزات ما لم تحدد ميزة أدناه غير ذلك."),
        "features": ("Per feature", "لكل ميزة"),
        "current": ("Now: {v}", "الآن: {v}"),
        "default": ("default: {v}", "الافتراضي: {v}"),
        "reset": ("Reset to default", "إعادة إلى الافتراضي"),
        "resetAria": ("Reset {name} to its default", "إعادة {name} إلى قيمته الافتراضية"),
        "restoreAll": ("Restore all defaults", "استعادة كل القيم الافتراضية"),
        "restoreConfirm": ("Restore every model setting to its default?", "هل تريد إعادة كل إعدادات النموذج إلى قيمها الافتراضية؟"),
        "restored": ("All model settings are back to their defaults.", "عادت كل إعدادات النموذج إلى قيمها الافتراضية."),
        "saved": ("Saved. Applies to the next run.", "حُفظ. يسري على التشغيل التالي."),
        "savedMemory": ("Saved. Laya is loading or unloading in the background.", "حُفظ. يجري تحميل Laya أو تفريغه في الخلفية."),
        "cancel": ("Cancel", "إلغاء"),
        "done": ("Done", "تم"),
        "loadedNow": ("Laya is loaded in memory now.", "Laya محمّل في الذاكرة الآن."),
        "unloadedNow": ("Laya is not in memory now; the next run loads it.", "Laya غير محمّل في الذاكرة الآن؛ يحمّله التشغيل التالي."),
        "desktopOnly": ("Loupe Station only — no effect on iPhone.", "في Loupe Station فقط — لا أثر له على iPhone."),
        "mobileOnly": ("iPhone only", "على iPhone فقط"),

        // Values
        "v.on": ("On", "مُفعّل"),
        "v.off": ("Off", "متوقف"),
        "v.featureRule": ("Each feature's own rule", "قاعدة كل ميزة"),
        "v.noCap": ("No cap", "دون حد"),
        "v.followGlobal": ("Same as everywhere", "كما في كل مكان"),
        "route.auto": ("Automatic (by language and length)", "تلقائي (حسب اللغة والطول)"),
        "route.english": ("Always the English model — not on iPhone", "النموذج الإنجليزي دائمًا — غير متوفر على iPhone"),
        "route.multilingual": ("Always the multilingual model", "النموذج متعدد اللغات دائمًا"),
        "route.follow": ("Same as everywhere ({route})", "كما في كل مكان ({route})"),
        "route.runsAs": ("Runs as: the multilingual model.", "يعمل بـ: النموذج متعدد اللغات."),
        "route.phone": ("iPhone carries one model, Laya multilingual, so Automatic and Multilingual both run it. The English model is on Loupe Station only; choosing it here still runs multilingual.",
                        "يحمل الـ iPhone نموذجًا واحدًا هو Laya متعدد اللغات، لذا يشغّله الوضعان التلقائي ومتعدد اللغات. النموذج الإنجليزي في Loupe Station فقط؛ واختياره هنا يشغّل متعدد اللغات أيضًا."),
        "mem.full": ("Full", "كامل"),
        "mem.balanced": ("Balanced", "متوازن"),
        "mem.low": ("Low memory", "ذاكرة منخفضة"),
        "text.builtin": ("Built-in", "المدمج"),
        "text.builtinN": ("Built-in: {n} characters", "المدمج: ⁦{n}⁩ حرفًا"),
        "text.global": ("Same as everywhere", "كما في كل مكان"),
        "text.custom": ("Custom", "مخصص"),
        "unit.min": ("{n} min", "⁦{n}⁩ دقيقة"),
        "unit.s": ("{n} s", "⁦{n}⁩ ث"),
        "unit.chars": ("{n} characters", "⁦{n}⁩ حرفًا"),
        "unit.perS": ("{n} per second", "⁦{n}⁩ في الثانية"),
        "unit.pages": ("{n} pages", "⁦{n}⁩ صفحات"),
        "unit.never": ("never", "أبدًا"),

        // Global keys
        "g.routing": ("Model routing", "توجيه النموذج"),
        "g.routing.desc": ("Automatic picks the English model for English and the multilingual one for other languages or long text. Forcing one is more predictable but can be less accurate.",
                           "الوضع التلقائي يختار النموذج الإنجليزي للإنجليزية ومتعدد اللغات للغات الأخرى أو النص الطويل. فرض نموذج واحد أكثر قابلية للتوقع لكنه قد يكون أقل دقة."),
        "g.memory_mode": ("Memory mode", "وضع الذاكرة"),
        "g.memory_mode.desc": ("How long Laya stays in the phone's memory: faster answers against memory and battery.",
                               "كم يبقى Laya في ذاكرة الهاتف: أجوبة أسرع مقابل الذاكرة والبطارية."),
        "mem.desc.full": ("Keeps Laya loaded once opened: the fastest answers, but it holds several hundred MB of memory and iOS is more likely to close Loupe in the background.",
                          "يُبقي Laya محمّلًا بعد فتحه: أسرع الأجوبة، لكنه يحجز مئات الميغابايتات من الذاكرة ويزيد احتمال أن يغلق iOS تطبيق Loupe في الخلفية."),
        "mem.desc.balanced": ("Frees Laya after it sits idle for the minutes below; the next use waits a few seconds while it loads and its files are checked again.",
                              "يحرّر Laya بعد أن يبقى خاملًا للدقائق أدناه؛ ينتظر الاستخدام التالي بضع ثوانٍ حتى يُحمَّل وتُفحص ملفاته مجددًا."),
        "mem.desc.low": ("Loads Laya for each run and frees it right after: the least memory and battery held between runs; every run starts a few seconds slower.",
                         "يحمّل Laya لكل تشغيل ويحرّره فور انتهائه: أقل ذاكرة وبطارية محجوزة بين التشغيلات؛ ويبدأ كل تشغيل أبطأ ببضع ثوانٍ."),
        "applies.memory_mode": ("Applies now: Laya loads or unloads in the background.", "يسري الآن: يُحمَّل Laya أو يُفرَّغ في الخلفية."),
        "applies.idle_unload_min": ("Applies at the next memory check, within 30 seconds.", "يسري عند فحص الذاكرة التالي، خلال 30 ثانية."),
        "g.idle_unload_min": ("Free an idle model after", "تحرير النموذج الخامل بعد"),
        "g.idle_unload_min.desc": ("Balanced only. Shorter frees memory sooner; the next use then waits a few seconds for Laya to load. 0 = never.",
                                   "للمتوازن فقط. المدة الأقصر تحرر الذاكرة أسرع، لكن الاستخدام التالي ينتظر بضع ثوانٍ حتى يُحمَّل Laya. 0 = أبدًا."),
        "g.accept_confidence": ("Confidence to accept a label", "الثقة اللازمة لقبول تصنيف"),
        "g.accept_confidence.desc": ("Below this, an answer is shown as unsure and waits in Needs you instead of acting. Higher is safer but leaves more for you to check. Off = each feature's own rule (judgments: their starting threshold; flights 0.80; the expiry watcher 0.5). A threshold set on a judgment's Measure screen still wins.",
                                     "دونها يظهر الجواب كغير مؤكد وينتظر في «يحتاجك» بدل أن يُنفَّذ. القيمة الأعلى أكثر أمانًا لكنها تترك لك مزيدًا من التدقيق. متوقف = قاعدة كل ميزة (الأحكام: عتبتها الابتدائية؛ الرحلات 0.80؛ مراقب انتهاء الصلاحية 0.5). العتبة المحددة في شاشة القياس لحكمٍ تغلب لسؤالها."),
        "g.use_calibration": ("Use calibration", "استخدام المعايرة"),
        "g.use_calibration.desc": ("Once your corrections have fitted a calibration for a question, Laya's confidence is adjusted with it. Off: raw confidence. No calibration is fitted on iPhone yet, so on and off both use raw confidence today.",
                                   "حين تكفي تصحيحاتك لمعايرة سؤال، تُعدَّل ثقة Laya بها. عند الإيقاف: الثقة الخام. لا تُجرى معايرة على الـ iPhone بعد، لذا يستخدم الوضعان الثقة الخام اليوم."),
        "g.rules_first": ("Rules first", "القواعد أولًا"),
        "g.rules_first.desc": ("A rule that is precise where it fires (an exact duplicate of a file already judged) answers before Laya. Off: Laya answers those too — slower, more battery.",
                               "القاعدة الدقيقة حيث تنطبق (نسخة مطابقة لملف حُكم عليه) تجيب قبل Laya. عند الإيقاف: يجيب Laya عنها أيضًا — أبطأ، وبطارية أكثر."),
        "g.baseline_switch": ("Switch to keyword rules when they win", "التحول إلى قواعد الكلمات حين تتفوق"),
        "g.baseline_switch.desc": ("Where your corrections show a judgment's keyword rule beating Laya, the rule answers it. Off: Laya always answers. A judgment's own Auto / Always baseline / Always Laya choice still wins.",
                                   "حيث تُظهر تصحيحاتك أن قاعدة الكلمات لحكمٍ تتفوق على Laya، تجيب القاعدة عنه. عند الإيقاف: يجيب Laya دائمًا. اختيار الحكم نفسه (تلقائي / القاعدة دائمًا / Laya دائمًا) يبقى الغالب."),
        "g.bias_correction": ("Bias correction", "تصحيح الانحياز"),
        "g.bias_correction.desc": ("Loupe Station can re-weight Laya's answers against its lean towards some options. Measured there, both methods answered worse, so the default is Off. iPhone implements Off only; the other choices are kept for Loupe Station and run as Off here.",
                                   "يمكن لـ Loupe Station إعادة وزن أجوبة Laya لتعويض ميله إلى بعض الخيارات. وقد قيس ذلك هناك فكانت الطريقتان أسوأ، لذا الافتراضي متوقف. الـ iPhone ينفّذ «متوقف» فقط؛ والخياران الآخران محفوظان لـ Loupe Station ويعملان هنا كـ «متوقف»."),
        "bias.off": ("Off", "متوقف"),
        "bias.contextual": ("Contextual — Loupe Station only", "سياقي — في Loupe Station فقط"),
        "bias.domain": ("Domain — Loupe Station only", "حسب المجال — في Loupe Station فقط"),
        "bias.phone": ("iPhone runs Off whatever is chosen here.", "يعمل الـ iPhone بوضع «متوقف» أيًّا كان الاختيار هنا."),
        "g.text_chars_english": ("Text read per item: English", "النص المقروء لكل عنصر: الإنجليزي"),
        "g.text_chars_english.desc": ("Characters sent to the English model. iPhone has no English model, so this is kept for Loupe Station and unused here.",
                                      "عدد الأحرف المرسلة إلى النموذج الإنجليزي. لا يوجد نموذج إنجليزي على الـ iPhone، لذا يُحفظ هذا لـ Loupe Station ولا يُستخدم هنا."),
        "g.text_chars_multilingual": ("Text read per item: multilingual", "النص المقروء لكل عنصر: متعدد اللغات"),
        "g.text_chars_multilingual.desc": ("Characters sent to Laya for a feature set to \"Same as everywhere\". More context against speed and battery.",
                                           "عدد الأحرف المرسلة إلى Laya للميزة المضبوطة على «كما في كل مكان». سياق أكثر مقابل السرعة والبطارية."),

        // Cost of asking (the live run views): Station's eng.g.cost_* wording, "this device" for "this Mac".
        "g.cost_input_per_mtok": ("Cost of asking: input price", "كلفة السؤال: سعر الإدخال"),
        "g.cost_input_per_mtok.desc": ("Dollars per million input tokens of the cloud model the live views compare Laya with. Default: Claude Sonnet 5 list price. Nothing is ever sent to it.",
                                       "دولارات لكل مليون رمز إدخال للنموذج السحابي الذي تقارن به العروض المباشرة Laya. الافتراضي: سعر Claude Sonnet 5 المعلن. لا يُرسل إليه شيء أبدًا."),
        "g.cost_output_per_mtok": ("Cost of asking: output price", "كلفة السؤال: سعر الإخراج"),
        "g.cost_output_per_mtok.desc": ("Dollars per million output tokens of that cloud model.", "دولارات لكل مليون رمز إخراج لذلك النموذج السحابي."),
        "g.cost_tokens_in": ("Cost of asking: tokens in per decision", "كلفة السؤال: رموز الإدخال لكل قرار"),
        "g.cost_tokens_in.desc": ("Input tokens one decision would take in a cloud request (an excerpt with its questions).",
                                  "رموز الإدخال التي يأخذها قرار واحد في طلب سحابي (مقتطف مع أسئلته)."),
        "g.cost_tokens_out": ("Cost of asking: tokens out per decision", "كلفة السؤال: رموز الإخراج لكل قرار"),
        "g.cost_tokens_out.desc": ("Output tokens one decision would take (a short JSON answer).", "رموز الإخراج التي يأخذها قرار واحد (إجابة JSON قصيرة)."),
        "unit.usdPerM": ("${n} per 1M tokens", "{n} دولار لكل مليون رمز"),
        "unit.tokens": ("{n} tokens", "{n} رمزًا"),

        // Features
        "feat.judgments": ("Judgments and sorting", "الأحكام والفرز"),
        "feat.judgments.hint": ("Your judgments: runs from a judgment's results and the sort in the background.", "أحكامك: التشغيل من نتائج الحكم والفرز في الخلفية."),
        "feat.scan": ("Privacy check and sources scan", "فحص الخصوصية ومسح المصادر"),
        "feat.scan.hint": ("Personal data, secrets and duplicates in your sources. Loupe Station's Folder Scan.", "البيانات الشخصية والأسرار والنسخ المكررة في مصادرك. فحص المجلدات في Loupe Station."),
        "feat.email": ("Mail triage", "فرز البريد"),
        "feat.email.hint": ("Phishing, spam, needs reply and urgent, across your mail.", "التصيّد والرسائل المزعجة وما يحتاج ردًا والعاجل، في بريدك."),
        "feat.browser": ("Site checks", "فحص المواقع"),
        "feat.browser.hint": ("Web links in mail and shared items, and the online checks. Loupe Station's browser protection.", "روابط الويب في البريد والعناصر المشتركة، والفحوص عبر الإنترنت. حماية المتصفح في Loupe Station."),
        "feat.watchers": ("Watchers", "المراقِبون"),
        "feat.watchers.hint": ("Subscriptions, expiry, changed terms and impersonation checks.", "الاشتراكات وانتهاء الصلاحية وتغيّر الشروط وفحوص انتحال الهوية."),
        "feat.flights": ("Flights", "الرحلات"),
        "feat.flights.hint": ("Ranking flight offers against your priorities.", "ترتيب عروض الرحلات حسب أولوياتك."),
        "feat.game": ("Riverflight (game)", "Riverflight (اللعبة)"),
        "feat.game.hint": ("Laya flying the plane in Watch mode.", "Laya يقود الطائرة في وضع المشاهدة."),
        "feat.playground": ("Playground and API", "ساحة التجربة والواجهة البرمجية"),
        "feat.playground.hint": ("The Playground and /api/predict are on Loupe Station only.", "ساحة التجربة و/api/predict في Loupe Station فقط."),

        "fk.use_laya": ("Use Laya", "استخدام Laya"),
        "fk.routing": ("Model routing", "توجيه النموذج"),
        "fk.text_chars": ("Text read per item", "النص المقروء لكل عنصر"),
        "fk.read_content": ("Read file contents", "قراءة محتوى الملفات"),
        "fk.content_budget_s": ("Time to read one file", "مدة قراءة الملف الواحد"),
        "fk.ocr": ("Read text in images and scans", "قراءة النص في الصور والمستندات الممسوحة"),
        "fk.ocr_max_pages": ("Scanned PDF pages to read", "صفحات PDF الممسوحة المقروءة"),
        "fk.time_limit_s": ("Laya's time limit per page", "مهلة Laya لكل صفحة"),
        "fk.queue_size": ("Pages that may wait", "الصفحات التي يمكنها الانتظار"),
        "fk.max_decisions_per_s": ("Laya decisions per second, at most", "قرارات Laya في الثانية، بحدٍّ أقصى"),

        "f.judgments.use_laya": ("On: Laya answers each judgment. Off: rules only — exact duplicates and each judgment's keyword rule answer, faster and with less battery; items no rule covers wait for a run with Laya.",
                                 "مُفعّل: يجيب Laya عن كل حكم. متوقف: القواعد فقط — تجيب النسخ المطابقة وقاعدة الكلمات لكل حكم، أسرع وبطارية أقل؛ وتنتظر العناصر التي لا تغطيها قاعدة تشغيلًا مع Laya."),
        "f.scan.use_laya": ("On iPhone this check runs on Loupe Station's rules; Laya does not label files here yet. Off marks each run as rules only.",
                            "على الـ iPhone يعمل هذا الفحص بقواعد Loupe Station؛ لا يصنّف Laya الملفات هنا بعد. الإيقاف يَسِم كل تشغيل بأنه من القواعد فقط."),
        "f.scan.read_content": ("On: file text is read for secrets and personal data. Off: names, folders and exact duplicates only; much faster and finds less.",
                                "مُفعّل: يُقرأ نص الملفات بحثًا عن الأسرار والبيانات الشخصية. متوقف: الأسماء والمجلدات والنسخ المطابقة فقط؛ أسرع بكثير ويجد أقل."),
        "f.scan.content_budget_s": ("Longest time to read one file. iPhone reads each file once, when Sources scans it, within its own limits.",
                                    "أطول وقت لقراءة ملف واحد. يقرأ الـ iPhone كل ملف مرة واحدة عند مسح المصادر، ضمن حدوده الخاصة."),
        "f.scan.ocr": ("On: text in photos, screenshots and scanned PDFs is read on this iPhone with Apple Vision; nothing leaves the phone. Off: only names and metadata — faster, less battery, finds less.",
                       "مُفعّل: يُقرأ النص في الصور ولقطات الشاشة وملفات PDF الممسوحة على هذا الـ iPhone عبر Apple Vision؛ لا يغادر شيء الهاتف. متوقف: الأسماء والبيانات الوصفية فقط — أسرع وبطارية أقل ويجد أقل."),
        "f.scan.ocr_max_pages": ("How many pages of a scanned PDF (one with no text layer) are read. More finds more, and costs time and battery.",
                                 "عدد صفحات ملف PDF الممسوح (بلا طبقة نص) التي تُقرأ. الأكثر يجد أكثر، ويكلّف وقتًا وبطارية."),
        "f.email.use_laya": ("On iPhone mail triage runs on the phishing evidence and Station's rules; Laya does not read mail here yet. Off marks each run as rules only.",
                             "على الـ iPhone يعمل فرز البريد بأدلة التصيّد وقواعد Loupe Station؛ لا يقرأ Laya البريد هنا بعد. الإيقاف يَسِم كل تشغيل بأنه من القواعد فقط."),
        "f.browser.use_laya": ("On iPhone site checks use the address and the online checks only; Laya does not read pages here. Off marks each run as rules only.",
                               "على الـ iPhone تعتمد فحوص المواقع على العنوان والفحوص عبر الإنترنت فقط؛ لا يقرأ Laya الصفحات هنا. الإيقاف يَسِم كل تشغيل بأنه من القواعد فقط."),
        "f.browser.time_limit_s": ("How long a page waits for Laya before it gets the address check alone.", "كم تنتظر الصفحة Laya قبل أن تحصل على فحص العنوان وحده."),
        "f.browser.queue_size": ("How many pages may wait for Laya while it reads another.", "كم صفحة يمكنها انتظار Laya وهو يقرأ غيرها."),
        "f.watchers.use_laya": ("On: the expiry watcher asks Laya what each document is. Off: the watchers run their rules only and say the Laya check did not run.",
                                "مُفعّل: يسأل مراقب انتهاء الصلاحية Laya عن نوع كل مستند. متوقف: يعمل المراقِبون بقواعدهم فقط ويذكرون أن فحص Laya لم يجرِ."),
        "f.flights.use_laya": ("On: Laya ranks offers against your priorities. Off: the rules rank them (price, then convenience); faster, no model needed.",
                               "مُفعّل: يرتّب Laya العروض حسب أولوياتك. متوقف: ترتّبها القواعد (السعر ثم الراحة)؛ أسرع ولا حاجة إلى النموذج."),
        "f.game.use_laya": ("On: in Watch mode the decision model picks every move live on this iPhone; rules remove crashing moves and work the gun. Off: the rule-based pilot flies.", "مُفعّل: في وضع المشاهدة يختار نموذج القرار كل حركة مباشرةً على هذا الـiPhone، وتستبعد القواعد الحركات التي تؤدي إلى الاصطدام وتتولى الإطلاق. متوقف: يقود الطيار الآلي القائم على القواعد."),
        "f.game.max_decisions_per_s": ("Caps how often Laya is asked while it flies. Lower saves battery and heat; the plane reacts less often (the safety override still steers). Off = as often as the game asks.",
                                       "يحدّ عدد مرات سؤال Laya أثناء القيادة. الأقل يوفّر البطارية والحرارة؛ وتستجيب الطائرة أقل (ويبقى تجاوز السلامة يوجّهها). متوقف = بقدر ما تطلب اللعبة."),
        "f.routing": ("Which model reads for this feature.", "أي نموذج يقرأ لهذه الميزة."),
        "f.text_chars": ("How much text Laya reads per item. More context can help; each decision is slower and uses more battery.",
                         "كم من النص يقرأ Laya لكل عنصر. السياق الأكثر قد يساعد؛ وكل قرار أبطأ ويستهلك بطارية أكثر."),
        "f.text_chars.none": ("Laya reads no text for this feature on iPhone yet, so the limit changes nothing here.",
                              "لا يقرأ Laya نصًا لهذه الميزة على الـ iPhone بعد، لذا لا يغيّر الحد شيئًا هنا."),

        // Banners
        "banner.layaOff": ("Laya was off for this run — answers come from rules only.", "كان Laya متوقفًا في هذا التشغيل — الأجوبة من القواعد فقط."),
        "banner.turnOn": ("Turn it on", "شغّله"),
        "banner.game": ("Laya is off for the game (Model settings), so the baseline autopilot is flying.", "Laya متوقف للعبة (إعدادات النموذج)، لذا يقود الطيار الآلي الأساسي."),
    ]
}
