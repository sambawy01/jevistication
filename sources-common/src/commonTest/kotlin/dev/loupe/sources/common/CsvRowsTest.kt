package dev.loupe.sources.common

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from Loupe Station's tests/test_items.py (@ ea7697a): the CSV cases, on JVM and iOS. */
class CsvRowsTest {
    private val statement = "Date,Description,Amount,Currency\n" +
        "05/06/2026,STREAMFLIX.COM,-9.99,GBP\n05/07/2026,STREAMFLIX.COM,-9.99,GBP\n" +
        "12/07/2026,Cafe Luna,\"-1,234.50\",GBP\n,no date,1.00,GBP\n"
    private val arabic = "التاريخ;البيان;مدين;دائن\n٠٥/٠٦/٢٠٢٦;فودافون;350,00;\n06/06/2026;تحويل;;1.000,00\n"

    @Test
    fun mappingIsDetectedByNameAndByValues() {
        val p = CsvRows.read(statement)
        assertEquals(',', p.delimiter)
        assertEquals("Date", p.mapping["date"]); assertEquals("Amount", p.mapping["amount"])
        assertEquals("Description", p.mapping["merchant"]); assertEquals("Currency", p.mapping["currency"])
        assertEquals("name", p.why["date"]); assertEquals(4, p.rows.size)

        val a = CsvRows.read(arabic)
        assertEquals(';', a.delimiter)
        assertEquals("التاريخ", a.mapping["date"]); assertEquals("مدين", a.mapping["debit"])
        assertEquals("دائن", a.mapping["credit"]); assertEquals("البيان", a.mapping["merchant"])

        val bare = CsvRows.read("2026-06-05;Netflix;EGP 120\n2026-07-05;Netflix;EGP 120\n")
        assertEquals(listOf("column 1", "column 2", "column 3"), bare.headers)
        assertFalse(bare.hasHeaderRow); assertEquals(2, bare.rows.size)
        assertEquals("column 1", bare.mapping["date"]); assertEquals("column 3", bare.mapping["amount"])
        assertEquals("column 2", bare.mapping["merchant"]); assertEquals("values", bare.why["merchant"])
    }

    @Test
    fun statementRowsCarryAmountsCurrencyAndDirection() {
        val rows = CsvRows.read(statement).rows
        assertEquals(3, rows.count { it.money != null }, "the row without a date is kept, without statement facts")
        val streamflix = rows.mapNotNull { it.money }.filter { it.merchant == "STREAMFLIX.COM" }
        assertEquals(listOf(LocalDate(2026, 6, 5), LocalDate(2026, 7, 5)), streamflix.map { it.date })
        assertEquals(-999L, streamflix[0].amountMinor); assertEquals("GBP", streamflix[0].currency)
        assertEquals(-123450L, rows.mapNotNull { it.money }.single { it.merchant == "Cafe Luna" }.amountMinor)

        val ar = CsvRows.read(arabic).rows.mapNotNull { it.money }
        assertEquals(2, ar.size)
        val voda = ar.single { it.merchant == "فودافون" }
        assertEquals(LocalDate(2026, 6, 5), voda.date); assertEquals(35000L, voda.amountMinor); assertEquals("debit", voda.direction)
        val transfer = ar.single { it.merchant == "تحويل" }
        assertEquals(-100000L, transfer.amountMinor); assertEquals("credit", transfer.direction)
    }

    @Test
    fun monthFirstAndAmountForms() {
        val p = CsvRows.read("Date,Payee,Amount\n03/04/2026,Gym,\$30.00\n")
        assertEquals("Payee", p.mapping["merchant"])
        assertEquals(LocalDate(2026, 3, 4), CsvRows.parseDate("03/04/2026", dayFirst = false))
        assertEquals(LocalDate(2026, 4, 3), CsvRows.parseDate("03/04/2026"))
        assertEquals(LocalDate(2026, 6, 5), CsvRows.parseDate("20260605"))
        assertEquals(LocalDate(2026, 6, 5), CsvRows.parseDate("05/06/26"))
        assertEquals(-4500L to null, CsvRows.parseAmount("(45.00)"))
        assertEquals(125000L to "EGP", CsvRows.parseAmount("EGP 1.250,00"))
        assertEquals(3000L to "USD", CsvRows.parseAmount("\$30.00"))
        assertEquals(35000L to "EGP", CsvRows.parseAmount("٣٥٠ ج.م"))
        assertEquals(null to null, CsvRows.parseAmount("STREAMFLIX.COM"))
        assertEquals(123456L, CsvRows.parseNumber("1 234,56"))
        assertEquals(123400L, CsvRows.parseNumber("1,234"))
        assertEquals(1250L, CsvRows.parseNumber("12,50"))
        assertNull(CsvRows.parseNumber("1,23,4"))
    }

    @Test
    fun delimiterSniffingAndQuotes() {
        assertEquals('\t', CsvRows.sniff("a\tb\tc\n1\t2\t3\n"))
        assertEquals('|', CsvRows.sniff("a|b\n1|2\n3|4\n"))
        assertEquals(';', CsvRows.sniff("name;note\nA;\"x, y, z\"\nB;\"p, q\"\n"), "commas inside quotes do not count")
        val t = CsvRows.read("name,note\nA,\"line one\nline two\"\nB,\"say \"\"hi\"\"\"\n")
        assertEquals(listOf("A", "line one\nline two"), t.rows[0].cells)
        assertEquals("say \"hi\"", t.rows[1].cells[1])
        assertTrue(CsvRows.read("").rows.isEmpty())
    }

    @Test
    fun decodingBomUtf16AndWindows1252() {
        assertEquals("a,b", CsvRows.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61, 0x2C, 0x62)))
        assertEquals("a,b", CsvRows.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x61, 0, 0x2C, 0, 0x62, 0)))
        assertEquals("café", CsvRows.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
    }
}
