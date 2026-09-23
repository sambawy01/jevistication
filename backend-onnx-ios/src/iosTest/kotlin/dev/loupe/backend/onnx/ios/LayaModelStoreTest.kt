@file:OptIn(ExperimentalForeignApi::class)

package dev.loupe.backend.onnx.ios

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [LayaModelStore] with no models needed: hashing, missing files, and refusing a wrong file. */
class LayaModelStoreTest {

    private fun tempDir(): String {
        val dir = NSTemporaryDirectory() + "laya-store-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return dir
    }

    private fun write(path: String, text: String) {
        val f = fopen(path, "wb")!!
        fputs(text, f)
        fclose(f)
    }

    @Test
    fun sha256MatchesKnownVectors() {
        val dir = tempDir()
        write("$dir/abc", "abc")
        write("$dir/empty", "")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", LayaModelStore.sha256("$dir/abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", LayaModelStore.sha256("$dir/empty"))
    }

    @Test
    fun anEmptyDirectoryReportsBothFilesMissingAndRefusesToOpen() {
        val store = LayaModelStore(tempDir())
        assertEquals(LayaModelStore.FILES.keys.toSet(), store.missing().toSet())
        val e = assertFailsWith<IllegalStateException> { store.verify() }
        assertTrue("missing" in e.message!!, e.message)
    }

    @Test
    fun aFileWithTheWrongHashIsRefused() {
        val dir = tempDir()
        write("$dir/${LayaModelStore.TOKENIZER}", "{}")
        write("$dir/${LayaModelStore.GRAPH}", "not a graph")
        val store = LayaModelStore(dir)
        assertEquals(emptyList(), store.missing())
        val e = assertFailsWith<IllegalStateException> { store.open() }
        assertTrue("SHA-256" in e.message!!, e.message)
    }

    @Test
    fun theDefaultVariantIsTheShippedInt8AndThePartialOneIsPinnedToo() {
        assertEquals(LayaModelStore.FILES, LayaModelStore.filesFor(LayaModelStore.DEFAULT_VARIANT))
        val partial = LayaModelStore.filesFor("int8-partial")
        assertEquals(
            listOf(LayaModelStore.TOKENIZER, "laya-multilingual-choice.int8-partial.onnx"),
            partial.keys.toList(),
        )
        assertEquals("03d732c31f7da991c6d5b1b816032431de67cc7e0080b17ed63a9053e41be973", partial.values.last())
        assertEquals(LayaModelStore.FILES.keys.toSet(), LayaModelStore(tempDir()).missing().toSet())
        assertEquals(partial.keys.toSet(), LayaModelStore(tempDir(), "int8-partial").missing().toSet())
        assertFailsWith<IllegalArgumentException> { LayaModelStore(tempDir(), "fp16") }
    }

    @Test
    fun applicationSupportResolvesToAnExistingDirectory() {
        val store = LayaModelStore.applicationSupport()
        assertTrue(store.directory.endsWith("/Application Support/" + LayaModelStore.SUBDIRECTORY), store.directory)
        assertTrue(NSFileManager.defaultManager.fileExistsAtPath(store.directory))
    }

    @Test
    fun theRealFilesMatchTheirPins() {
        val models = IosLayaFixture.modelsDir() ?: return
        val tokenizer = "$models/laya-multilingual/tokenizer/tokenizer.json"
        val graph = "$models/laya-multilingual-onnx/laya-multilingual-choice.int8.onnx"
        if (!IosLayaFixture.present(tokenizer) || !IosLayaFixture.present(graph)) {
            println("SKIPPED: models not present")
            return
        }
        assertEquals(LayaModelStore.FILES.getValue(LayaModelStore.TOKENIZER), LayaModelStore.sha256(tokenizer))
        assertEquals(LayaModelStore.FILES.getValue(LayaModelStore.GRAPH), LayaModelStore.sha256(graph))
    }
}
