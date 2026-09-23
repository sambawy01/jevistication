package dev.loupe.kit.watchers

import dev.loupe.sources.common.ImageInfo
import dev.loupe.sources.common.PdfInfo
import dev.loupe.sources.common.PlatformExtractors
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument

/** PDFKit, as the app's `AppleExtractors` reads PDFs. Images are not needed by the watchers. */
internal actual fun sampleReaders(): PlatformExtractors = object : PlatformExtractors {
    override fun readPdf(path: String): PdfInfo {
        val doc = PDFDocument(uRL = NSURL.fileURLWithPath(path)) ?: return PdfInfo("", 0, null, null, "damaged PDF: PDFKit could not open it")
        return PdfInfo((doc.string ?: "").trim(), doc.pageCount.toInt(), null, null)
    }

    override fun readImage(path: String): ImageInfo = ImageInfo(emptyMap(), null)
}
