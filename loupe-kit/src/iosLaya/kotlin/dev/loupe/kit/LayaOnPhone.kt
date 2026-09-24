package dev.loupe.kit

import dev.loupe.backend.onnx.ios.LayaModelStore
import dev.loupe.backend.onnx.ios.LayaOnDevice
import dev.loupe.engine.Backend
import dev.loupe.engine.Judgment
import dev.loupe.engine.TextState

/**
 * Swift's door to Laya on the phone. Kotlin exceptions must not cross into Swift (an uncaught one
 * aborts the process), so everything here returns a value that says what went wrong instead.
 *
 * No network: this only finds, verifies and opens files already on the device. Getting them there
 * is the app's consented download (or `ios-native/sideload-models.sh` in development).
 */
object LayaOnPhone {
    /** Where the files are expected — `Library/Application Support/Loupe/laya-multilingual` — or null. */
    fun directory(): String? = runCatching { LayaModelStore.applicationSupport().directory }.getOrNull()

    /** File names the store expects, in a fixed order. */
    val fileNames: List<String> get() = LayaModelStore.FILES.keys.toList()

    /** The pinned SHA-256 of [name], or null if it is not one of [fileNames]. */
    fun pinnedSha256(name: String): String? = LayaModelStore.FILES[name]

    /** Expected files absent from [directory]. */
    fun missing(directory: String): List<String> = LayaModelStore(directory).missing()

    /** Lower-case hex SHA-256 of a file, or null when it cannot be read. */
    fun sha256(path: String): String? = runCatching { LayaModelStore.sha256(path) }.getOrNull()

    /** Verifies every file against its pin and opens Laya; the caller closes a [Opened.Ready]. */
    fun open(directory: String): Opened =
        runCatching { LayaModelStore(directory).open() }
            .fold({ Opened.Ready(it) }, { Opened.Failed(it.message ?: it::class.simpleName ?: "could not open Laya") })

    /** Graph variants the store knows (`int8` default, `int8-partial` opt-in). */
    val variants: List<String> get() = LayaModelStore.VARIANTS.keys.toList()

    /** File names for [variant] (tokenizer first), or empty for an unknown variant. */
    fun fileNames(variant: String): List<String> =
        runCatching { LayaModelStore.filesFor(variant).keys.toList() }.getOrDefault(emptyList())

    /** The pinned SHA-256 of [name] under [variant], or null. */
    fun pinnedSha256(variant: String, name: String): String? =
        runCatching { LayaModelStore.filesFor(variant)[name] }.getOrNull()

    /** Expected [variant] files absent from [directory] (all of them for an unknown variant). */
    fun missing(directory: String, variant: String): List<String> =
        runCatching { LayaModelStore(directory, variant).missing() }.getOrDefault(listOf(variant))

    /** Verifies [variant]'s files against their pins and opens Laya; the caller closes a [Opened.Ready]. */
    fun open(directory: String, variant: String): Opened =
        runCatching { LayaModelStore(directory, variant).open() }
            .fold({ Opened.Ready(it) }, { Opened.Failed(it.message ?: it::class.simpleName ?: "could not open Laya") })

    /**
     * One Choice scored exactly as the parity tests score it (the whole state, no budget cut),
     * for the Diagnostics screen. Probabilities come back in [candidates] order; any Kotlin
     * exception becomes a [CaseScore.Failed] instead of crossing into Swift.
     */
    fun scoreCase(
        backend: Backend,
        id: String,
        question: String,
        candidates: List<String>,
        descriptions: List<String>,
        state: String,
    ): CaseScore = runCatching {
        val described = candidates.indices.mapNotNull { i ->
            descriptions.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { candidates[i] to it }
        }.toMap()
        val judgment = Judgment.Choice(id, question, candidates, descriptions = described)
        val masses = backend.score(judgment, TextState.build(listOf(id to state), 1_000_000)).masses
        CaseScore.Scored(candidates.map { masses[it] ?: Double.NaN })
    }.getOrElse { CaseScore.Failed(it.message ?: it::class.simpleName ?: "scoring failed") }

    sealed class CaseScore {
        class Scored(val probabilities: List<Double>) : CaseScore()
        class Failed(val message: String) : CaseScore()
    }

    sealed class Opened {
        class Ready(val laya: LayaOnDevice) : Opened()
        class Failed(val message: String) : Opened()
    }
}

/**
 * Laya under Model settings' memory mode: [first] is the verified open the app just made; a reload
 * after an unload opens [variant] from [directory] again (verifying the files), all in Kotlin so no
 * exception crosses into Swift.
 */
fun LayaOnPhone.memory(first: LayaOnDevice, directory: String, variant: String): dev.loupe.kit.settings.ModelMemory {
    fun wrap(laya: LayaOnDevice) = object : dev.loupe.kit.settings.LoadedModel {
        override val backend: Backend get() = laya.backend
        override fun close() { runCatching { laya.close() } }
    }
    val memory = dev.loupe.kit.settings.ModelMemory({
        runCatching { LayaModelStore(directory, variant).open() }.getOrNull()?.let(::wrap)
    })
    memory.install(wrap(first))
    return memory
}
