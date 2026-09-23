package dev.loupe.sources.common

/** A PDF's text layer and info, as the platform reads it. [error] set means unreadable, with why. */
class PdfInfo(
    val text: String,
    val pages: Int,
    /** Creation date, `yyyy-MM-dd` in the scan's time zone, or null. */
    val createdIso: String?,
    val producer: String?,
    val error: String? = null,
)

/** Image metadata as the platform reads it: facts in display order, EXIF date `yyyy-MM-dd` or null. */
class ImageInfo(val facts: Map<String, String>, val takenIso: String?)

/**
 * What only the platform can read well: PDF text (PDFKit on iOS, PDFBox on the JVM) and image
 * metadata (ImageIO on iOS, metadata-extractor on the JVM). Implemented in Swift on iOS, so the
 * calls take a path and return plain values rather than throwing across the language boundary.
 */
interface PlatformExtractors {
    fun readPdf(path: String): PdfInfo

    fun readImage(path: String): ImageInfo
}

/** Stand-in when no platform reader is wired: PDFs and images are skipped with the reason shown. */
object NoPlatformExtractors : PlatformExtractors {
    override fun readPdf(path: String): PdfInfo = PdfInfo("", 0, null, null, error = "no PDF reader on this platform")

    override fun readImage(path: String): ImageInfo = ImageInfo(mapOf("metadata" to "unreadable (no image reader on this platform)"), null)
}
