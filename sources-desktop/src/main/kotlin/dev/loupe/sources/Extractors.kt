package dev.loupe.sources

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import org.apache.james.mime4j.dom.Entity
import org.apache.james.mime4j.dom.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** A file or message could not be read, for a reason worth showing the user. */
class Unreadable(reason: String) : Exception(reason)

/**
 * Plain text from bytes: UTF-8 when the bytes are valid UTF-8, otherwise Windows-1252 — the two
 * encodings real personal files overwhelmingly use. Never guesses silently: a decoding that had to
 * fall back is still text, just read in the other encoding.
 */
internal object PlainText {
    fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(body)).toString()
        } catch (_: CharacterCodingException) {
            String(body, charset("windows-1252"))
        }
    }

    /** A NUL byte in the first 8 KB means this is not a text file whatever its name says. */
    fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }
}

/**
 * Visible text from HTML. Tags are stripped, `<script>` and `<style>` dropped whole, block
 * elements become line breaks and the common entities are decoded. That is all: this is text
 * extraction for a classifier, not a renderer, and nothing is rewritten or summarised.
 */
object HtmlText {
    private val DROP = Regex("""<(script|style|head|noscript)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK = Regex("""<\s*(br|/p|/div|/li|/tr|/h[1-6]|/table|p|div|li|tr|h[1-6])\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val NUMERIC = Regex("""&#(x[0-9a-fA-F]+|\d+);""")
    private val NAMED = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&pound;" to "£", "&euro;" to "€", "&copy;" to "©",
        "&ndash;" to "–", "&mdash;" to "—", "&hellip;" to "…",
    )
    private val SPACES = Regex("""[ \t ]+""")
    private val BLANK_LINES = Regex("""\n\s*\n\s*\n+""")

    fun toText(html: String): String {
        var s = COMMENT.replace(html, " ")
        s = DROP.replace(s, " ")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, " ")
        s = NUMERIC.replace(s) { m ->
            val code = m.groupValues[1]
            val cp = if (code.startsWith("x")) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else " "
        }
        for ((entity, replacement) in NAMED) s = s.replace(entity, replacement)
        s = s.lines().joinToString("\n") { SPACES.replace(it, " ").trim() }
        return BLANK_LINES.replace(s, "\n\n").trim()
    }
}

/** Links in text or HTML. Mechanical: these are what the site-fraud watcher assesses. */
object Links {
    private val HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""https?://[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)

    fun find(vararg texts: String): List<String> {
        val found = LinkedHashSet<String>()
        for (text in texts) {
            HREF.findAll(text).map { it.groupValues[1].trim() }.filter { it.startsWith("http", ignoreCase = true) }.forEach { found += it }
            BARE.findAll(text).map { it.value.trimEnd('.', ',', ';') }.forEach { found += it }
        }
        return found.toList()
    }
}

/** An email read by Apache James mime4j: headers, a text body, links and attachment names. */
internal data class ParsedEmail(val facts: EmailFacts, val body: String) {
    /** What the model reads: the identifying headers, a blank line, then the body verbatim. */
    fun modelText(): String = buildString {
        val from = listOfNotNull(facts.fromName, facts.fromAddress?.let { "<$it>" }).joinToString(" ")
        if (from.isNotBlank()) append("From: ").append(from).append('\n')
        if (facts.to.isNotEmpty()) append("To: ").append(facts.to.joinToString(", ")).append('\n')
        facts.date?.let { append("Date: ").append(it).append('\n') }
        facts.subject?.let { append("Subject: ").append(it).append('\n') }
        if (facts.attachmentNames.isNotEmpty()) append("Attachments: ").append(facts.attachmentNames.joinToString(", ")).append('\n')
        append('\n').append(body)
    }.trim()
}

internal object EmailExtractor {

    /** Permissive: real exports carry malformed headers, and one bad header must not lose a message. */
    private fun builder() = DefaultMessageBuilder().apply {
        setMimeEntityConfig(MimeConfig.PERMISSIVE)
        setContentDecoding(true)
    }

    fun parse(bytes: ByteArray, zone: ZoneId): ParsedEmail {
        val message: Message = try {
            builder().parseMessage(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw Unreadable("not a readable email: ${e.message ?: e::class.simpleName}")
        }
        try {
            val plain = mutableListOf<String>()
            val html = mutableListOf<String>()
            val attachments = mutableListOf<String>()
            collect(message, plain, html, attachments)
            val htmlRaw = html.joinToString("\n")
            val body = when {
                plain.isNotEmpty() -> plain.joinToString("\n\n")
                html.isNotEmpty() -> HtmlText.toText(htmlRaw)
                else -> ""
            }
            val from = message.from?.firstOrNull()
            val facts = EmailFacts(
                fromName = from?.name?.takeIf { it.isNotBlank() },
                fromAddress = from?.address?.lowercase(),
                to = message.to?.flatten()?.map(Mailbox::getAddress).orEmpty(),
                subject = message.subject?.trim()?.takeIf { it.isNotEmpty() },
                date = message.date?.let { toLocalDate(it, zone) },
                links = Links.find(body, htmlRaw),
                attachmentNames = attachments,
            )
            return ParsedEmail(facts, body.trim())
        } finally {
            message.dispose()
        }
    }

    private fun collect(entity: Entity, plain: MutableList<String>, html: MutableList<String>, attachments: MutableList<String>) {
        val body = entity.body
        if (body is Multipart) {
            body.bodyParts.forEach { collect(it, plain, html, attachments) }
            return
        }
        val isAttachment = entity.dispositionType.equals("attachment", ignoreCase = true) ||
            (entity.filename != null && entity.mimeType?.startsWith("text/") != true)
        if (isAttachment) {
            attachments += entity.filename ?: entity.mimeType ?: "attachment"
            return
        }
        if (body is TextBody) {
            val text = body.reader.use { it.readText() }
            when (entity.mimeType?.lowercase()) {
                "text/plain" -> plain += text
                "text/html" -> html += text
            }
        }
    }

    private fun toLocalDate(date: Date, zone: ZoneId): LocalDate = date.toInstant().atZone(zone).toLocalDate()
}

/** Text from a PDF's text layer, by Apache PDFBox. No rendering and no OCR. */
internal object PdfExtractor {
    data class Pdf(val text: String, val pages: Int, val created: LocalDate?, val producer: String?)

    fun read(bytes: ByteArray, zone: ZoneId): Pdf {
        val document = try {
            Loader.loadPDF(bytes)
        } catch (_: InvalidPasswordException) {
            throw Unreadable("encrypted PDF: needs a password")
        } catch (e: Exception) {
            throw Unreadable("damaged PDF: ${e.message ?: e::class.simpleName}")
        }
        document.use { doc ->
            if (doc.isEncrypted && !doc.currentAccessPermission.canExtractContent()) {
                throw Unreadable("encrypted PDF: text extraction not permitted")
            }
            val text = PDFTextStripper().getText(doc)
            val info = doc.documentInformation
            return Pdf(
                text = text.trim(),
                pages = doc.numberOfPages,
                created = info?.creationDate?.toInstant()?.atZone(zone)?.toLocalDate(),
                producer = info?.producer,
            )
        }
    }
}

/** EXIF and container metadata from an image, by metadata-extractor. Pixels are never read. */
internal object ImageExtractor {
    data class Image(val facts: Map<String, String>, val taken: LocalDate?)

    fun read(bytes: ByteArray, zone: ZoneId): Image {
        val metadata = try {
            ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes), bytes.size.toLong())
        } catch (e: Exception) {
            // An image whose metadata cannot be parsed is still an image; it just has no facts.
            return Image(mapOf("metadata" to "unreadable (${e.message ?: e::class.simpleName})"), null)
        }
        val facts = LinkedHashMap<String, String>()
        var width: String? = null
        var height: String? = null
        for (directory in metadata.directories) {
            for (tag in directory.tags) {
                when (tag.tagName) {
                    "Image Width" -> if (width == null) width = tag.description?.substringBefore(' ')
                    "Image Height" -> if (height == null) height = tag.description?.substringBefore(' ')
                }
            }
        }
        if (width != null && height != null) facts["dimensions"] = "${width}x$height"
        metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.let { d ->
            listOfNotNull(d.getString(ExifIFD0Directory.TAG_MAKE), d.getString(ExifIFD0Directory.TAG_MODEL))
                .joinToString(" ").takeIf { it.isNotBlank() }?.let { facts["camera"] = it.trim() }
        }
        val taken = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ?.getDateOriginal(java.util.TimeZone.getTimeZone(zone))
            ?.toInstant()?.atZone(zone)?.toLocalDate()
        taken?.let { facts["taken"] = it.toString() }
        if (metadata.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation != null) {
            facts["location"] = "GPS position recorded"
        }
        return Image(facts, taken)
    }
}
