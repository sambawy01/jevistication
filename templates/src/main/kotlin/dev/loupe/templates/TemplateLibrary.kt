package dev.loupe.templates

import dev.loupe.engine.BuiltInJudgments
import dev.loupe.engine.FailurePosture
import dev.loupe.engine.FailurePosture.LOUD
import dev.loupe.engine.FailurePosture.NULL_ACTION
import dev.loupe.engine.FailurePosture.OPEN
import dev.loupe.engine.JudgmentDefinition
import dev.loupe.templates.Baseline.Constant
import dev.loupe.templates.Baseline.DateBefore
import dev.loupe.templates.Baseline.Keyword
import dev.loupe.templates.Baseline.KeywordMap
import dev.loupe.templates.Baseline.Pattern
import dev.loupe.templates.Baseline.SenderIs
import dev.loupe.templates.SourceKind.DOCUMENT
import dev.loupe.templates.SourceKind.EMAIL
import dev.loupe.templates.SourceKind.PHOTO
import dev.loupe.templates.SourceKind.SPREADSHEET

/**
 * The template question library: the seven built-in judgments grown into a browsable set, grouped
 * by what people actually keep.
 *
 * **How these are written.** Each question asks one focused thing (§6 lever 2: decompose hard
 * judgments into easy ones and let code combine them), in words a person would use, with an
 * explicit no-op option wherever a choice could otherwise force the model to pick something that
 * does not apply. The three-part criteria name the near misses that decide the hard cases. Safety
 * judgments are **warn-only** and fail **loud**: a negative answer is never shown as "safe", and a
 * failed answer is never silent. Baselines are deliberately dumb — a keyword, a pattern, a date
 * rule, a sender rule, or "always no" — because the product's claim is only worth anything if the
 * model is measured against the thing it replaces.
 *
 * None of these carry an accuracy claim. There is no labelled corpus yet; what a template is worth
 * on your data is what the calibration and baseline screens measure from your corrections.
 */
object TemplateLibrary {

    private const val PHOTO_NOTE =
        "On the desktop Loupe reads a photo's file name and metadata only — there is no OCR or image " +
            "labelling here, so a photo with no readable text is not sent to the model. The phone " +
            "build adds both."

    private const val WARN_NOTE =
        "Warn-only: a 'no' here means these checks found nothing, which is not an all-clear."

    private fun ex(text: String, answer: String, why: String) = TemplateExample(text, answer, why)

    private fun fromBuiltIn(
        definition: JudgmentDefinition,
        category: Category,
        title: String,
        sources: Set<SourceKind>,
        baseline: Baseline?,
        examples: List<TemplateExample>,
        mechanical: MechanicalCheck? = null,
        desktopNote: String? = null,
    ) = Template(
        id = definition.judgment.id,
        category = category,
        title = title,
        question = definition.judgment.question,
        shape = Shape.YesNo,
        invariant = definition.invariant,
        breaks = definition.breaks,
        lookalikes = definition.lookalikes,
        onFailure = definition.judgment.onFailure,
        sources = sources,
        baseline = baseline,
        examples = examples,
        mechanical = mechanical,
        desktopNote = desktopNote,
    )

    // ---------------------------------------------------------------- Money & receipts

    private val money = listOf(
        fromBuiltIn(
            BuiltInJudgments.RECEIPT, Category.MONEY, "Receipts",
            setOf(EMAIL, DOCUMENT, PHOTO),
            Keyword(listOf("receipt", "total paid", "amount paid", "paid with", "payment received"), "yes", "no"),
            listOf(
                ex("Fresh Basket Market. 2 x oat milk £3.10. TOTAL PAID £12.45 VISA ****1111. Thank you!", "yes", "A payee, an amount paid and a date."),
                ex("Quote #118: garden fence replacement, estimated £1,450. Valid for 30 days.", "no", "A quote: nothing has been paid."),
                ex("Your order has shipped! Tracking number 1Z999AA10123456784.", "no", "Evidence of dispatch, not of payment."),
            ),
        ),
        Template(
            id = "tax-receipt",
            category = Category.MONEY,
            title = "Receipts for tax time",
            question = "Is this a record of a payment I may need for my tax return?",
            shape = Shape.YesNo,
            invariant = "Evidence of money paid or received that a tax return can rest on: donations, business " +
                "expenses, professional fees, medical costs, pension or childcare payments, invoices you issued.",
            breaks = "Everyday personal spending with no tax treatment — groceries, streaming, a coffee.",
            lookalikes = "A bank statement lists payments but is not the receipt for any of them. A charity newsletter " +
                "mentions giving without evidencing a gift. A payslip is a tax record but not a receipt.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, PHOTO, SPREADSHEET),
            baseline = Keyword(listOf("donation", "gift aid", "invoice", "tax", "deductible", "professional fee"), "yes", "no"),
            examples = listOf(
                ex("Thank you for your donation of £50.00 to Riverside Food Bank. Gift Aid declared.", "yes", "A donation with Gift Aid is a tax record."),
                ex("Streamflix: your monthly plan £9.99 was charged.", "no", "Personal entertainment has no tax treatment."),
            ),
        ),
        Template(
            id = "receipt-kind",
            category = Category.MONEY,
            title = "What a purchase was for",
            question = "What kind of purchase is this for?",
            shape = Shape.Pick(
                listOf("groceries", "eating out", "travel", "bills and utilities", "software or subscription", "health", "something else", "not a purchase"),
                noOp = "not a purchase",
            ),
            invariant = "Names what the money bought, from the merchant and the items, not from the payment method.",
            breaks = "Nothing was bought — it is a statement, an advert or a price list — which is 'not a purchase'.",
            lookalikes = "A supermarket receipt for a phone charger is 'something else', not 'groceries'. A train ticket " +
                "bought through an app is 'travel', not 'software'.",
            onFailure = OPEN,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = KeywordMap(
                listOf(
                    KeywordMap.Rule(listOf("supermarket", "market", "grocer", "grocery"), "groceries"),
                    KeywordMap.Rule(listOf("restaurant", "cafe", "takeaway", "bistro"), "eating out"),
                    KeywordMap.Rule(listOf("flight", "train", "hotel", "taxi", "boarding"), "travel"),
                    KeywordMap.Rule(listOf("electricity", "gas", "water", "broadband", "utility"), "bills and utilities"),
                    KeywordMap.Rule(listOf("subscription", "plan", "licence", "license"), "software or subscription"),
                    KeywordMap.Rule(listOf("pharmacy", "clinic", "dental", "prescription"), "health"),
                ),
                otherwise = "not a purchase",
            ),
            examples = listOf(
                ex("Fresh Basket Market receipt: bread, eggs, apples. Total £8.20.", "groceries", "A food shop."),
                ex("Northline Rail e-ticket, London to York, £42.00 paid.", "travel", "A ticket for a journey."),
                ex("Your statement for August is ready to view.", "not a purchase", "A statement, not a purchase."),
            ),
        ),
        Template(
            id = "refund-issued",
            category = Category.MONEY,
            title = "Refunds issued",
            question = "Does this confirm that a refund has been issued to me?",
            shape = Shape.YesNo,
            invariant = "A merchant or bank states money is being returned, with an amount or an order it applies to.",
            breaks = "The refund is only requested, under review, or offered as store credit you have not accepted.",
            lookalikes = "'Your return has been received' precedes a refund but is not one. A 'refund policy' footer on " +
                "every receipt mentions refunds without issuing one.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("refund issued", "refunded", "has been refunded", "refund of"), "yes", "no"),
            examples = listOf(
                ex("We've refunded £24.99 to your card ending 1111. It can take 5 days to appear.", "yes", "Money returned, with an amount."),
                ex("We have received your return and will inspect it within 7 days.", "no", "Received, not yet refunded."),
            ),
        ),
        Template(
            id = "bill-unpaid",
            category = Category.MONEY,
            title = "Bills still to pay",
            question = "Is this a bill that still needs paying?",
            shape = Shape.YesNo,
            invariant = "An amount is owed by the recipient and has not been paid: an invoice, a final demand, a due notice.",
            breaks = "It confirms payment, or the amount will be taken automatically by direct debit with nothing to do.",
            lookalikes = "A receipt shows an amount and a merchant too, but the money has moved. A statement 'balance' on a " +
                "card paid in full each month is not a bill.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("amount due", "payment due", "please pay", "overdue", "final notice", "due date"), "yes", "no"),
            examples = listOf(
                ex("Invoice INV-2207 from Brightside Plumbing. Amount due £180.00 by 30 September 2026.", "yes", "Owed, with a due date."),
                ex("Thanks — we received your payment of £180.00 for invoice INV-2207.", "no", "Paid."),
                ex("Your direct debit of £62.00 will be collected on 1 October.", "no", "Collected automatically; nothing to do."),
            ),
        ),
        Template(
            id = "warranty-proof",
            category = Category.MONEY,
            title = "Proof of purchase for a warranty",
            question = "Is this proof of purchase for an item that could need a warranty claim?",
            shape = Shape.YesNo,
            invariant = "A receipt or invoice for a durable good — an appliance, electronics, furniture, a tool — that " +
                "shows the item, the seller and the date.",
            breaks = "Consumables, services, food, or a purchase that names no item.",
            lookalikes = "A warranty registration card is about the warranty, not proof of purchase. An order " +
                "confirmation before dispatch is weaker than the invoice that follows it.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("warranty", "guarantee", "serial number", "model no"), "yes", "no"),
            examples = listOf(
                ex("Invoice: Kettle KX-200, serial 44A91, £39.99, sold by Homeware Direct, 12 March 2026.", "yes", "A durable good, a seller and a date."),
                ex("Receipt: 1 x flat white £3.20.", "no", "A coffee needs no warranty."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Documents & deadlines

    private val documents = listOf(
        fromBuiltIn(
            BuiltInJudgments.EXPIRING, Category.DOCUMENTS, "Documents that expire",
            setOf(DOCUMENT, PHOTO, EMAIL),
            Keyword(listOf("expiry", "expires", "expiration", "valid until", "date of expiry", "renewal date"), "yes", "no"),
            listOf(
                ex("PASSPORT. Surname SAMPLE. Date of issue 14 JAN 2017. Date of expiry 14 JAN 2027.", "yes", "A passport lapses, and it matters."),
                ex("Bank statement, period 1–31 August 2026.", "no", "A statement period is not an expiry."),
            ),
        ),
        Template(
            id = "expires-before",
            category = Category.DOCUMENTS,
            title = "Documents that expire before {date}",
            question = "Is this a document whose expiry date matters, such as a passport, visa, licence, policy or lease?",
            shape = Shape.YesNo,
            invariant = "The model decides only what the document is. Whether it lapses before {date} is date arithmetic, " +
                "done mechanically on the dates found in it — the model never does sums.",
            breaks = "It has no expiry, or its expiry carries no consequence.",
            lookalikes = "Issue dates, statement periods and printed-on dates are dates but not expiries; the latest date " +
                "on a document is taken as its expiry, and an ambiguous date on its earlier reading.",
            onFailure = LOUD,
            sources = setOf(DOCUMENT, PHOTO, EMAIL),
            baseline = DateBefore("{date}", "yes", "no"),
            parameters = listOf(Parameter("date", ParamKind.DATE, "Warn about anything expiring before", "2027-03-31")),
            examples = listOf(
                ex("Driving licence. Valid until 02.02.2027.", "yes", "A licence; the date check runs separately."),
                ex("Council tax bill for 2026/27.", "no", "A bill has a due date, not an expiry."),
            ),
        ),
        Template(
            id = "document-type",
            category = Category.DOCUMENTS,
            title = "Identity and entitlement documents",
            question = "Which kind of official document is this?",
            shape = Shape.Pick(
                listOf("passport", "visa or residence permit", "driving licence", "insurance policy", "warranty", "lease or tenancy", "none of these"),
                noOp = "none of these",
            ),
            invariant = "The document itself, or a faithful copy or scan of it, of exactly one of these kinds.",
            breaks = "A letter about the document, a form to apply for one, or anything else — 'none of these'.",
            lookalikes = "A visa application form is not a visa. An insurance quote is not a policy. A car-rental " +
                "agreement mentions a driving licence without being one.",
            onFailure = LOUD,
            sources = setOf(DOCUMENT, PHOTO, EMAIL),
            baseline = KeywordMap(
                listOf(
                    KeywordMap.Rule(listOf("passport"), "passport"),
                    KeywordMap.Rule(listOf("visa", "residence permit"), "visa or residence permit"),
                    KeywordMap.Rule(listOf("driving licence", "driver's license", "driving license"), "driving licence"),
                    KeywordMap.Rule(listOf("policy number", "policy schedule", "insurance"), "insurance policy"),
                    KeywordMap.Rule(listOf("warranty"), "warranty"),
                    KeywordMap.Rule(listOf("tenancy", "lease"), "lease or tenancy"),
                ),
                otherwise = "none of these",
            ),
            examples = listOf(
                ex("PASSPORT. Type P. Surname SAMPLE. Date of expiry 14 JAN 2027.", "passport", "The document itself."),
                ex("Policy schedule — Home insurance, policy number HX-0042, renewal 1 November 2026.", "insurance policy", "A policy schedule."),
                ex("Apply for a visa online: fill in form VAF-1.", "none of these", "An application, not a visa."),
            ),
        ),
        Template(
            id = "deadline",
            category = Category.DOCUMENTS,
            title = "Deadlines",
            question = "Does this set a deadline that I have to meet?",
            shape = Shape.YesNo,
            invariant = "A date or time by which the recipient must act — pay, submit, respond, renew, collect — with a " +
                "consequence for missing it.",
            breaks = "The date is for someone else to act, or nothing happens if it passes.",
            lookalikes = "'Sale ends Sunday' is a deadline with no obligation. An event date is not a deadline unless " +
                "something must be done before it.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("deadline", "by no later than", "due by", "must be received", "submit by", "respond by"), "yes", "no"),
            examples = listOf(
                ex("Your self-assessment return must be received by 31 January 2027 or a £100 penalty applies.", "yes", "An obligation with a penalty."),
                ex("Our summer sale ends this Sunday!", "no", "A marketing date, not an obligation."),
            ),
        ),
        Template(
            id = "signed-agreement",
            category = Category.DOCUMENTS,
            title = "Signed contracts and agreements",
            question = "Is this a signed contract or agreement?",
            shape = Shape.YesNo,
            invariant = "Terms both parties have accepted, with signatures, a signature block marked signed, or an " +
                "e-signature certificate.",
            breaks = "An unsigned version sent for review, or terms and conditions you merely clicked past.",
            lookalikes = "A later unsigned revision can supersede a signed one without being one. A proposal is laid out " +
                "like a contract and binds no one.",
            onFailure = NULL_ACTION,
            sources = setOf(DOCUMENT, EMAIL, PHOTO),
            baseline = Keyword(listOf("signed", "signature", "e-signature", "executed"), "yes", "no"),
            examples = listOf(
                ex("TENANCY AGREEMENT ... Signed: A. Sample (Tenant), 1 May 2026. Signed: B. Example (Landlord).", "yes", "Signed by both parties."),
                ex("Please review the attached draft agreement and send comments.", "no", "Sent for review, unsigned."),
            ),
        ),
        Template(
            id = "official-notice",
            category = Category.DOCUMENTS,
            title = "Official notices",
            question = "Is this an official notice from a court, tax office or government body?",
            shape = Shape.YesNo,
            invariant = "Issued by a public authority, addressed to the recipient, about their own case, tax, benefits, " +
                "vehicle, licence or legal matter.",
            breaks = "Generic public information, a newsletter from a council, or a private company using official-sounding language.",
            lookalikes = "Scam messages imitate tax offices precisely because people act on them; the sender's address " +
                "and any link are what the fraud watchers check. A solicitor's letter is legal but not official.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("HMRC", "court", "tax office", "IRS", "council tax", "DVLA", "penalty notice"), "yes", "no"),
            examples = listOf(
                ex("Council Tax bill 2026/27, account 55501. Annual amount £1,812.00.", "yes", "A public body, about your own account."),
                ex("Riverside Council newsletter: new recycling bins arrive in October.", "no", "General public information."),
            ),
        ),
        Template(
            id = "keep-how-long",
            category = Category.DOCUMENTS,
            title = "How long to keep a document",
            question = "How long is this worth keeping?",
            shape = Shape.Pick(
                listOf("keep permanently", "keep for a few years", "keep until it is replaced", "fine to discard", "not sure"),
                noOp = "not sure",
            ),
            invariant = "Permanent: identity, property, wills, qualifications. Years: tax and financial records. Until " +
                "replaced: policies, statements, manuals superseded by the next one. Discard: nothing depends on it.",
            breaks = "It is not a document anyone keeps — use 'not sure' rather than forcing a bin.",
            lookalikes = "Old is not disposable: a ten-year-old deed is permanent. A receipt is disposable unless it " +
                "backs a warranty or a tax claim.",
            onFailure = NULL_ACTION,
            sources = setOf(DOCUMENT, EMAIL, PHOTO),
            baseline = Constant("not sure"),
            examples = listOf(
                ex("Degree certificate, Bachelor of Science, awarded to A. Sample.", "keep permanently", "A qualification."),
                ex("Home insurance policy schedule 2025–26.", "keep until it is replaced", "Superseded at renewal."),
                ex("Parking confirmation: bay 14, 2 hours, paid.", "fine to discard", "Nothing depends on it after the day."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Email triage

    private val email = listOf(
        fromBuiltIn(
            BuiltInJudgments.NEEDS_REPLY, Category.EMAIL, "Needs a reply",
            setOf(EMAIL),
            Pattern("""\?\s*$|\?\s*\n""", "yes", "no", "a line ending in a question mark"),
            listOf(
                ex("From: Priya <priya@example.org>\nCan you confirm you're free on Thursday for the handover?", "yes", "A direct question to you."),
                ex("From: Newsletter <news@example.com>\nWhat will you cook this weekend? Try our new recipes!", "no", "A rhetorical question in bulk mail."),
            ),
        ),
        Template(
            id = "reply-from-sender",
            category = Category.EMAIL,
            title = "Messages from {sender} that need a reply",
            question = "Is this message from {sender} waiting on a response from me?",
            shape = Shape.YesNo,
            invariant = "Written by {sender} and expecting something back from the recipient: an answer, a decision, a " +
                "document or a confirmation.",
            breaks = "It is from someone else, it is informational, or the thread shows it was already answered.",
            lookalikes = "A forwarded message from {sender} may be waiting on the person it was forwarded from. An automated " +
                "notice sent in {sender}'s name expects nothing.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL),
            baseline = SenderIs("{sender}", "yes", "no", andAsksQuestion = true),
            parameters = listOf(Parameter("sender", ParamKind.SENDER, "Sender name, address or domain", "priya@example.org")),
            examples = listOf(
                ex("From: Priya <priya@example.org>\nCould you send the signed form by Friday?", "yes", "Asks you for something."),
                ex("From: Priya <priya@example.org>\nFYI — the office is closed on Monday.", "no", "Informational."),
            ),
        ),
        Template(
            id = "urgency",
            category = Category.EMAIL,
            title = "How soon something needs attention",
            question = "How soon does this need my attention, where 0 is no action, 1 is this month, 2 is this week and 3 is today?",
            shape = Shape.Ordinal(0..3, listOf("no action needed", "this month", "this week", "today")),
            invariant = "The band is set by the earliest consequence for the recipient, not by how loudly the message is worded.",
            breaks = "Messages that need nothing at all are 0, however urgent they sound.",
            lookalikes = "'URGENT' in a subject line is a marketing and phishing habit, not a deadline. A calm message " +
                "about a payment failing tomorrow is a 3.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = KeywordMap(
                listOf(
                    KeywordMap.Rule(listOf("today", "immediately", "within 24 hours", "tonight"), "3"),
                    KeywordMap.Rule(listOf("this week", "by friday", "by monday", "tomorrow"), "2"),
                    KeywordMap.Rule(listOf("this month", "by the end of the month", "within 30 days"), "1"),
                ),
                otherwise = "0",
            ),
            examples = listOf(
                ex("Your card payment failed. Update your details today to keep your plan active.", "3", "A consequence today."),
                ex("Reminder: team photos are next month; nothing needed yet.", "0", "No action."),
                ex("Can you review the budget by Friday?", "2", "This week."),
            ),
        ),
        Template(
            id = "email-kind",
            category = Category.EMAIL,
            title = "What kind of email",
            question = "What kind of email is this?",
            shape = Shape.Pick(
                listOf("from a person I know", "work", "newsletter", "receipt or order", "account notification", "marketing", "not sure"),
                noOp = "not sure",
            ),
            invariant = "Classified by who sent it and why, not by its layout: a person writing to you, your work, a " +
                "publication you subscribed to, a transaction, a service telling you about your account, or selling.",
            breaks = "Mixed or unreadable messages are 'not sure' rather than a guess.",
            lookalikes = "Marketing often dresses as an account notification ('Your account has a reward!'). A newsletter " +
                "from a colleague's personal blog is a newsletter, not work.",
            onFailure = OPEN,
            sources = setOf(EMAIL),
            baseline = KeywordMap(
                listOf(
                    KeywordMap.Rule(listOf("unsubscribe", "view in browser"), "newsletter"),
                    KeywordMap.Rule(listOf("order", "receipt", "invoice"), "receipt or order"),
                    KeywordMap.Rule(listOf("your account", "password", "sign-in", "security alert"), "account notification"),
                    KeywordMap.Rule(listOf("offer", "sale", "% off", "discount"), "marketing"),
                ),
                otherwise = "not sure",
            ),
            examples = listOf(
                ex("From: Mum <mum@family.example>\nLovely to see you on Sunday. x", "from a person I know", "Personal."),
                ex("From: Streamflix <billing@streamflix.example>\nYour receipt for September: £9.99.", "receipt or order", "A transaction."),
                ex("From: Garden Weekly <news@gardenweekly.example>\nThis week: autumn bulbs. Unsubscribe here.", "newsletter", "A publication."),
            ),
        ),
        Template(
            id = "meeting-request",
            category = Category.EMAIL,
            title = "Meeting requests",
            question = "Is this asking me to attend or arrange a meeting?",
            shape = Shape.YesNo,
            invariant = "The recipient is invited to, or asked to find a time for, a meeting, call or appointment.",
            breaks = "A notice that a meeting happened, minutes, or a webinar advert sent to everyone.",
            lookalikes = "Calendar confirmations of a meeting you already accepted need no decision. A 'book a demo' " +
                "footer is an advert.",
            onFailure = OPEN,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("meeting", "invite", "invitation", "call", "calendar", "are you free"), "yes", "no"),
            examples = listOf(
                ex("Are you free for a 30-minute call on Tuesday to go through the plan?", "yes", "Asks you to find a time."),
                ex("Minutes from yesterday's meeting are attached.", "no", "A record of a past meeting."),
            ),
        ),
        Template(
            id = "someone-will-follow-up",
            category = Category.EMAIL,
            title = "Promised follow-ups",
            question = "Is the sender promising to get back to me later?",
            shape = Shape.YesNo,
            invariant = "The sender commits to a later action for the recipient — a reply, a document, a decision, a call.",
            breaks = "The promise is conditional on the recipient doing something first, or it is boilerplate.",
            lookalikes = "'We'll be in touch' in an automated acknowledgement is boilerplate; 'I'll send the figures on " +
                "Thursday' from a person is a promise you might want to chase.",
            onFailure = OPEN,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("I'll get back", "I will get back", "will follow up", "I'll send", "I will send"), "yes", "no"),
            examples = listOf(
                ex("Thanks — I'll send the revised quote on Thursday.", "yes", "A dated promise."),
                ex("We have received your enquiry. We'll be in touch.", "no", "Boilerplate acknowledgement."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Subscriptions

    private val subscriptions = listOf(
        fromBuiltIn(
            BuiltInJudgments.UNSUBSCRIBE_CANDIDATE, Category.SUBSCRIPTIONS, "Unsubscribe candidates",
            setOf(EMAIL),
            Keyword(listOf("unsubscribe", "manage preferences", "email preferences"), "yes", "no"),
            listOf(
                ex("From: Deals Daily <offers@dealsdaily.example>\nToday only: 40% off. Unsubscribe | Manage preferences", "yes", "Recurring bulk marketing."),
                ex("From: Northbank <alerts@northbank.example>\nA new sign-in to your account. Manage email preferences.", "no", "A security alert you must keep getting."),
            ),
        ),
        Template(
            id = "subscription-charge",
            category = Category.SUBSCRIPTIONS,
            title = "Subscription charges",
            question = "Is this a charge for a recurring subscription?",
            shape = Shape.YesNo,
            invariant = "Money taken on a schedule for continuing access — a plan, a membership, a licence — with an amount.",
            breaks = "A one-off purchase, an instalment of a single purchase, or a free plan with no charge.",
            lookalikes = "A marketing email about a plan is not a charge. A utility bill is recurring but metered, which " +
                "the recurring-money watcher counts separately.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, SPREADSHEET),
            baseline = Keyword(listOf("subscription", "membership", "monthly plan", "annual plan", "renews", "recurring"), "yes", "no"),
            examples = listOf(
                ex("Streamflix: we charged £9.99 for your monthly plan. Next payment 5 October.", "yes", "A scheduled charge."),
                ex("Thanks for buying 'Mountain Atlas' (e-book), £7.99.", "no", "A one-off purchase."),
            ),
        ),
        Template(
            id = "trial-ending",
            category = Category.SUBSCRIPTIONS,
            title = "Free trials about to charge",
            question = "Does this warn that a free trial is about to turn into a paid plan?",
            shape = Shape.YesNo,
            invariant = "A trial ends on a stated date and money will be taken unless the recipient cancels.",
            breaks = "The trial ends with no charge, or it was already converted and this is a receipt.",
            lookalikes = "'Start your free trial' is an advert. 'Your trial has ended' after the charge is a receipt, not a warning.",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("trial ends", "trial will end", "free trial", "trial is ending"), "yes", "no"),
            examples = listOf(
                ex("Your GymPass free trial ends on 30 September. You'll then be charged £29.99 a month unless you cancel.", "yes", "A dated charge unless you act."),
                ex("Start your 30-day free trial of CloudBox Premium today!", "no", "An advert."),
            ),
        ),
        Template(
            id = "price-rise",
            category = Category.SUBSCRIPTIONS,
            title = "Price rises",
            question = "Does this announce that a price I pay is going up?",
            shape = Shape.YesNo,
            invariant = "An existing charge, premium, rent or plan price rises for the recipient, from a stated date.",
            breaks = "Prices fall or stay the same, or the change applies only to new customers.",
            lookalikes = "Renewal letters bury rises in tables without the word 'increase'; the term-change watcher compares " +
                "the amounts mechanically. 'Prices from £X' in an advert is not your price.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("price increase", "price change", "will increase", "going up", "new price", "premium has changed"), "yes", "no"),
            examples = listOf(
                ex("From 1 November your plan will cost £12.99 a month (currently £9.99).", "yes", "Your price rises."),
                ex("New customers: get broadband from £20 a month.", "no", "An advert, not your price."),
            ),
        ),
        Template(
            id = "auto-renewal",
            category = Category.SUBSCRIPTIONS,
            title = "Automatic renewal notices",
            question = "Is this a notice that something will renew automatically?",
            shape = Shape.YesNo,
            invariant = "A policy, plan, membership or domain renews on a stated date without further action.",
            breaks = "It asks you to renew manually, or it has already renewed and this is the receipt.",
            lookalikes = "Insurance renewal invitations that need you to accept are not automatic. A receipt after " +
                "renewal is too late to be a notice.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("auto-renew", "automatically renew", "will renew", "renews on", "auto renewal"), "yes", "no"),
            examples = listOf(
                ex("Your home insurance will renew automatically on 1 November 2026. No action is needed.", "yes", "Renews without action."),
                ex("Your policy expires soon — renew online to stay covered.", "no", "Manual renewal."),
            ),
        ),
        Template(
            id = "cancellation-confirmed",
            category = Category.SUBSCRIPTIONS,
            title = "Cancellations confirmed",
            question = "Does this confirm that a subscription or service was cancelled?",
            shape = Shape.YesNo,
            invariant = "The provider states the cancellation took effect or will on a date, and no further charges follow.",
            breaks = "A cancellation request is only acknowledged, pending, or the provider is trying to retain you.",
            lookalikes = "'We're sorry to see you go — here's 50% off to stay' is a retention offer, not a confirmation.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("has been cancelled", "cancellation confirmed", "successfully cancelled", "has been canceled"), "yes", "no"),
            examples = listOf(
                ex("Your CloudBox subscription has been cancelled. You won't be charged again.", "yes", "Confirmed, no further charges."),
                ex("Before you go: stay for 3 months at half price.", "no", "A retention offer."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Files & cleanup

    private val files = listOf(
        fromBuiltIn(
            BuiltInJudgments.DUPLICATE, Category.FILES, "Duplicates",
            setOf(DOCUMENT, PHOTO, EMAIL),
            Pattern("""\(\s*\d+\s*\)|\bcopy\b""", "yes", "no", "\"copy\" or a \"(1)\"-style suffix"),
            listOf(
                ex("receipt-grocery-2026-08 (copy).txt — identical text to receipt-grocery-2026-08.txt", "yes", "Exact copies are caught by hash before the model."),
                ex("Tenancy agreement v2, signed — differs from v1 by the signatures.", "no", "A signed version differs in a way that matters."),
            ),
            mechanical = MechanicalCheck.EXACT_DUPLICATE,
        ),
        fromBuiltIn(
            BuiltInJudgments.STALE, Category.FILES, "Stale files",
            setOf(DOCUMENT, EMAIL, PHOTO),
            Constant("no"),
            listOf(
                ex("Parking confirmation for 3 March 2024, bay 14.", "yes", "Its purpose passed long ago."),
                ex("House purchase completion statement, 2016.", "no", "Old but legally important."),
            ),
        ),
        fromBuiltIn(
            BuiltInJudgments.JUNK, Category.FILES, "Junk",
            setOf(DOCUMENT, PHOTO, EMAIL),
            Constant("no"),
            listOf(
                ex("Untitled document: 'asdf asdf test'.", "yes", "No value to anyone."),
                ex("Screenshot: booking reference QX7-4411, check-in from 15:00.", "no", "A record of a booking."),
            ),
        ),
        Template(
            id = "refetchable-download",
            category = Category.FILES,
            title = "Downloads you can fetch again",
            question = "Is this a download that could simply be fetched again if deleted?",
            shape = Shape.YesNo,
            invariant = "Publicly available material the recipient did not create: installers, manuals, public " +
                "reports, brochures, sample files.",
            breaks = "Anything personal or one-off: a statement, a signed form, a ticket, a document made for you.",
            lookalikes = "A PDF manual is re-fetchable; a PDF invoice with your name on it is not. A downloaded bank " +
                "statement can be downloaded again — but only while the bank keeps it.",
            onFailure = NULL_ACTION,
            sources = setOf(DOCUMENT),
            baseline = Keyword(listOf("installer", "user manual", "setup", "brochure", "download"), "yes", "no"),
            examples = listOf(
                ex("Kettle KX-200 user manual. Safety instructions. Descaling.", "yes", "A public manual."),
                ex("Your personalised pension statement, 2026.", "no", "Made for you."),
            ),
        ),
        Template(
            id = "superseded-version",
            category = Category.FILES,
            title = "Earlier versions",
            question = "Is this an earlier version of a document that has a later version?",
            shape = Shape.YesNo,
            invariant = "The text says or shows that a newer revision exists — version numbers, 'superseded', 'final' " +
                "elsewhere — and this one adds nothing the newer one lacks.",
            breaks = "It is the final or signed version, or it holds content later versions dropped.",
            lookalikes = "'v2' in a file name is not proof a v3 exists. Annual documents (2025 policy, 2026 policy) are " +
                "separate records, not versions of one.",
            onFailure = NULL_ACTION,
            sources = setOf(DOCUMENT),
            baseline = Pattern("""\b(v\d+|version \d+|superseded|old)\b""", "yes", "no", "a version marker or 'superseded'"),
            examples = listOf(
                ex("Project plan v1 — superseded by v3 (see shared folder).", "yes", "Says a newer version exists."),
                ex("Project plan — FINAL, approved 2 June.", "no", "The final version."),
            ),
        ),
        Template(
            id = "file-home",
            category = Category.FILES,
            title = "Where a file belongs",
            question = "Which area of life does this file belong to?",
            shape = Shape.Pick(
                listOf("money", "home", "health", "work", "travel", "identity", "family and personal", "not sure"),
                noOp = "not sure",
            ),
            invariant = "The area the file is about for its owner, decided by its subject, not its format.",
            breaks = "Files about several areas equally, or none, are 'not sure'.",
            lookalikes = "A travel-insurance policy is travel, not money. A payslip is work and money; pick the one a " +
                "person would file it under — money.",
            onFailure = OPEN,
            sources = setOf(DOCUMENT, PHOTO, EMAIL),
            baseline = KeywordMap(
                listOf(
                    KeywordMap.Rule(listOf("bank", "statement", "invoice", "receipt", "tax"), "money"),
                    KeywordMap.Rule(listOf("tenancy", "lease", "mortgage", "boiler", "council tax"), "home"),
                    KeywordMap.Rule(listOf("doctor", "prescription", "appointment", "clinic", "vaccination"), "health"),
                    KeywordMap.Rule(listOf("meeting", "project", "client", "payroll"), "work"),
                    KeywordMap.Rule(listOf("flight", "hotel", "booking", "itinerary"), "travel"),
                    KeywordMap.Rule(listOf("passport", "birth certificate", "driving licence"), "identity"),
                ),
                otherwise = "not sure",
            ),
            examples = listOf(
                ex("Boiler service certificate, 12 Elm Road, next service due March 2027.", "home", "About the home."),
                ex("Flight itinerary LHR–LIS, booking ref QX7-4411.", "travel", "A trip."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Photos

    private val photos = listOf(
        Template(
            id = "screenshot-worth-keeping",
            category = Category.PHOTOS,
            title = "Screenshots worth keeping",
            question = "Is this a screenshot of something worth keeping, such as a confirmation, ticket or code?",
            shape = Shape.YesNo,
            invariant = "A screen capture whose content is a record: a booking or order confirmation, a ticket, a " +
                "reference number, a receipt, an address.",
            breaks = "Memes, accidental captures, and screenshots of things you can look up again trivially.",
            lookalikes = "A screenshot of a conversation may matter to someone — err towards keeping when it names a " +
                "person, a date or an amount.",
            onFailure = NULL_ACTION,
            sources = setOf(PHOTO),
            baseline = Keyword(listOf("booking", "confirmation", "ticket", "reference", "order"), "yes", "no"),
            examples = listOf(
                ex("Screenshot. Text: 'Booking confirmed. Reference QX7-4411. Check-in 15:00.'", "yes", "A booking record."),
                ex("Screenshot. Text: 'lol same'.", "no", "Nothing to keep."),
            ),
            desktopNote = PHOTO_NOTE,
        ),
        Template(
            id = "photo-of-document",
            category = Category.PHOTOS,
            title = "Photos of paper documents",
            question = "Is this a photo of a paper document?",
            shape = Shape.YesNo,
            invariant = "A photograph whose subject is a page: a letter, a receipt, a form, a certificate, a ticket.",
            breaks = "People, places and objects, even with some text in the frame.",
            lookalikes = "A photo of a shop sign or a menu board has text but is not a document you own.",
            onFailure = OPEN,
            sources = setOf(PHOTO),
            baseline = Keyword(listOf("scan", "document", "receipt", "letter"), "yes", "no"),
            examples = listOf(
                ex("Image 'IMG_2044.jpg', 3024x4032. Text found: 'Invoice INV-2207 Amount due £180.00'.", "yes", "A page."),
                ex("Image 'IMG_2051.jpg', 4032x3024, taken at 18:40. No text found.", "no", "Nothing suggests a page."),
            ),
            desktopNote = PHOTO_NOTE,
        ),
        Template(
            id = "photo-subject",
            category = Category.PHOTOS,
            title = "What a photo is of",
            question = "What is this photo mostly of?",
            shape = Shape.Pick(
                listOf("people", "a document or receipt", "a screen", "a place", "food", "an object", "not sure"),
                noOp = "not sure",
            ),
            invariant = "The main subject of the frame.",
            breaks = "When the evidence is only a file name and metadata, 'not sure' is the honest answer.",
            lookalikes = "A photo of a screen showing a document is 'a screen'. A restaurant receipt on a table is 'a " +
                "document or receipt', not 'food'.",
            onFailure = OPEN,
            sources = setOf(PHOTO),
            baseline = Constant("not sure"),
            examples = listOf(
                ex("Image 'receipt-cafe.jpg'. Text found: 'Flat white £3.20 TOTAL £3.20'.", "a document or receipt", "A receipt."),
                ex("Image 'IMG_3001.jpg', 4032x3024. No text found.", "not sure", "Metadata alone cannot say."),
            ),
            desktopNote = PHOTO_NOTE,
        ),
        Template(
            id = "photo-has-sensitive-data",
            category = Category.PHOTOS,
            title = "Photos showing sensitive numbers",
            question = "Does this image show a card number, ID number or password?",
            shape = Shape.YesNo,
            invariant = "Readable text in the image includes a payment card number, a national ID or passport number, a " +
                "password or a recovery code.",
            breaks = "The number is masked (**** 1111), or only a reference number is shown.",
            lookalikes = "Booking references and order numbers look like secrets and are not. A photo of the back of a " +
                "card is sensitive even without a name.",
            onFailure = LOUD,
            sources = setOf(PHOTO, DOCUMENT),
            baseline = Pattern("""\b(?:\d[ -]?){13,16}\b|password|recovery code""", "yes", "no", "a 13–16 digit number, 'password' or 'recovery code'"),
            examples = listOf(
                ex("Screenshot. Text: 'Your recovery codes: 4821-9930 7712-0044'.", "yes", "Recovery codes are secrets."),
                ex("Screenshot. Text: 'Card ending **** 1111 charged £9.99'.", "no", "Masked."),
            ),
            warnOnly = true,
            desktopNote = PHOTO_NOTE,
        ),
    )

    // ---------------------------------------------------------------- Work

    private val work = listOf(
        Template(
            id = "task-for-me",
            category = Category.WORK,
            title = "Tasks assigned to me",
            question = "Does this assign a task to me?",
            shape = Shape.YesNo,
            invariant = "Someone asks the recipient, specifically, to do a piece of work, with or without a date.",
            breaks = "The task is for someone else or a group without an owner, or it is already done.",
            lookalikes = "'Can someone look at this?' to a list assigns nobody. Meeting notes that list your name next " +
                "to an action item do assign you one.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("can you", "could you", "please", "action:", "assigned to you"), "yes", "no"),
            examples = listOf(
                ex("Could you pull together the Q3 numbers before Tuesday's review?", "yes", "A task for you."),
                ex("The Q3 numbers are now on the shared drive.", "no", "Informational."),
            ),
        ),
        Template(
            id = "about-project",
            category = Category.WORK,
            title = "About {project}",
            question = "Is this about {project}?",
            shape = Shape.YesNo,
            invariant = "The item's main subject is {project}: it discusses, plans, reports on or bills for it.",
            breaks = "{project} is mentioned in passing, in a signature, or in a list of unrelated projects.",
            lookalikes = "Another project with a similar name, or a newsletter that mentions {project} once, is not about it.",
            onFailure = OPEN,
            sources = setOf(EMAIL, DOCUMENT, SPREADSHEET),
            baseline = Keyword(listOf("{project}"), "yes", "no"),
            parameters = listOf(Parameter("project", ParamKind.TEXT, "Project name", "Harbour Bridge refit")),
            examples = listOf(
                ex("Harbour Bridge refit: revised schedule attached, cladding moves to week 42.", "yes", "The main subject."),
                ex("Company newsletter: welcome to our new starters.", "no", "Unrelated."),
            ),
        ),
        Template(
            id = "meeting-notes",
            category = Category.WORK,
            title = "Meeting notes",
            question = "Is this a set of meeting notes or minutes?",
            shape = Shape.YesNo,
            invariant = "A record of what was said or decided at a meeting: attendees, topics, decisions, actions.",
            breaks = "An agenda sent before the meeting, or an invitation.",
            lookalikes = "An agenda has the same headings and none of the outcomes.",
            onFailure = OPEN,
            sources = setOf(DOCUMENT, EMAIL),
            baseline = Keyword(listOf("minutes", "attendees", "action items", "meeting notes"), "yes", "no"),
            examples = listOf(
                ex("Meeting notes 12 Sept. Attendees: A, B. Decisions: ship Friday. Actions: A to update plan.", "yes", "Outcomes recorded."),
                ex("Agenda for Thursday: 1. Budget 2. Hiring.", "no", "An agenda."),
            ),
        ),
        Template(
            id = "expense-claimable",
            category = Category.WORK,
            title = "Work expenses to claim",
            question = "Is this a work expense I could claim back?",
            shape = Shape.YesNo,
            invariant = "A receipt for something bought for work — travel to a client, a work lunch, equipment, a course — " +
                "paid personally.",
            breaks = "Personal spending, or something the employer already paid directly.",
            lookalikes = "A receipt from a work trip's evening out may or may not be claimable; that is exactly the kind " +
                "of item the uncertain queue should ask you about.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("client", "business", "conference", "expenses", "work trip"), "yes", "no"),
            examples = listOf(
                ex("Northline Rail: London to York return £84.00. Purpose: client workshop.", "yes", "Travel for work."),
                ex("Fresh Basket Market groceries £8.20.", "no", "Personal."),
            ),
        ),
        Template(
            id = "confidential",
            category = Category.WORK,
            title = "Confidential material",
            question = "Does this contain confidential business information?",
            shape = Shape.YesNo,
            invariant = "Material not meant to leave the business: salaries, contracts, customer data, unreleased plans, " +
                "anything marked confidential.",
            breaks = "Public information, marketing copy, or personal matters.",
            lookalikes = "Email footers saying 'this message may be confidential' are on everything and prove nothing.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT, SPREADSHEET),
            baseline = Keyword(listOf("confidential", "internal only", "do not distribute", "salary", "NDA"), "yes", "no"),
            examples = listOf(
                ex("INTERNAL ONLY — 2027 salary bands and headcount plan.", "yes", "Internal plans."),
                ex("Press release: we are opening a new office in Leeds.", "no", "Public."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Travel

    private val travel = listOf(
        Template(
            id = "booking-confirmed",
            category = Category.TRAVEL,
            title = "Travel bookings",
            question = "Is this a confirmed travel booking?",
            shape = Shape.YesNo,
            invariant = "A booking reference for a journey or a stay that has been made and paid or guaranteed.",
            breaks = "A search result, a quote, a held reservation awaiting payment, or a price alert.",
            lookalikes = "'Prices to Lisbon from £49' is an advert. A booking cancellation confirms the booking no longer exists.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("booking confirmed", "booking reference", "e-ticket", "itinerary", "confirmation number"), "yes", "no"),
            examples = listOf(
                ex("Your booking is confirmed. Booking reference QX7-4411. LHR → LIS, 14 Oct 2026, 08:05.", "yes", "Reference, route, date."),
                ex("Price alert: London to Lisbon now from £49.", "no", "An advert."),
            ),
        ),
        Template(
            id = "booking-kind",
            category = Category.TRAVEL,
            title = "Kinds of travel booking",
            question = "What kind of travel booking is this?",
            shape = Shape.Pick(
                listOf("flight", "hotel or accommodation", "train or coach", "car hire", "event ticket", "not a booking"),
                noOp = "not a booking",
            ),
            invariant = "The kind of the thing booked, from its own details.",
            breaks = "Anything not a booking is 'not a booking'.",
            lookalikes = "A flight-and-hotel package is a flight (the part with the fixed time). An airport parking " +
                "booking is car hire's cousin but is 'not a booking' of travel itself.",
            onFailure = OPEN,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = KeywordMap(
                listOf(
                    KeywordMap.Rule(listOf("flight", "boarding", "airline", "gate"), "flight"),
                    KeywordMap.Rule(listOf("hotel", "check-in", "accommodation", "apartment"), "hotel or accommodation"),
                    KeywordMap.Rule(listOf("rail", "train", "coach"), "train or coach"),
                    KeywordMap.Rule(listOf("car hire", "car rental", "pick-up"), "car hire"),
                    KeywordMap.Rule(listOf("ticket", "concert", "match"), "event ticket"),
                ),
                otherwise = "not a booking",
            ),
            examples = listOf(
                ex("Booking QX7-4411: LHR → LIS, flight SP 1203, seat 14C.", "flight", "A flight."),
                ex("Casa Azul Lisbon: 3 nights from 14 Oct, check-in 15:00.", "hotel or accommodation", "A stay."),
            ),
        ),
        Template(
            id = "trip-changed",
            category = Category.TRAVEL,
            title = "Changes to booked trips",
            question = "Does this report a change to a trip I have booked?",
            shape = Shape.YesNo,
            invariant = "A carrier or host reports a new time, a cancellation, a gate or platform change, or a changed " +
                "booking the recipient holds.",
            breaks = "The original confirmation, a reminder with no change, or a change to someone else's booking.",
            lookalikes = "'Check in now' reminders arrive at the same time as changes and mean nothing changed.",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("rescheduled", "cancelled", "new departure time", "schedule change", "delayed"), "yes", "no"),
            examples = listOf(
                ex("Schedule change: flight SP 1203 on 14 Oct now departs 10:40 (was 08:05).", "yes", "A new time."),
                ex("Online check-in is now open for SP 1203.", "no", "A reminder, no change."),
            ),
        ),
        Template(
            id = "entry-requirement",
            category = Category.TRAVEL,
            title = "Entry requirements",
            question = "Does this state an entry requirement for a trip, such as a visa or passport validity rule?",
            shape = Shape.YesNo,
            invariant = "A condition for entering a country: visa, passport validity, travel authorisation, vaccination.",
            breaks = "General travel advice with no requirement, or a requirement for a country not being visited.",
            lookalikes = "Airline marketing about 'travel essentials' is advice, not a rule. The expiry radar does the " +
                "passport arithmetic once the rule is known.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("visa", "passport must be valid", "entry requirement", "ETIAS", "ESTA", "travel authorisation"), "yes", "no"),
            examples = listOf(
                ex("Your passport must be valid for at least 3 months after the date you plan to leave the Schengen area.", "yes", "A validity rule."),
                ex("Packing tips for your autumn city break.", "no", "Advice."),
            ),
        ),
    )

    // ---------------------------------------------------------------- Safety & fraud

    private val safety = listOf(
        Template(
            id = "phishing",
            category = Category.SAFETY,
            title = "Phishing",
            question = "Is this message trying to get me to log in, pay or share details under false pretences?",
            shape = Shape.YesNo,
            invariant = "It pushes the recipient to sign in, pay, or hand over details, and something about who it claims " +
                "to be does not hold: the sender, the link, the reason.",
            breaks = "A genuine request from a known service through its real address, or a message that asks for nothing.",
            lookalikes = "Real security alerts also say 'sign in' — the difference is in the origin, which the site-fraud " +
                "watcher checks mechanically and which page content can never clear. The model can only add suspicion.",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("verify your account", "confirm your identity", "account suspended", "unusual activity", "update your payment"), "yes", "no"),
            examples = listOf(
                ex("From: PayPal <service@paypa1-secure.example>\nYour account is limited. Verify your identity here: http://paypal.account-verify.example/login", "yes", "A lookalike sender and a foreign link."),
                ex("From: Mum <mum@family.example>\nSunday lunch at 1?", "no", "Asks for nothing."),
            ),
            warnOnly = true,
            desktopNote = WARN_NOTE,
        ),
        Template(
            id = "impostor-sender",
            category = Category.SAFETY,
            title = "Someone pretending to be someone",
            question = "Does this message claim to come from someone it may not really be from?",
            shape = Shape.YesNo,
            invariant = "The name or story says one person or company; the address, the tone or the request says another.",
            breaks = "The message is consistent with its sender's history and asks for nothing unusual.",
            lookalikes = "A friend writing from a new address is legitimate and looks identical at first. The " +
                "impersonation watcher checks the address against history mechanically; this judgment reads the text.",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("new number", "new phone", "gift card", "urgent favour", "can't talk right now"), "yes", "no"),
            examples = listOf(
                ex("From: Mum <mum.family@quickmail.example>\nHi love, new phone. Can you buy me two gift cards? Can't talk now.", "yes", "A classic 'new number' pattern."),
                ex("From: Mum <mum@family.example>\nSee you Sunday.", "no", "Consistent with history."),
            ),
            warnOnly = true,
            desktopNote = WARN_NOTE,
        ),
        Template(
            id = "pressure-tactics",
            category = Category.SAFETY,
            title = "Pressure to act now",
            question = "Does this pressure me to act immediately or face a penalty?",
            shape = Shape.YesNo,
            invariant = "It threatens a loss — suspension, a fine, an arrest, a missed prize — unless the recipient acts at once.",
            breaks = "A real deadline stated calmly, with time to verify it independently.",
            lookalikes = "Genuine final notices exist and are also firm; they give dates and reference numbers you can check.",
            onFailure = LOUD,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("immediately", "within 24 hours", "act now", "will be suspended", "final warning", "arrest"), "yes", "no"),
            examples = listOf(
                ex("Your account will be suspended within 24 hours unless you verify now.", "yes", "A threat with no time to check."),
                ex("Reminder: your invoice is due on 30 September. Reference INV-2207.", "no", "A calm, checkable deadline."),
            ),
            warnOnly = true,
            desktopNote = WARN_NOTE,
        ),
        Template(
            id = "asks-for-secrets",
            category = Category.SAFETY,
            title = "Requests for passwords and codes",
            question = "Does this ask me for a password, one-time code or card number?",
            shape = Shape.YesNo,
            invariant = "It asks the recipient to send, type or read out a password, a one-time code, a PIN, a card number " +
                "or a CVV.",
            breaks = "It only tells you a code (a login code you requested), or asks you to reset a password yourself on the real site.",
            lookalikes = "'Here is your code: 481 552' is delivery, not a request. 'Reply with the code we sent you' is a request, " +
                "and no genuine service asks it. Loupe itself never fills any of these (§4).",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("send us the code", "reply with the code", "your password", "card number", "CVV", "PIN"), "yes", "no"),
            examples = listOf(
                ex("To confirm, reply with the 6-digit code we just texted you and your card's CVV.", "yes", "Asks for a code and a CVV."),
                ex("Your sign-in code is 481552. Don't share it with anyone.", "no", "Delivers a code you asked for."),
            ),
            warnOnly = true,
            desktopNote = WARN_NOTE,
        ),
        Template(
            id = "claims-brand",
            category = Category.SAFETY,
            title = "Messages claiming to be {brand}",
            question = "Does this message present itself as coming from {brand}?",
            shape = Shape.YesNo,
            invariant = "The name, logo text, signature or story says {brand}. Whether its origin matches is then checked " +
                "mechanically — sender domain and links against {brand}'s — never by the model.",
            breaks = "{brand} is only mentioned, e.g. 'paid with {brand}' on a shop's receipt.",
            lookalikes = "A shop receipt that says 'paid with {brand}' mentions {brand} without claiming to be it.",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = SenderIs("{brand}", "yes", "no"),
            parameters = listOf(Parameter("brand", ParamKind.TEXT, "Brand or bank name", "PayPal")),
            examples = listOf(
                ex("From: PayPal Security <service@paypa1-secure.example>\nDear customer, your PayPal account is limited.", "yes", "Presents itself as the brand."),
                ex("From: Homeware Direct <orders@homeware.example>\nPaid with PayPal: £39.99.", "no", "Only mentions it."),
            ),
            warnOnly = true,
            desktopNote = WARN_NOTE,
        ),
        Template(
            id = "too-good-to-be-true",
            category = Category.SAFETY,
            title = "Prizes and windfalls",
            question = "Does this offer a prize, refund or windfall I did not ask for?",
            shape = Shape.YesNo,
            invariant = "Unexpected money or goods are offered, usually with a step to claim them.",
            breaks = "A refund or reward the recipient did request or earn, with a traceable reference.",
            lookalikes = "Loyalty points statements and genuine tax refunds exist; a real one does not ask for card details to pay you.",
            onFailure = LOUD,
            sources = setOf(EMAIL),
            baseline = Keyword(listOf("you have won", "congratulations", "claim your", "prize", "unclaimed refund"), "yes", "no"),
            examples = listOf(
                ex("Congratulations! You have won a £500 voucher. Claim your prize by entering your card details.", "yes", "An unrequested windfall."),
                ex("We've refunded £24.99 for order 55-1021.", "no", "A traceable refund."),
            ),
            warnOnly = true,
            desktopNote = WARN_NOTE,
        ),
    )

    // ---------------------------------------------------------------- Personal

    private val personal = listOf(
        Template(
            id = "health-record",
            category = Category.PERSONAL,
            title = "Health records",
            question = "Is this a medical record, prescription or appointment?",
            shape = Shape.YesNo,
            invariant = "About the recipient's or their family's health care: results, letters from clinicians, " +
                "prescriptions, bookings with a practice or hospital.",
            breaks = "Health marketing, insurance adverts, fitness-app summaries.",
            lookalikes = "A gym membership receipt is not a health record. A pharmacy receipt for a prescription is borderline — " +
                "the queue will ask.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("appointment", "prescription", "clinic", "GP", "hospital", "test results"), "yes", "no"),
            examples = listOf(
                ex("Your appointment with Dr Example at Riverside Surgery is on 2 October at 09:20.", "yes", "A clinic booking."),
                ex("Get fit this autumn: 20% off our protein bars.", "no", "Marketing."),
            ),
        ),
        Template(
            id = "about-person",
            category = Category.PERSONAL,
            title = "About {person}",
            question = "Is this about {person}?",
            shape = Shape.YesNo,
            invariant = "{person} is the subject: the item is to, from or mostly about them.",
            breaks = "{person} is only copied in, or shares a name with someone else mentioned.",
            lookalikes = "A group email that lists {person} among twenty recipients is not about them.",
            onFailure = OPEN,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Keyword(listOf("{person}"), "yes", "no"),
            parameters = listOf(Parameter("person", ParamKind.TEXT, "Name", "Sam")),
            examples = listOf(
                ex("Sam's school report, autumn term.", "yes", "Sam is the subject."),
                ex("Newsletter to parents of class 4B.", "no", "Not about one person."),
            ),
        ),
        Template(
            id = "sentimental",
            category = Category.PERSONAL,
            title = "Sentimental keepsakes",
            question = "Is this something a person would keep for sentimental reasons?",
            shape = Shape.YesNo,
            invariant = "Personal meaning outlasting any practical use: letters and cards from people, milestones, " +
                "children's work, family photos.",
            breaks = "Practical records, however important, and anything impersonal.",
            lookalikes = "A wedding invoice is practical; the wedding invitation may be sentimental. This is a genuinely " +
                "hard judgment (§6 calls it contested) — expect the queue to ask often.",
            onFailure = NULL_ACTION,
            sources = setOf(EMAIL, DOCUMENT, PHOTO),
            baseline = Constant("no"),
            examples = listOf(
                ex("Happy 10th birthday Sam! Love, Grandma x", "yes", "A card from a person."),
                ex("Boiler service certificate.", "no", "Practical."),
            ),
        ),
        Template(
            id = "home-admin",
            category = Category.PERSONAL,
            title = "Home admin",
            question = "Is this about running the home, such as rent, utilities, repairs or home insurance?",
            shape = Shape.YesNo,
            invariant = "The household's own obligations and services: tenancy or mortgage, energy, water, broadband, " +
                "council tax, repairs, home insurance.",
            breaks = "Shopping for the home (furniture adverts), or someone else's property.",
            lookalikes = "A furniture sale is marketing, not home admin. A letter about the building's shared repairs is admin.",
            onFailure = OPEN,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Keyword(listOf("rent", "tenancy", "mortgage", "electricity", "broadband", "council tax", "boiler", "home insurance"), "yes", "no"),
            examples = listOf(
                ex("Your electricity bill for August: £62.40, direct debit on 1 October.", "yes", "A household service."),
                ex("Sofa sale: up to 50% off this weekend.", "no", "Marketing."),
            ),
        ),
        Template(
            id = "remind-me",
            category = Category.PERSONAL,
            title = "Worth a reminder later",
            question = "Would I want to be reminded of this later?",
            shape = Shape.YesNo,
            invariant = "It carries a future date or a loose end that matters to the recipient and is easy to forget.",
            breaks = "Past events, things already done, or things with nothing to do.",
            lookalikes = "Every event email has a date; few need a reminder. §6 names this as a hard, contested judgment — " +
                "the calibration screen will show whether it is earning its keep on your data.",
            onFailure = OPEN,
            sources = setOf(EMAIL, DOCUMENT),
            baseline = Constant("no"),
            examples = listOf(
                ex("Your boiler service is due by March 2027.", "yes", "A future obligation, easy to forget."),
                ex("Thanks for attending yesterday's webinar.", "no", "Past."),
            ),
        ),
    )

    /**
     * The two options each yes/no template offers the model, positive first. Written so that each
     * option says what it means: with bare `yes`/`no`, Laya largely ignored the question on the
     * sample (see [Shape.Binary]). A warn-only template's negative option is what the model reads,
     * never what the user is shown — the app shows "no signal", because §4 forbids a blessing.
     */
    private val OPTIONS: Map<String, Pair<String, String>> = mapOf(
        "is-receipt" to ("a receipt or proof of purchase" to "not a receipt"),
        "tax-receipt" to ("a payment record for my tax return" to "not needed for tax"),
        "refund-issued" to ("a refund issued to me" to "no refund issued"),
        "bill-unpaid" to ("a bill still to pay" to "nothing left to pay"),
        "warranty-proof" to ("proof of purchase for a durable item" to "not warranty proof"),
        "is-expiring-document" to ("a document whose expiry matters" to "no expiry that matters"),
        "expires-before" to ("a document whose expiry matters" to "no expiry that matters"),
        "deadline" to ("a deadline I must meet" to "no deadline for me"),
        "signed-agreement" to ("a signed contract or agreement" to "not a signed agreement"),
        "official-notice" to ("an official notice from a public body" to "not an official notice"),
        "needs-reply" to ("waiting on my response" to "not waiting on me"),
        "reply-from-sender" to ("waiting on my response" to "not waiting on me"),
        "meeting-request" to ("a request to meet" to "not a meeting request"),
        "someone-will-follow-up" to ("a promise to get back to me" to "no promise to follow up"),
        "unsubscribe-candidate" to ("bulk mail I no longer read" to "mail worth keeping"),
        "subscription-charge" to ("a recurring subscription charge" to "not a subscription charge"),
        "trial-ending" to ("a free trial about to charge me" to "not a trial warning"),
        "price-rise" to ("a price rise for me" to "no price rise for me"),
        "auto-renewal" to ("an automatic renewal notice" to "not an automatic renewal"),
        "cancellation-confirmed" to ("a confirmed cancellation" to "not a cancellation"),
        "is-duplicate" to ("a redundant copy" to "not a redundant copy"),
        "is-stale" to ("no longer useful to keep" to "still useful to keep"),
        "is-junk" to ("worthless to keep" to "worth keeping"),
        "refetchable-download" to ("a download I could fetch again" to "personal or one-off"),
        "superseded-version" to ("an earlier, superseded version" to "a current or unique version"),
        "screenshot-worth-keeping" to ("a screenshot worth keeping" to "nothing worth keeping"),
        "photo-of-document" to ("a photo of a paper document" to "not a document photo"),
        "photo-has-sensitive-data" to ("shows a card number, ID number or password" to "no sensitive number shown"),
        "task-for-me" to ("a task assigned to me" to "no task for me"),
        "about-project" to ("about this project" to "not about this project"),
        "meeting-notes" to ("meeting notes or minutes" to "not meeting notes"),
        "expense-claimable" to ("a work expense I could claim" to "not a claimable expense"),
        "confidential" to ("confidential business information" to "nothing confidential"),
        "booking-confirmed" to ("a confirmed travel booking" to "not a confirmed booking"),
        "trip-changed" to ("a change to a trip I booked" to "no change to a trip"),
        "entry-requirement" to ("an entry requirement for a trip" to "no entry requirement"),
        "phishing" to ("a scam or phishing attempt" to "an ordinary message"),
        "impostor-sender" to ("someone pretending to be someone else" to "consistent with its sender"),
        "pressure-tactics" to ("pressure to act immediately" to "no pressure to act now"),
        "asks-for-secrets" to ("a request for a password, code or card number" to "no request for secrets"),
        "claims-brand" to ("claims to come from that brand" to "does not claim to be that brand"),
        "too-good-to-be-true" to ("an unrequested prize or windfall" to "no unrequested windfall"),
        "health-record" to ("a medical record, prescription or appointment" to "not a health record"),
        "about-person" to ("about this person" to "not about this person"),
        "sentimental" to ("a sentimental keepsake" to "not sentimental"),
        "home-admin" to ("about running the home" to "not home admin"),
        "remind-me" to ("worth a reminder later" to "no reminder needed"),
    )

    /** A yes/no template restated with descriptive options; baseline and examples follow. */
    private fun Template.described(positive: String, negative: String): Template {
        check(shape == Shape.YesNo) { "$id is not a yes/no template" }
        val labels = mapOf("yes" to positive, "no" to negative)
        return copy(
            shape = Shape.Binary(positive, negative),
            baseline = baseline?.relabel(labels),
            examples = examples.map { it.copy(answer = labels.getValue(it.answer)) },
        )
    }

    /** Every template, grouped in [Category] order. */
    val ALL: List<Template> =
        (money + documents + email + subscriptions + files + photos + work + travel + safety + personal)
            .map { t ->
                val options = OPTIONS[t.id]
                check((options != null) == (t.shape == Shape.YesNo)) { "${t.id}: every yes/no template needs descriptive options" }
                if (options == null) t else t.described(options.first, options.second)
            }
            .sortedBy { it.category.ordinal }

    /** Looks a template up by id. */
    fun byId(id: String): Template? = ALL.firstOrNull { it.id == id }

    /** Templates in [category], in library order. */
    fun inCategory(category: Category): List<Template> = ALL.filter { it.category == category }

    /**
     * Templates matching [query] in their title, question or criteria (case-insensitive), in
     * library order. An empty query matches everything.
     */
    fun search(query: String, category: Category? = null): List<Template> {
        val q = query.trim().lowercase()
        return ALL.filter { t ->
            (category == null || t.category == category) &&
                (q.isEmpty() || listOf(t.title, t.question, t.invariant, t.lookalikes, t.category.title).any { q in it.lowercase() })
        }
    }

    /** Posture used for a user's own judgment unless they pick another. */
    val DEFAULT_POSTURE: FailurePosture = NULL_ACTION
}
