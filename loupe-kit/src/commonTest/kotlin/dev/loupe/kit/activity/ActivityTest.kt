package dev.loupe.kit.activity

import dev.loupe.engine.Distribution
import dev.loupe.engine.LedgerRow
import dev.loupe.engine.Probability
import dev.loupe.engine.ResolvedBy
import dev.loupe.kit.privacy.PrivacyCheck
import dev.loupe.kit.privacy.PrivacyItemListener
import dev.loupe.kit.settings.EngineSettings
import dev.loupe.persistence.JsonValue
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityTest {
    private class Clock(var t: Double = 1_000.0, var m: Double = 0.0) : ActivityClock {
        override fun unix(): Double = t
        override fun mono(): Double = m
        fun tick(s: Double) { t += s; m += s }
    }

    private fun registry(clock: Clock = Clock()) = ActivityRegistry(clock)

    private fun start(reg: ActivityRegistry, kind: String = "scan", view: String? = "scan", cancel: ActivityCancel? = null, total: Double? = null) =
        reg.start(kind, "act.title.scan", null, view, null, total, null, cancel, null)!!

    // ---------------------------------------------------------------- the contract's names

    @Test
    fun namesMatchStationAndThePhoneAddsItsOwn() {
        assertEquals(
            listOf("scan", "email_run", "watchers", "model_load", "ocr", "ollama_pull", "feeds", "llm_job", "calibration", "mail_history"),
            ActivityNames.STATION_KINDS,
        )
        assertEquals(listOf("judgments", "sort", "inbox", "flights", "game"), ActivityNames.MOBILE_KINDS)
        assertEquals(listOf("read", "decisions", "flagged", "to_you", "tokens"), ActivityNames.COUNTERS)
        assertEquals(listOf("accepted", "uncertain", "flagged", "skipped"), ActivityNames.GATES)
        assertEquals(listOf("laya", "rule", "baseline", "personal"), ActivityNames.SOURCES)
        assertEquals(listOf("english", "multilingual"), ActivityNames.MODELS)
        assertEquals(listOf("running", "done", "error", "cancelled"), ActivityNames.STATES)
        assertEquals(10, ActivityNames.HISTORY)
        assertEquals(12, ActivityNames.DECISIONS)
    }

    @Test
    fun loopStageIdsAreStationsInOrder() {
        assertEquals(listOf("fetch", "read", "q1", "q2", "q3", "rules", "evidence", "outcome", "review"), LiveRun.EMAIL.ids)
        assertEquals(listOf("walk", "read", "q1", "q2", "q3", "rules", "evidence", "outcome", "review"), LiveRun.SCAN.ids)
        assertEquals(listOf("items", "read", "q1", "q2", "q3", "rules", "evidence", "outcome", "review"), LiveRun.WATCHERS.ids)
        assertEquals(listOf("fetch", "read", "q1", "q2", "q3", "rules", "evidence", "outcome", "review"), LiveRun.HISTORY.ids)
        assertEquals(mapOf("accepted" to "outcome", "uncertain" to "review", "flagged" to "review", "skipped" to "rules"), LiveRun.FATE_END)
        assertEquals(LiveRun.SCAN, LiveRun.pipeFor("scan"))
        assertEquals(LiveRun.SCAN, LiveRun.pipeFor("inbox"))
        assertEquals(LiveRun.EMAIL, LiveRun.pipeFor("email_run"))
        assertEquals(LiveRun.WATCHERS, LiveRun.pipeFor("judgments"))
        assertNull(LiveRun.pipeFor("model_load"))
    }

    @Test
    fun jobCarriesEveryContractField() {
        val reg = registry()
        val job = start(reg)
        val o = job.snapshot().json()
        val keys = listOf(
            "id", "kind", "view", "ref", "title", "state", "stage", "progress", "rate", "eta_s", "expected_s", "started_at",
            "finished_at", "elapsed_s", "cancellable", "cancel_requested", "counters", "gates", "shares", "decisions", "seq",
            "model", "steps", "meta", "result",
        )
        assertEquals(keys, o.fields.keys.toList())
        val shares = o.fields["shares"] as JsonValue.Obj
        assertEquals(listOf("model", "source"), shares.fields.keys.toList())
        assertEquals("scan-1", job.id)
        val snap = reg.snapshot().json()
        assertEquals(listOf("running", "finished", "server_time", "reference"), snap.fields.keys.toList())
    }

    // ---------------------------------------------------------------- behaviour

    @Test
    fun countersGatesSharesAndTheDecisionLog() {
        val reg = registry()
        val job = start(reg)
        job.count("read", 3)
        job.gate("flagged", 2)
        job.gate("accepted", 1)
        job.gate("nonsense", 5)
        repeat(14) { job.decision("needs_reply", if (it % 2 == 0) "yes" else "no", 0.8123, "laya", "multilingual", 700) }
        job.decision("sensitive", "no", 1.0, "rule", null, 0)
        val s = job.snapshot()
        assertEquals(3, s.counter("read"))
        assertEquals(2, s.counter("flagged"), "a flagged gate counts as flagged")
        assertEquals(15, s.counter("decisions"))
        assertEquals(14 * 700, s.counter("tokens"))
        assertEquals(12, s.decisions.size, "the log keeps the last 12")
        assertEquals(15, s.seq)
        assertEquals(0.812, s.decisions.first().c)
        assertEquals(14, s.sharesModel["multilingual"])
        assertEquals(14, s.sharesSource["laya"])
        assertEquals(1, s.sharesSource["rule"])
        assertEquals("multilingual", s.model)
        assertEquals(0, s.gate("skipped"))
    }

    @Test
    fun rateAndEtaOverTheLast15Seconds() {
        val clock = Clock()
        val reg = registry(clock)
        val job = start(reg, total = 100.0)
        job.progress(0.0, null)
        clock.tick(2.0); job.progress(10.0, null)
        clock.tick(2.0); job.progress(20.0, null)
        val s = job.snapshot()
        assertEquals(5.0, s.rate)
        assertEquals(16.0, s.etaS)
        assertEquals(0.2, s.fraction)
        clock.tick(20.0)
        assertNull(job.snapshot().rate, "old samples fall out of the window")
    }

    @Test
    fun finishKeepsTheLastTenAndCancelAnswersLikeStation() {
        val reg = registry()
        var cancelled = 0
        val c = start(reg, cancel = { cancelled++ })
        assertEquals(202, reg.cancel(c.id))
        assertEquals(1, cancelled)
        assertTrue(c.snapshot().cancelRequested)
        assertEquals(404, reg.cancel("nope"))
        val plain = start(reg)
        assertEquals(409, reg.cancel(plain.id), "not cancellable")
        c.finish("cancelled", "act.res.stopped", null)
        assertEquals(409, reg.cancel(c.id), "finished")
        repeat(12) { start(reg).finish("done", "act.res.scan", mapOf("files" to it, "flagged" to 0)) }
        val snap = reg.snapshot()
        assertEquals(10, snap.finished.size)
        assertEquals(1, snap.running.size)
        assertEquals("done", snap.finished.first().state)
        assertEquals("11", snap.finished.first().result?.param("files"))
        assertNull(reg.start("mystery", "act.title.scan", null, null, null, null, null, null, null), "unknown kind refused")
    }

    @Test
    fun doneFillsTheProgressAndLatestPrefersRunning() {
        val reg = registry()
        val a = start(reg, total = 10.0)
        a.progress(4.0, null)
        a.finish("done", null, null)
        assertEquals(10.0, reg.snapshot().finished.first().progress?.done)
        val b = start(reg)
        assertEquals(b.id, reg.snapshot().latest("scan")?.id)
        b.finish("error", "act.res.failed", null)
        assertEquals(b.id, reg.snapshot().latest("scan")?.id)
        assertNull(reg.snapshot().latest("email"))
    }

    @Test
    fun stagesNodesDiamondsAndParticles() {
        val reg = registry()
        val job = start(reg, kind = "email_run", view = "email")
        job.stage("act.stage.fetching", null)
        assertEquals("fetch", LiveRun.activeNode(job.snapshot()))
        job.stage("act.stage.classifying", null)
        assertEquals("read", LiveRun.activeNode(job.snapshot()))
        assertEquals(listOf("category", "needs_reply", "urgency"), LiveRun.diamondQuestions(job.snapshot()))
        val before = job.snapshot()
        job.decision("is_phishing", "no", 1.0, "rule", null, 0)
        job.count("read", 20)
        job.gate("accepted", 9)
        job.gate("flagged", 1)
        val after = job.snapshot()
        assertEquals(listOf("category", "needs_reply", "is_phishing"), LiveRun.diamondQuestions(after))
        assertEquals(listOf("is_phishing"), LiveRun.freshQuestions(before, after))
        val spawns = LiveRun.spawns(before, after)
        assertEquals(6, spawns.count { it.fate == "accepted" }, "batched at 6 a gate")
        assertEquals(1, spawns.count { it.fate == "flagged" })
        assertEquals("review", spawns.first { it.fate == "flagged" }.end)
        assertEquals(4, spawns.count { it.fate == "reading" })
        assertEquals(2, LiveRun.batchSize(before, after, "accepted"))
        assertTrue(LiveRun.spawns(after, after).isEmpty(), "nothing moved, nothing moves")
        job.finish("done", null, null)
        assertNull(LiveRun.activeNode(job.snapshot()))
    }

    @Test
    fun theStatusLineSpeaksAtMostEvery12sOrOnAStageChange() {
        val reg = registry()
        val job = start(reg)
        val a = LiveAnnouncer()
        assertNotNull(a.offer(0, job.snapshot(), "x"))
        assertNull(a.offer(5_000, job.snapshot(), "x"))
        assertNotNull(a.offer(12_000, job.snapshot(), "x"))
        job.stage("act.stage.hashing", null)
        assertNotNull(a.offer(12_100, job.snapshot(), "x"), "a stage change speaks at once")
        assertNull(a.offer(12_200, job.snapshot(), "x"))
        job.finish("done", null, null)
        assertNotNull(a.offer(12_300, job.snapshot(), "x"), "the end speaks at once")
    }

    @Test
    fun costOfAskingUsesTheContractFormulaAndDefaults() {
        val r = CostReference.DEFAULT
        assertEquals("Claude Sonnet 5", r.model)
        assertEquals(2.0, r.inputPerMtok)
        assertEquals(10.0, r.outputPerMtok)
        assertEquals(700, r.tokensIn)
        assertEquals(60, r.tokensOut)
        assertEquals("2026-09-24", r.checked)
        assertEquals("https://docs.claude.com/en/docs/about-claude/pricing", r.source)
        assertEquals(0.002, r.perDecision)
        assertEquals(2.0, r.cost(1000), 1e-9)
        assertFalse(r.custom)
        val mine = r.withPrices(3.0, 15.0, 700, 60)
        assertTrue(mine.custom)
        assertEquals(3.0, mine.cost(1000), 1e-9)
        assertEquals(listOf("cost_input_per_mtok", "cost_output_per_mtok", "cost_tokens_in", "cost_tokens_out"), CostReference.SETTING_KEYS)
        for (k in CostReference.SETTING_KEYS) assertNotNull(EngineSettings.spec("global.$k"), k)
        assertEquals(r, EngineSettings.DEFAULTS.costReference)
        val json = r.json()
        assertEquals(listOf("model", "input_per_mtok", "output_per_mtok", "tokens_in", "tokens_out", "source", "checked", "custom", "per_decision"), json.fields.keys.toList())
    }

    @Test
    fun ledgerRowsBecomeDecisionsWithOpaqueQuestionIds() {
        val reg = registry()
        val job = start(reg, kind = "judgments", view = "judgments")
        fun row(p: Double, by: ResolvedBy = ResolvedBy.Model, failure: String? = null) = LedgerRow(
            judgmentId = "j-is-this-a-receipt-from-my-landlord", criteriaHash = "h",
            distribution = Distribution.of(mapOf("yes" to p, "no" to 1 - p)), action = "yes",
            propensity = Probability.of(p), itemId = "sample/rent-march.pdf", resolvedBy = by, failure = failure,
        )
        ActivityReport.recordRows(
            job,
            listOf(row(0.9), row(0.55), row(1.0, ResolvedBy.Mechanical("exact-duplicate")), row(0.5, ResolvedBy.Unusable, "bad json")),
            0.7, "multilingual",
        )
        val s = job.snapshot()
        assertEquals(4, s.counter("read"))
        assertEquals(2, s.gate("accepted"))
        assertEquals(1, s.gate("uncertain"))
        assertEquals(1, s.gate("skipped"))
        assertEquals(1, s.counter("to_you"))
        assertEquals(2, s.sharesSource["laya"])
        assertEquals(1, s.sharesSource["rule"])
        val q = s.decisions.first().q!!
        assertTrue(q.startsWith("j:") && q.length == 10, q)
        assertEquals(Activity.questionId("j-is-this-a-receipt-from-my-landlord"), q)
        val text = s.jsonText()
        assertFalse("receipt" in text || "landlord" in text || "rent-march" in text, text)
    }

    // ---------------------------------------------------------------- privacy: no text, names or paths

    @Test
    fun cleanKeepsOnlyNumbersBooleansAndShortIdentifiers() {
        val c = Activity.clean(
            mapOf(
                "files" to 12, "ratio" to 0.123456, "ok" to true, "none" to null, "model" to "qwen2.5:7b", "watcher" to "watcher:expiry",
                "subject" to "Your invoice for March is ready", "path" to "/var/mobile/Documents/tax.pdf", "file" to "passport-scan.pdf",
                "sender" to "anna@example.com", "quote" to "\"hi\"", "Bad Key" to 1, "long" to "x".repeat(41), "photo" to "IMG_2041.HEIC",
            ),
        )
        assertEquals(setOf("files", "ratio", "ok", "none", "model", "watcher"), c.keys)
        assertEquals(12.0, c["files"])
        assertEquals(0.1235, c["ratio"])
    }

    @Test
    fun noTextOrFileNamesReachAJobThroughAnyDoor() {
        val secrets = listOf(
            "Your invoice for March is ready", "passport-scan.pdf", "/var/mobile/Containers/Data/tax-2025.pdf", "anna@example.com",
            "IMG_2041.HEIC", "Re: dinner on Friday?", "file:///private/var/x.txt", "4111 1111 1111 1111",
        )
        val reg = registry()
        for (s in secrets) {
            val job = reg.start("scan", "act.title.scan", mapOf("name" to s), "scan", s, 10.0, "act.stage.walking", null, null)!!
            job.stage("act.stage.watcher", mapOf("watcher" to s))
            job.stage(s, null)
            job.decision(s, s, 0.5, s, s, 0)
            job.step(s, s, "running", 1.0, 2.0, s, mapOf("x" to s), mapOf("y" to s))
            job.step("fetch", "act.step.fetch", "running", 1.0, 2.0, "act.res.list", mapOf("entries" to s), mapOf("note" to s))
            job.setMeta(mapOf("current" to s, "files_seen" to 3))
            job.finish("done", "act.res.scan", mapOf("files" to 3, "flagged" to s))
        }
        val text = reg.snapshot().jsonText()
        for (s in secrets) assertFalse(s in text, "leaked: $s")
        for (part in listOf("invoice", "passport", "tax-2025", "anna", "IMG_2041", "dinner", "4111")) assertFalse(part in text, "leaked: $part")
    }

    @Test
    fun aRealPrivacyCheckReportsCountsOnly() {
        fun item(id: String, name: String, text: String, kind: ItemKind = ItemKind.TEXT) = SourceItem(
            id = id, sourceId = "files", kind = kind, path = "/var/mobile/Documents/private/$name", messageIndex = null, name = name,
            text = text, hasText = true, textTruncated = false, sizeBytes = text.length.toLong(), contentHash = "h-$id",
            mime = "text/plain", date = null, dateOrigin = null, email = null, facts = emptyMap(), duplicateOf = null,
        )
        val items = listOf(
            item("a", "passport-scan.txt", "Passport holder Anna Karlsson, card 4111 1111 1111 1111, anna.karlsson@example.com"),
            item("b", "shopping-list.txt", "milk, eggs, bread"),
            item("c", "aws-keys.env", "AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"),
            item("d", "Anna Karlsson.vcf", "Anna Karlsson +46 70 123 45 67", ItemKind.CONTACT),
        )
        val reg = registry()
        val job = reg.start("scan", "act.title.scan", null, "scan", null, items.size.toDouble(), "act.stage.deciding", null, null)!!
        var dupes = false
        val summary = PrivacyCheck.summariseWatching(items, emptySet(), emptyMap(), true, object : PrivacyItemListener {
            override fun onItem(done: Int, total: Int, findings: Int, skipped: Boolean) {
                ActivityReport.recordScanned(job, findings, skipped, true)
                job.progress(done.toDouble(), total.toDouble())
            }
            override fun onDuplicates() { dupes = true; job.stage("act.stage.hashing", null) }
        })
        job.finish("done", "act.res.scan", mapOf("files" to summary.itemsChecked, "flagged" to summary.findings.size))
        assertTrue(dupes)
        val s = reg.snapshot().finished.first()
        assertEquals(3, s.counter("read"))
        assertEquals(1, s.gate("skipped"))
        assertTrue(s.gate("flagged") >= 2, "the passport text and the key file")
        assertEquals(1, s.gate("accepted"))
        assertEquals(3, s.sharesSource["rule"])
        val text = reg.snapshot().jsonText()
        for (leak in listOf("passport", "Anna", "Karlsson", "4111", "example.com", "shopping", "milk", "aws-keys", "wJalr", "/var/mobile", ".vcf", "+46")) {
            assertFalse(leak in text, "leaked: $leak")
        }
    }
}
