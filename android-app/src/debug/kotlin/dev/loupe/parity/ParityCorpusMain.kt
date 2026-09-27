package dev.loupe.parity

import java.io.File
import kotlin.system.exitProcess

/**
 * Runs tools/parity/corpus.json on a phone or emulator, on Android's own regex engine (ICU) and the
 * shared code exactly as the app ships it. Started by tools/parity/run-device.sh with app_process:
 *
 *     CLASSPATH=<the debug APK> app_process /system/bin dev.loupe.parity.ParityCorpusMain <corpus.json>
 *
 * Prints one line per differing case and a summary; exits 1 when any case differs. Debug builds only.
 */
object ParityCorpusMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: run {
            println("usage: ParityCorpusMain <corpus.json>")
            exitProcess(2)
        }
        val result = ParityCorpus.run(File(path).readText(Charsets.UTF_8))
        for (f in result.failures) println("FAIL $f")
        println("functions: " + result.byFn.entries.joinToString(", ") { "${it.key} ${it.value}" })
        println("parity corpus: ${result.cases} cases, ${result.failures.size} differ")
        exitProcess(if (result.failures.isEmpty()) 0 else 1)
    }
}
