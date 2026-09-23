package dev.loupe.engine

/** A content type the engine recognises mechanically. */
enum class ContentType(val mime: String) {
    PDF("application/pdf"),
    PNG("image/png"),
    JPEG("image/jpeg"),
    GIF("image/gif"),
    ZIP("application/zip"),
    PLAIN_TEXT("text/plain"),
    UNKNOWN("application/octet-stream"),
    ;

    /** True for types the engine reads as an image and must OCR before judging. */
    val isImage: Boolean get() = this == PNG || this == JPEG || this == GIF
}

/**
 * Content-type detection (A3): exact, free, and decided before any judgment runs.
 *
 * **Magic bytes beat the extension**, always. A file named `.pdf` that begins with `PK` is a zip,
 * whatever it claims, and a pipeline that trusts the name is one rename away from feeding a
 * judgment something it cannot read.
 */
object MimeFacts {

    private val EXTENSIONS = mapOf(
        "pdf" to ContentType.PDF,
        "png" to ContentType.PNG,
        "jpg" to ContentType.JPEG,
        "jpeg" to ContentType.JPEG,
        "gif" to ContentType.GIF,
        "zip" to ContentType.ZIP,
        "docx" to ContentType.ZIP,
        "xlsx" to ContentType.ZIP,
        "txt" to ContentType.PLAIN_TEXT,
        "md" to ContentType.PLAIN_TEXT,
        "csv" to ContentType.PLAIN_TEXT,
    )

    /** Sniffs [bytes] by leading signature, or [ContentType.UNKNOWN] if none matches. */
    fun sniff(bytes: ByteArray): ContentType = when {
        startsWith(bytes, 0x25, 0x50, 0x44, 0x46) -> ContentType.PDF // %PDF
        startsWith(bytes, 0x89, 0x50, 0x4E, 0x47) -> ContentType.PNG
        startsWith(bytes, 0xFF, 0xD8, 0xFF) -> ContentType.JPEG
        startsWith(bytes, 0x47, 0x49, 0x46, 0x38) -> ContentType.GIF // GIF8
        startsWith(bytes, 0x50, 0x4B, 0x03, 0x04) -> ContentType.ZIP // PK..
        else -> ContentType.UNKNOWN
    }

    /** The type implied by [fileName]'s extension, which is a claim, not a fact. */
    fun fromExtension(fileName: String): ContentType =
        EXTENSIONS[fileName.substringAfterLast('.', "").lowercase()] ?: ContentType.UNKNOWN

    /**
     * The type of a file, preferring the signature over the name.
     *
     * The extension is consulted only where the bytes carry no signature we know — which is the
     * normal case for plain text.
     */
    fun detect(fileName: String, bytes: ByteArray): ContentType {
        val sniffed = sniff(bytes)
        return if (sniffed != ContentType.UNKNOWN) sniffed else fromExtension(fileName)
    }

    /** True when [fileName]'s extension disagrees with what the bytes actually are. */
    fun extensionLies(fileName: String, bytes: ByteArray): Boolean {
        val sniffed = sniff(bytes)
        if (sniffed == ContentType.UNKNOWN) return false
        val claimed = fromExtension(fileName)
        return claimed != ContentType.UNKNOWN && claimed != sniffed
    }

    private fun startsWith(bytes: ByteArray, vararg signature: Int): Boolean {
        if (bytes.size < signature.size) return false
        return signature.withIndex().all { (i, b) -> bytes[i] == b.toByte() }
    }
}

/**
 * Whether OCR produced anything worth judging (A3).
 *
 * An image whose OCR yields nothing usable cannot be judged on its text, and a judgment handed
 * three stray characters will confidently answer about noise. Gating on this mechanically is how
 * that item reaches the uncertain queue instead of a wrong answer.
 */
object OcrFacts {

    /**
     * True when [ocrOutput] carries enough text, enough of which is letters, to judge on.
     *
     * @param minCharacters below this there is nothing to read.
     * @param minLetterRatio OCR noise is mostly punctuation and stray marks; real text is mostly
     *   letters and digits.
     */
    fun hasUsableText(
        ocrOutput: String,
        minCharacters: Int = 12,
        minLetterRatio: Double = 0.5,
    ): Boolean {
        require(minCharacters >= 0) { "minCharacters must not be negative" }
        require(minLetterRatio in 0.0..1.0) { "minLetterRatio must be in [0,1]" }

        val trimmed = ocrOutput.trim()
        if (trimmed.length < minCharacters) return false
        val meaningful = trimmed.count { it.isLetterOrDigit() }
        return meaningful.toDouble() / trimmed.length >= minLetterRatio
    }
}
