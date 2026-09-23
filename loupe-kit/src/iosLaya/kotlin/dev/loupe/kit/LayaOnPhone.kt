package dev.loupe.kit

import dev.loupe.backend.onnx.ios.LayaModelStore
import dev.loupe.backend.onnx.ios.LayaOnDevice

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

    sealed class Opened {
        class Ready(val laya: LayaOnDevice) : Opened()
        class Failed(val message: String) : Opened()
    }
}
