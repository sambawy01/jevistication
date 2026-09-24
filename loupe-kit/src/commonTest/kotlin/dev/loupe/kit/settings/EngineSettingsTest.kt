package dev.loupe.kit.settings

import dev.loupe.kit.watchers.TEST_TMP
import dev.loupe.persistence.JsonValue
import dev.loupe.persistence.PlatformFiles
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Model settings schema v1: defaults, clamping, reset, persistence and Station's JSON shape. */
class EngineSettingsTest {
    private fun dir() = "$TEST_TMP/settings-" + Random.nextLong().toULong().toString(16)

    /** Station's schema v1 `settings` object with every default (the relayed schema, key for key). */
    private val stationDefaults = """
        {"global":{"routing":"auto","memory_mode":"balanced","idle_unload_min":10,"accept_confidence":null,
          "use_calibration":true,"rules_first":true,"baseline_switch":true,"text_chars_english":1400,
          "text_chars_multilingual":2400},
         "features":{
          "scan":{"use_laya":true,"routing":null,"text_chars":null,"read_content":true,"content_budget_s":60},
          "email":{"use_laya":true,"routing":null,"text_chars":null},
          "browser":{"use_laya":true,"routing":null,"text_chars":null,"time_limit_s":5,"queue_size":2},
          "watchers":{"use_laya":true,"routing":null,"text_chars":null},
          "playground":{"use_laya":true,"routing":null,"text_chars":null}}}
    """.trimIndent()

    @Test
    fun documentHasStationShapeWithMobileFeaturesAfter() {
        val doc = EngineSettings.DEFAULTS.documentJson()
        assertEquals(JsonValue.Num("1"), doc["version"])
        val settings = doc["settings"] as JsonValue.Obj
        assertEquals(listOf("global", "features"), settings.fields.keys.toList())
        val features = settings["features"] as JsonValue.Obj
        assertEquals(Features.ALL, features.fields.keys.toList())

        val station = JsonValue.parse(stationDefaults) as JsonValue.Obj
        // Global: same keys, same order, same values.
        assertEquals(station["global"], settings["global"])
        // Station's features: same keys in the same order, same defaults.
        for (id in Features.STATION) assertEquals(station["features"]!!.asObj[id], features[id], id)
        // The phone's features: the common keys, and the game's one extra.
        for (id in Features.MOBILE) {
            val keys = (features[id] as JsonValue.Obj).fields.keys.toList()
            val expected = listOf("use_laya", "routing", "text_chars") + if (id == Features.GAME) listOf("max_decisions_per_s") else emptyList()
            assertEquals(expected, keys, id)
        }
        // Whole numbers are written without a fraction, as Station writes them.
        assertTrue("\"idle_unload_min\": 10," in EngineSettingsStore(null).document())
    }

    @Test
    fun defaultsReproduceTheOldBehaviour() {
        val d = EngineSettings.DEFAULTS
        assertEquals("auto", d.routing)
        assertEquals("balanced", d.memoryMode)
        assertEquals(10.0, d.idleUnloadMin)
        assertNull(d.acceptConfidence)
        assertTrue(d.useCalibration && d.rulesFirst && d.baselineSwitch)
        assertNull(d.gameMaxDecisionsPerS)
        for (f in Features.ALL) {
            val p = d.policy(f)
            assertTrue(p.useLaya, f)
            assertNull(p.textChars, f)
            assertEquals("multilingual", p.routing, "the phone runs its one checkpoint")
            assertEquals(4_000, p.budget(4_000))
            assertEquals(0.8, p.threshold(0.8))
        }
        assertEquals(emptyList(), d.changed)
    }

    @Test
    fun clampsNumbersAndRefusesWrongTypes() {
        val s = EngineSettingsStore(null)
        s.setNumber("global.idle_unload_min", 5_000.0)
        assertEquals(1_440.0, s.current.idleUnloadMin)
        s.setNumber("global.idle_unload_min", -3.0)
        assertEquals(0.0, s.current.idleUnloadMin)
        s.setNumber("global.accept_confidence", 0.01)
        assertEquals(0.05, s.current.acceptConfidence)
        s.setNumber("global.accept_confidence", 1.5)
        assertEquals(0.99, s.current.acceptConfidence)
        s.setNumber("global.text_chars_multilingual", 150.7)
        assertEquals(200, s.current.textCharsMultilingual)
        s.setNumber("features.browser.queue_size", 3.6)
        assertEquals(4, s.current.browserQueueSize)
        s.setTextChars("features.judgments.text_chars", EngineSettings.TEXT_CUSTOM, 50)
        assertEquals(100, s.current.textChars(Features.JUDGMENTS))
        s.setNumber("features.game.max_decisions_per_s", 0.1)
        assertEquals(0.5, s.current.gameMaxDecisionsPerS)

        val bad = s.setString("global.routing", "fast")
        assertFalse(bad.ok)
        assertEquals("auto", s.current.routing)
        val wrongType = s.set("global.rules_first", JsonValue.Str("yes"))
        assertFalse(wrongType.ok)
        assertTrue(s.current.rulesFirst)
        assertFalse(s.set("global.nope", JsonValue.Bool(true)).ok)
        // "english" is a valid value (parity), though the phone runs multilingual whatever it says.
        assertTrue(s.setString("global.routing", "english").ok)
        assertEquals("multilingual", s.current.policy(Features.JUDGMENTS).routing)
        assertEquals("english", s.current.policy(Features.JUDGMENTS).requestedRouting)
    }

    @Test
    fun textCharsGlobalReadsTheMultilingualLimit() {
        val s = EngineSettingsStore(null)
        s.setTextChars("features.watchers.text_chars", EngineSettings.TEXT_GLOBAL, 0)
        assertEquals(2_400, s.current.textChars(Features.WATCHERS))
        s.setNumber("global.text_chars_multilingual", 3_000.0)
        assertEquals(3_000, s.current.policy(Features.WATCHERS).budget(4_000))
        s.setNumber("global.text_chars_english", 900.0)
        assertEquals(3_000, s.current.policy(Features.WATCHERS).budget(4_000), "English is unused on the phone")
    }

    @Test
    fun acceptConfidenceYieldsToAThresholdSetOnMeasure() {
        val p = EngineSettings.DEFAULTS.policy(Features.JUDGMENTS).copy(acceptConfidence = 0.9)
        assertEquals(0.9, p.threshold(0.8))
        assertEquals(0.7, p.threshold(0.8, override = 0.7))
    }

    @Test
    fun resetOneKeyAndResetAll() {
        val s = EngineSettingsStore(null)
        s.setBool("features.scan.use_laya", false)
        s.setString("global.memory_mode", "low")
        s.setNumber("global.accept_confidence", 0.7)
        assertEquals(setOf("global.memory_mode", "global.accept_confidence", "features.scan.use_laya"), s.current.changed.toSet())
        val one = s.reset("features.scan.use_laya")
        assertEquals(listOf("features.scan.use_laya"), one.changed)
        assertTrue(s.current.useLaya(Features.SCAN))
        val all = s.resetAll()
        assertEquals(listOf("global.memory_mode"), all.reload)
        assertEquals(EngineSettings.DEFAULTS, s.current)
        assertEquals(emptyList(), s.resetAll().changed, "nothing left to reset")
    }

    @Test
    fun stationPutAndGetBodies() {
        val s = EngineSettingsStore(null)
        val c = s.put("""{"global":{"idle_unload_min":30,"routing":"multilingual"},"features":{"scan":{"use_laya":false,"content_budget_s":900}}}""")
        assertTrue(c.ok, c.errors.toString())
        assertEquals(setOf("global.routing", "global.idle_unload_min", "features.scan.use_laya", "features.scan.content_budget_s"), c.changed.toSet())
        assertEquals(listOf("global.idle_unload_min"), c.reload)
        assertEquals(600.0, s.current.contentBudgetS)
        val r = s.put("""{"reset":["global.routing","features.scan.use_laya"]}""")
        assertEquals(setOf("global.routing", "features.scan.use_laya"), r.changed.toSet())
        assertEquals(30.0, s.current.idleUnloadMin)
        s.put("""{"reset_all":true}""")
        assertEquals(EngineSettings.DEFAULTS, s.current)

        val get = JsonValue.parse(s.get()) as JsonValue.Obj
        assertEquals(listOf("version", "settings", "defaults", "env", "reload"), get.fields.keys.toList())
        assertEquals(JsonValue.obj(), get["env"], "no environment or MDM on iPhone")
        assertEquals(JsonValue.strings(listOf("global.memory_mode", "global.idle_unload_min")), get["reload"])
        assertEquals(get["settings"], get["defaults"])
    }

    @Test
    fun persistsAndReadsBack() {
        val dir = dir()
        val a = EngineSettingsStore(dir)
        a.setBool("features.flights.use_laya", false)
        a.setTextChars("features.judgments.text_chars", EngineSettings.TEXT_CUSTOM, 1_234)
        a.setNumber("global.accept_confidence", 0.65)
        val b = EngineSettingsStore(dir)
        assertEquals(a.current, b.current)
        assertFalse(b.current.useLaya(Features.FLIGHTS))
        assertEquals(1_234, b.current.textChars(Features.JUDGMENTS))
        val text = PlatformFiles.readText("$dir/engine_settings.json")
        assertNotNull(text)
        val doc = JsonValue.parse(text) as JsonValue.Obj
        assertEquals(1, doc["version"]!!.asInt)
        assertEquals(JsonValue.Bool(false), doc["settings"]!!.asObj["features"]!!.asObj["flights"]!!.asObj["use_laya"])
        b.resetAll()
        assertEquals(EngineSettings.DEFAULTS, EngineSettingsStore(dir).current)
    }

    @Test
    fun aBadFileFallsBackKeyByKey() {
        val dir = dir()
        PlatformFiles.createDirectories(dir)
        PlatformFiles.writeAtomically("$dir/engine_settings.json", "{not json")
        val broken = EngineSettingsStore(dir)
        assertEquals(EngineSettings.DEFAULTS, broken.current)
        assertNotNull(broken.problem)

        PlatformFiles.writeAtomically(
            "$dir/engine_settings.json",
            """{"version":1,"settings":{"global":{"memory_mode":"huge","idle_unload_min":99999,"rules_first":false},
               "features":{"email":{"use_laya":"no"},"future":{"x":1},"judgments":{"text_chars":"global"}}}}""",
        )
        val partial = EngineSettingsStore(dir).current
        assertEquals("balanced", partial.memoryMode, "an unknown choice keeps the default")
        assertEquals(1_440.0, partial.idleUnloadMin, "out of range is clamped")
        assertFalse(partial.rulesFirst)
        assertTrue(partial.useLaya(Features.EMAIL), "a wrong type keeps the default")
        assertEquals(EngineSettings.TEXT_GLOBAL, partial.textCharsMode(Features.JUDGMENTS))
    }

    @Test
    fun listenersHearOnlyRealChanges() {
        val s = EngineSettingsStore(null)
        val heard = mutableListOf<List<String>>()
        s.onChange { _, c -> heard += c.changed }
        s.setBool("features.game.use_laya", true)   // already true
        s.setBool("features.game.use_laya", false)
        assertEquals(listOf(listOf("features.game.use_laya")), heard)
    }

    @Test
    fun everySpecIsValidAtItsDefault() {
        for (spec in EngineSettings.SPECS) {
            assertEquals(spec.default, EngineSettings.validate(spec, spec.default), spec.key)
        }
        assertEquals(EngineSettings.SPECS.size, EngineSettings.SPECS.map { it.key }.toSet().size)
    }
}
