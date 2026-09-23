package dev.loupe.sources

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Writes the binary half of the synthetic sample dataset — PDFs, images and the files that are
 * meant to be skipped — into the resources folder. Run with `./gradlew :sources-desktop:generateSamples`;
 * the outputs are committed, so nothing at build or test time depends on this.
 *
 * Every document is invented and says so on its face.
 */
fun main(args: Array<String>) {
    val root = File(args.single())
    // At the foot of each page rather than the head: a banner as the first line colours what a
    // classifier makes of the whole document, and the sample should read like the real thing.
    val notice = "Synthetic sample for the Loupe demo - every name, number and address is invented."
    fun pdf(path: String, created: Calendar, lines: List<String>) {
        val file = File(root, path).apply { parentFile.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 11f)
                cs.setLeading(15f)
                cs.newLineAtOffset(56f, 780f)
                for (line in lines + listOf("", notice)) {
                    cs.showText(line)
                    cs.newLine()
                }
                cs.endText()
            }
            doc.documentInformation.creationDate = created
            doc.documentInformation.producer = "Loupe sample generator"
            doc.documentInformation.title = "Synthetic sample"
            doc.save(file)
        }
        println("wrote $file")
    }
    fun date(y: Int, m: Int, d: Int) = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, 9, 0) }

    pdf(
        "documents/insurance/home-insurance-renewal-2025.pdf", date(2025, 10, 1),
        listOf(
            "HARBOURSIDE INSURANCE", "Home insurance - renewal schedule", "",
            "Policyholder: A. Sample", "Property: 12 Elm Road, Sampletown", "Policy number: HX-0042-SAMPLE",
            "Period of cover: 1 November 2025 to 31 October 2026", "",
            "Buildings cover: £350,000.00", "Contents cover: £40,000.00", "Excess: £250.00",
            "Annual premium: £450.00", "", "Your policy will renew automatically unless you tell us otherwise.",
        ),
    )
    pdf(
        "documents/insurance/home-insurance-renewal-2026.pdf", date(2026, 10, 1),
        listOf(
            "HARBOURSIDE INSURANCE", "Home insurance - renewal schedule", "",
            "Policyholder: A. Sample", "Property: 12 Elm Road, Sampletown", "Policy number: HX-0042-SAMPLE",
            "Period of cover: 1 November 2026 to 31 October 2027", "",
            "Buildings cover: £350,000.00", "Contents cover: £40,000.00", "Excess: £350.00",
            "Annual premium: £553.50", "", "Your policy will renew automatically unless you tell us otherwise.",
        ),
    )
    pdf(
        "documents/bank/northbank-statement-2026-08.pdf", date(2026, 9, 1),
        listOf(
            "NORTHBANK - Current account statement", "Account: A. Sample, sort code 00-00-00, account 00000000",
            "Statement period: 1 August 2026 to 31 August 2026", "",
            "05/08/2026  STREAMFLIX MONTHLY          -9.99", "12/08/2026  CLOUDBOX PLUS               -2.49",
            "14/08/2026  FRESH BASKET MARKET         -12.45", "01/08/2026  RENT 12 ELM ROAD            -1,150.00",
            "28/08/2026  SALARY SAMPLE LTD           +2,400.00", "", "Closing balance: £1,905.07",
        ),
    )
    pdf(
        "documents/downloads/kettle-kx200-manual.pdf", date(2024, 1, 10),
        listOf(
            "Kettle KX-200 - User manual", "", "Safety instructions: do not immerse the base in water.",
            "Descaling: fill with equal parts water and vinegar, boil, rinse twice.",
            "Warranty: 2 years from the date of purchase. Keep your proof of purchase.",
        ),
    )
    // A "scan": one image and no text layer, so the scanner reports it as having no text.
    File(root, "documents/downloads/scanned-letter.pdf").let { file ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val img = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().run { color = Color.WHITE; fillRect(0, 0, 200, 100); color = Color.GRAY; fillRect(20, 20, 160, 8); fillRect(20, 40, 120, 8); dispose() }
            }
            PDPageContentStream(doc, page).use { it.drawImage(LosslessFactory.createFromImage(doc, img), 56f, 600f) }
            doc.documentInformation.creationDate = date(2026, 5, 3)
            doc.save(file)
        }
        println("wrote $file")
    }
    fun image(path: String, format: String, w: Int, h: Int, top: Color, bottom: Color) {
        val file = File(root, path).apply { parentFile.mkdirs() }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        for (y in 0 until h) {
            val t = y.toFloat() / h
            g.color = Color(
                (top.red * (1 - t) + bottom.red * t).toInt(),
                (top.green * (1 - t) + bottom.green * t).toInt(),
                (top.blue * (1 - t) + bottom.blue * t).toInt(),
            )
            g.drawLine(0, y, w, y)
        }
        g.dispose()
        ImageIO.write(img, format, file)
        println("wrote $file")
    }
    image("documents/photos/lisbon-sunset.png", "png", 320, 200, Color(250, 170, 90), Color(60, 40, 120))
    image("documents/photos/IMG_2051.jpg", "jpg", 320, 240, Color(120, 180, 230), Color(40, 120, 60))

    // Files the scanner must skip, each for a reason it shows.
    File(root, "documents/downloads/photos-backup.zip").let { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("README.txt"))
            zip.write("$notice\n".toByteArray())
            zip.closeEntry()
        }
        // The same bytes under a name that lies about them.
        file.copyTo(File(root, "documents/downloads/invoice-FINAL.pdf"), overwrite = true)
        println("wrote $file and its lying copy")
    }
    File(root, "documents/downloads/app-data.bin").writeBytes(ByteArray(64) { (it * 7).toByte() })
}
