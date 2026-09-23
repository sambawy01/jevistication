package dev.loupe.kit.privacy

import dev.loupe.engine.ContentHash
import dev.loupe.kit.watchers.SAMPLE_DIR
import dev.loupe.kit.watchers.sampleReaders
import dev.loupe.persistence.CorrectionKey
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem
import dev.loupe.sources.common.SourceRoot
import dev.loupe.sources.common.SourceScanner
import dev.loupe.sources.common.SourceType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The privacy check over the shipped sample and synthetic items, on the JVM and the iOS simulator. */
class PrivacyCheckTest {
    private val today = LocalDate(2026, 9, 23)

    private val sample: List<SourceItem> by lazy {
        SourceScanner(sampleReaders(), TimeZone.UTC).scan(
            listOf(
                SourceRoot("sample", SourceType.FOLDER, "$SAMPLE_DIR/documents", "sample:documents/"),
                SourceRoot("sample", SourceType.MAIL_EXPORT, "$SAMPLE_DIR/mail", "sample:mail/"),
            ),
        ).items
    }

    private fun item(id: String, text: String, bytes: String = text, kind: ItemKind = ItemKind.TEXT) = SourceItem(
        id = id, sourceId = "files", kind = kind, path = "/tmp/" + id.substringAfter(':'), messageIndex = null,
        name = id.substringAfterLast('/'), text = text, hasText = text.isNotEmpty(), textTruncated = false,
        sizeBytes = bytes.encodeToByteArray().size.toLong(), contentHash = ContentHash.of(bytes), mime = "text/plain",
        date = null, dateOrigin = null, email = null, facts = emptyMap(),
    )

    @Test
    fun theSampleRaisesItsIdDocumentsAndDuplicateReceipts() {
        val f = PrivacyCheck.findings(sample, setOf("sample"), today)
        val ids = f.filter { it.group == PrivacyGroup.ID_DOCUMENTS }.map { it.itemName }.toSet()
        assertEquals(setOf("passport-scan-SPECIMEN.txt", "driving-licence-SPECIMEN.txt"), ids)
        val passport = f.first { it.itemName == "passport-scan-SPECIMEN.txt" }
        assertEquals("id_document", passport.ruleId)
        assertEquals(3, passport.severity)
        assertEquals("documents/identity/passport-scan-SPECIMEN.txt", passport.location)
        val dup = f.single { it.group == PrivacyGroup.DUPLICATES }
        val g = assertNotNull(dup.duplicates)
        assertEquals(2, g.count)
        assertEquals(1, g.members.count { it.keep })
        assertTrue(g.members.any { it.name == "fresh-basket-2026-08-14 (copy).txt" && !it.keep })
        assertTrue(f.all { it.sample })
        assertEquals(1, dup.severity)
        assertTrue(f.zipWithNext().all { (a, b) -> a.severity >= b.severity })
    }

    /** The station's `test_duplicates_large_and_old`, over items: 3 copies, a same-size impostor, a small pair. */
    @Test
    fun duplicatesGroupBySizeThenContent() {
        val big = "x".repeat(2000) + "end"
        val items = listOf(
            item("files:a.bin", big), item("files:copy of a.bin", big), item("files:sub/a again.bin", big),
            item("files:same size.bin", big.dropLast(1) + "e"),
            item("files:small1.txt", "notes: same"), item("files:small2.txt", "notes: same"),
            item("files:empty1.txt", ""), item("files:empty2.txt", ""),
        )
        val groups = Duplicates.groups(items)
        assertEquals(2, groups.size)
        val g = groups.first { it.count == 3 }
        assertEquals(setOf("files:a.bin", "files:copy of a.bin", "files:sub/a again.bin"), g.members.map { it.itemId }.toSet())
        assertEquals(2L * big.length, g.wastedBytes)
        assertEquals(1, g.members.count { it.keep })
        assertEquals("files:a.bin", g.keepItemId)   // shortest path among equals
        assertTrue(groups.all { it.groupId.startsWith("dup-") && it.groupId.length == 16 })
        assertEquals(groups.map { it.groupId }, Duplicates.groups(items.reversed()).map { it.groupId })
    }

    /** `test_pii_signals_drive_findings_and_never_leak`: findings by type, and no raw value anywhere. */
    @Test
    fun signalsBecomeGroupedFindingsAndNeverLeak() {
        val nids = PrivacyRulesTest.NIDS
        val rows = (0 until 30).joinToString("\n") { i -> "${nids[i % 3]},cust$i@example.com" }
        val clients = item(
            "files:clients.csv",
            "national_id,email\n$rows\npassport no ${PrivacyRulesTest.PASSPORT_NO}\niban ${PrivacyRulesTest.IBAN}\ncard ${PrivacyRulesTest.CARD}\n",
        )
        val team = item("files:team.csv", "اسم الموظف,الراتب,خصومات\nAhmed,9000,100\n")
        val env = item("files:.env", "ANTHROPIC_API_KEY=${PrivacyRulesTest.ANTHROPIC}\nDB_PASSWORD=${PrivacyRulesTest.PASSWORD}\n")
        val contact = item("contacts:1", "Mum +20 1012345678 mum@example.com", kind = ItemKind.CONTACT)
        val f = PrivacyCheck.findings(listOf(clients, team, env, contact), emptySet(), today)
        val rules = f.filter { it.itemId == clients.id }.map { it.ruleId }.toSet()
        assertEquals(setOf("egypt_national_id", "passport_number", "iban", "card_number", "contact_list"), rules)
        assertEquals(PrivacyGroup.CARDS, f.first { it.ruleId == "card_number" }.group)
        assertEquals(PrivacyGroup.CONTACTS, f.first { it.ruleId == "contact_list" }.group)
        assertEquals(2, f.first { it.ruleId == "contact_list" }.severity)
        assertEquals("payroll_headers", f.single { it.itemId == team.id }.ruleId)
        assertEquals(setOf("env_file", "anthropic_key", "password_assignment"), f.filter { it.itemId == env.id }.map { it.ruleId }.toSet())
        assertTrue(f.none { it.itemId == contact.id }, "the address book is not a leak")
        assertEquals(PrivacyGroup.SECRETS, f.first().group)

        val everything = f.toString() + PrivacyCheck.summarise(listOf(clients, team, env), emptySet(), emptyMap(), today).toString()
        val raw = nids + listOf(
            PrivacyRulesTest.IBAN, PrivacyRulesTest.IBAN.substring(4, 20), PrivacyRulesTest.CARD, PrivacyRulesTest.CARD.take(12),
            PrivacyRulesTest.PASSPORT_NO, "cust7@example.com", "cust7@",
        ) + PrivacyRulesTest.ALL_SECRETS.flatMap { listOf(it, it.substring(6, minOf(24, it.length))) }
        for (v in raw) assertFalse(v in everything, v)
    }

    @Test
    fun markSafeIsRememberedAndUndone() {
        val all = PrivacyCheck.summarise(sample, setOf("sample"), emptyMap(), today)
        val passport = all.findings.first { it.itemName == "passport-scan-SPECIMEN.txt" }
        val record = PrivacyCheck.markSafe(passport, "2026-09-23T12:00:00Z")
        assertEquals("safe", record.label)
        val key = CorrectionKey(record.judgmentId, record.criteriaHash, record.itemId)
        val after = PrivacyCheck.summarise(sample, setOf("sample"), mapOf(key to "safe"), today)
        assertEquals(all.findings.size - 1, after.findings.size)
        assertEquals(1, after.markedSafe)
        assertEquals(null, PrivacyCheck.retraction(passport, "t").label)
        assertTrue(all.groups.first() == PrivacyGroup.ID_DOCUMENTS)
    }
}
