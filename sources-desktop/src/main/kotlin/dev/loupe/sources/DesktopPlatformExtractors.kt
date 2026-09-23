package dev.loupe.sources

import dev.loupe.sources.common.ImageInfo
import dev.loupe.sources.common.PdfInfo
import dev.loupe.sources.common.PlatformExtractors
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId

/**
 * The JVM's platform readers for :sources-common's scanner: PDFBox for PDF text layers and
 * metadata-extractor for image metadata — the same code the desktop `Scanner` uses.
 */
class DesktopPlatformExtractors(private val zone: ZoneId = ZoneId.systemDefault()) : PlatformExtractors {
    override fun readPdf(path: String): PdfInfo = try {
        val pdf = PdfExtractor.read(Files.readAllBytes(Path.of(path)), zone)
        PdfInfo(pdf.text, pdf.pages, pdf.created?.toString(), pdf.producer)
    } catch (e: Unreadable) {
        PdfInfo("", 0, null, null, error = e.message ?: "unreadable")
    }

    override fun readImage(path: String): ImageInfo {
        val image = ImageExtractor.read(Files.readAllBytes(Path.of(path)), zone)
        return ImageInfo(image.facts, image.taken?.toString())
    }
}
