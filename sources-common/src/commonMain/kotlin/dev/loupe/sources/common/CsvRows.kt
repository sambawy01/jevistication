package dev.loupe.sources.common

import dev.loupe.engine.DateFacts
import kotlinx.datetime.LocalDate

/**
 * A CSV file as rows with their column context (PRODUCT.md §2), for the Inbox (epic #7 child 15).
 * Ported from Loupe Station's `laya_studio/items/csvimport.py` (@ ea7697a): the same decoding
 * (UTF-8 with or without BOM, UTF-16, else Windows-1252), the delimiter sniffed among `, ; tab |`,
 * the same "no header row" rule (a date or an amount in row 1), the same header names in English and
 * Arabic for date / amount / debit / credit / merchant / description / currency, the same fallback
 * to the values (the column whose cells read as dates, as amounts, as text), and the same amount
 * reading (1,234.56 and 1.234,56, parentheses for negatives, Arabic-Indic digits, currency markers).
 *
 * Unlike Station, which imports statements only, every non-empty row is kept: a row with a date and
 * an amount also carries the statement facts ([Row.money]); the others are rows with context.
 */
object CsvRows {
    const val MAX_CSV_BYTES: Long = 20L * 1024 * 1024
    const val MAX_ROWS: Int = 100_000
    val FIELDS: List<String> = listOf("date", "amount", "debit", "credit", "merchant", "description", "currency")
    val CURRENCIES: List<String> = listOf("EGP", "USD", "EUR", "SAR", "AED", "GBP")

    private val NAMES: Map<String, Set<String>> = mapOf(
        "date" to setOf("date", "transaction date", "posting date", "posted", "value date", "booking date", "trans date",
            "تاريخ", "التاريخ", "تاريخ العملية", "تاريخ المعاملة", "تاريخ القيد"),
        "amount" to setOf("amount", "value", "sum", "total", "transaction amount", "amount (egp)", "المبلغ", "مبلغ", "القيمة"),
        "debit" to setOf("debit", "withdrawal", "withdrawals", "paid out", "money out", "مدين", "سحب", "مسحوبات"),
        "credit" to setOf("credit", "deposit", "deposits", "paid in", "money in", "دائن", "إيداع", "ايداع", "إيداعات"),
        "merchant" to setOf("merchant", "payee", "merchant name", "counterparty", "name", "vendor", "التاجر", "المستفيد", "الجهة"),
        "description" to setOf("description", "details", "narrative", "memo", "reference", "transaction details", "particulars",
            "البيان", "الوصف", "التفاصيل"),
        "currency" to setOf("currency", "ccy", "curr", "العملة"),
    )

    /** Station's `_MARKERS`, longest / most specific first. */
    private val MARKERS: List<Pair<Regex, String>> = listOf(
        "جنيه\\s+(?:إ|ا)سترليني" to "GBP", "جنيه\\s+مصري" to "EGP", "ريال\\s+سعودي" to "SAR",
        "US\\$" to "USD", "E£" to "EGP", "L\\.E\\.?" to "EGP", "ج\\.\\s?م\\.?" to "EGP", "ر\\.\\s?س\\.?" to "SAR", "د\\.\\s?إ\\.?" to "AED",
        "EGP" to "EGP", "USD" to "USD", "EUR" to "EUR", "SAR" to "SAR", "AED" to "AED", "GBP" to "GBP",
        "LE" to "EGP", "SR" to "SAR", "Dhs?" to "AED",
        "جنيه" to "EGP", "جم" to "EGP", "ريال" to "SAR", "درهم" to "AED", "دولار" to "USD", "يورو" to "EUR",
        "\\$" to "USD", "€" to "EUR", "£" to "GBP",
    ).map { (re, code) -> Regex(re) to code }

    private val NUMERIC_CELL = Regex("^[-+(]?\\s*[^\\sA-Za-z]{0,4}\\s*[0-9٠-٩][0-9٠-٩,.٫٬  ]*\\s*[^\\s0-9]{0,6}\\)?$")

    /** A statement row's facts: amount in minor units with the file's sign, currency, direction. */
    data class Money(val date: LocalDate, val amountMinor: Long, val currency: String?, val direction: String?, val merchant: String?, val description: String?)

    data class Row(val number: Int, val cells: List<String>, val money: Money?)

    data class Table(
        val delimiter: Char,
        val headers: List<String>,
        val hasHeaderRow: Boolean,
        val rows: List<Row>,
        /** field -> header, for the fields that were found. */
        val mapping: Map<String, String>,
        /** field -> "name" | "values": why each column was picked. */
        val why: Map<String, String>,
        val truncated: Boolean,
    ) {
        val delimiterName: String get() = if (delimiter == '\t') "tab" else delimiter.toString()
        val statementRows: Int get() = rows.count { it.money != null }
    }

    fun decode(data: ByteArray): String {
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return data.copyOfRange(3, data.size).decodeToString()
        }
        if (data.size >= 2 && ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) || (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))) {
            val le = data[0] == 0xFF.toByte()
            val sb = StringBuilder()
            var i = 2
            while (i + 1 < data.size) {
                val a = data[i].toInt() and 0xFF
                val b = data[i + 1].toInt() and 0xFF
                sb.append((if (le) a or (b shl 8) else (a shl 8) or b).toChar())
                i += 2
            }
            return sb.toString()
        }
        val utf8 = data.decodeToString()
        return if ('\uFFFD' in utf8) Charsets.windows1252(data) else utf8
    }

    /** Splits [text] into records with [delim], honouring double quotes (RFC 4180, as Python's csv). */
    fun parse(text: String, delim: Char, maxRows: Int = MAX_ROWS + 1): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endRow() {
            row.add(cell.toString()); cell.clear()
            rows.add(row); row = mutableListOf()
        }
        while (i < text.length && rows.size < maxRows) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> if (cell.isEmpty()) quoted = true else cell.append(ch)
                    delim -> { row.add(cell.toString()); cell.clear() }
                    '\r' -> { endRow(); if (i + 1 < text.length && text[i + 1] == '\n') i++ }
                    '\n' -> endRow()
                    else -> cell.append(ch)
                }
            }
            i++
        }
        if ((cell.isNotEmpty() || row.isNotEmpty()) && rows.size < maxRows) endRow()
        return rows
    }

    /**
     * The delimiter among `, ; tab |`: the one that splits the first lines into the same number of
     * fields (more than one) most consistently — Python's `csv.Sniffer` idea — else, as Station
     * falls back, the one that occurs most in the sample.
     */
    fun sniff(text: String): Char {
        val sample = text.take(20_000)
        val candidates = listOf(',', '\t', ';', '|')
        var best: Char? = null
        var bestScore = 0.0
        var bestWidth = 0
        for (d in candidates) {
            val lines = parse(sample, d, 40).filter { r -> r.any { it.isNotBlank() } }.take(20)
            if (lines.size < 1) continue
            val widths = lines.map { it.size }
            val mode = widths.groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key }))!!
            if (mode.key < 2) continue
            val consistency = mode.value.toDouble() / lines.size
            if (consistency > bestScore + 1e-9 || (consistency > bestScore - 1e-9 && mode.key > bestWidth)) {
                best = d; bestScore = consistency; bestWidth = mode.key
            }
        }
        if (best != null && bestScore >= 0.9) return best
        return candidates.maxBy { d -> sample.count { it == d } }
    }

    fun read(text: String, dayFirst: Boolean = true): Table {
        val delim = sniff(text)
        val parsed = parse(text, delim)
        val all = parsed.filter { r -> r.any { it.isNotBlank() } }.map { r -> r.map { it.trim() } }
        val truncated = all.size > MAX_ROWS
        val kept = all.take(MAX_ROWS + 1)
        if (kept.isEmpty()) return Table(delim, emptyList(), false, emptyList(), emptyMap(), emptyMap(), false)
        val first = kept.first()
        val noHeader = first.any { it.isNotEmpty() && looksLikeValue(it) }
        val headers = if (noHeader) List(first.size) { "column ${it + 1}" } else first
        val body = (if (noHeader) kept else kept.drop(1)).take(MAX_ROWS)
        val (mapping, why) = detectMapping(headers, body)
        val rows = body.mapIndexed { i, cells -> Row(i + 1, cells, money(headers, cells, mapping, dayFirst)) }
        return Table(delim, headers, !noHeader, rows, mapping, why, truncated)
    }

    private fun looksLikeValue(cell: String): Boolean = parseDate(cell) != null || parseAmount(cell).first != null

    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '٠'..'٩') '0' + (c - '٠') else if (c in '۰'..'۹') '0' + (c - '۰') else c)
    }

    fun parseDate(cell: String, dayFirst: Boolean = true): LocalDate? {
        val s = normalizeDigits(cell)
        DateFacts.find(s, dayFirst).firstOrNull()?.let { return it.date }
        Regex("\\s*([0-9]{4})([0-9]{2})([0-9]{2})\\s*").matchEntire(s)?.let { m ->
            return date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("\\s*([0-9]{1,2})[/.\\-]([0-9]{1,2})[/.\\-]([0-9]{2})\\s*").matchEntire(s)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = 2000 + m.groupValues[3].toInt()
            return if (dayFirst) date(y, b, a) else date(y, a, b)
        }
        return null
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()

    /** (minor units, currency or null): "1,234.50", "-45.00", "(45.00)", "EGP 1.250,00", "٣٥٠ ج.م". */
    fun parseAmount(cell: String): Pair<Long?, String?> {
        val s = cell.trim()
        if (s.isEmpty()) return null to null
        val neg = s.startsWith("-") || (s.startsWith("(") && s.endsWith(")"))
        var core = s.removePrefix("-").removePrefix("+").let { if (it.startsWith("(") && it.endsWith(")")) it.substring(1, it.length - 1) else it }.trim()
        var currency: String? = null
        for ((re, code) in MARKERS) {
            val m = re.find(core) ?: continue
            val before = core.substring(0, m.range.first).trim()
            val after = core.substring(m.range.last + 1).trim()
            if (before.isEmpty() || after.isEmpty()) {
                currency = code
                core = (before + after).trim().removePrefix("-").trim()
                break
            }
        }
        if (currency == null && !NUMERIC_CELL.matches(s)) return null to null
        core = core.replace(Regex("[^0-9٠-٩,.٫٬  ]"), "").trim()
        val minor = parseNumber(core) ?: return null to null
        return (if (neg) -minor else minor) to currency
    }

    /** Station's `facts.money.parse_number`: a written number -> minor units, or null when ambiguous. */
    fun parseNumber(raw: String): Long? {
        var s = normalizeDigits(raw).trim().replace('٬', ',').replace('٫', '.')
        s = s.replace(Regex("[\u00a0\u202f ]"), " ")
        if (s.isEmpty() || !Regex("[0-9][0-9 ,.]*").matches(s)) return null
        if (' ' in s) {
            val groups = s.split(' ')
            val last = Regex("([0-9]{3})(?:[.,]([0-9]{1,2}))?").matchEntire(groups.last()) ?: return null
            if (!Regex("[0-9]{1,3}").matches(groups.first()) || !groups.subList(1, groups.size - 1).all { Regex("[0-9]{3}").matches(it) }) return null
            val frac = last.groupValues[2]
            return toMinor(groups.dropLast(1).joinToString("") + last.groupValues[1] + if (frac.isNotEmpty()) ".$frac" else "")
        }
        val hasDot = '.' in s
        val hasComma = ',' in s
        if (hasDot && hasComma) {
            val dec = if (s.lastIndexOf('.') > s.lastIndexOf(',')) '.' else ','
            val thou = if (dec == '.') ',' else '.'
            val intPart = s.substringBeforeLast(dec)
            val frac = s.substringAfterLast(dec)
            if (thou in frac || dec in intPart || !grouped(intPart, thou) || !Regex("[0-9]{1,2}").matches(frac)) return null
            return toMinor(intPart.replace(thou.toString(), "") + "." + frac)
        }
        val sep = if (hasDot) '.' else if (hasComma) ',' else null
        if (sep == null) return toMinor(s)
        val parts = s.split(sep)
        if (parts.size > 2) return if (grouped(s, sep)) toMinor(s.replace(sep.toString(), "")) else null
        val (head, tail) = parts
        if (tail.length == 3 && head.length in 1..3) return toMinor(head + tail)
        if (tail.length in 1..2) return toMinor("$head.$tail")
        return null
    }

    private fun grouped(intPart: String, sep: Char): Boolean {
        val groups = intPart.split(sep)
        return Regex("[0-9]{1,3}").matches(groups.first()) && groups.drop(1).all { Regex("[0-9]{3}").matches(it) }
    }

    private fun toMinor(s: String): Long? {
        val major = s.substringBefore('.')
        val frac = s.substringAfter('.', "").padEnd(2, '0')
        if (major.isEmpty() || major.length > 15 || frac.length > 2) return null
        return major.toLongOrNull()?.let { it * 100 + frac.toLong() }
    }

    private fun norm(h: String): String = h.trim().lowercase().replace('_', ' ').split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Station's `detect_mapping`: by header name first, then by what the values look like. */
    fun detectMapping(headers: List<String>, rows: List<List<String>>): Pair<Map<String, String>, Map<String, String>> {
        val mapping = linkedMapOf<String, String>()
        val why = linkedMapOf<String, String>()
        val normed = headers.map(::norm)
        for (f in FIELDS) {
            for ((i, h) in normed.withIndex()) {
                if (h in NAMES.getValue(f) && headers[i] !in mapping.values) {
                    mapping[f] = headers[i]; why[f] = "name"; break
                }
            }
        }
        if ("merchant" !in mapping && "description" in mapping) {
            mapping["merchant"] = mapping.getValue("description"); why["merchant"] = "name"
        }
        val sample = rows.take(50)
        fun share(i: Int, test: (String) -> Boolean): Double {
            val cells = sample.mapNotNull { it.getOrNull(i) }.filter { it.isNotEmpty() }
            return if (cells.isEmpty()) 0.0 else cells.count(test).toDouble() / cells.size
        }
        val taken = mapping.values.toSet()
        val free = headers.indices.filter { headers[it] !in taken }.toMutableList()
        val isDate = { c: String -> parseDate(c) != null }
        val isAmount = { c: String -> parseAmount(c).first != null }
        if ("date" !in mapping) {
            free.maxByOrNull { share(it, isDate) }?.takeIf { share(it, isDate) >= 0.6 }?.let {
                mapping["date"] = headers[it]; why["date"] = "values"; free.remove(it)
            }
        }
        if ("amount" !in mapping && "debit" !in mapping) {
            free.maxByOrNull { share(it, isAmount) }?.takeIf { share(it, isAmount) >= 0.6 }?.let {
                mapping["amount"] = headers[it]; why["amount"] = "values"; free.remove(it)
            }
        }
        if ("merchant" !in mapping) {
            val letters = Regex("[A-Za-zء-ي]{3}")
            fun texty(i: Int): Double {
                val cells = sample.mapNotNull { it.getOrNull(i) }.filter { it.isNotEmpty() }
                if (cells.isEmpty()) return 0.0
                val n = cells.count { letters.containsMatchIn(it) && parseAmount(it).first == null }
                return n.toDouble() / cells.size + cells.toSet().size / (10.0 * cells.size)
            }
            free.maxByOrNull(::texty)?.takeIf { texty(it) >= 0.6 }?.let { mapping["merchant"] = headers[it]; why["merchant"] = "values" }
        }
        return mapping to why
    }

    /** Station's `import_rows` per row: the statement facts, or null without a date and an amount. */
    private fun money(headers: List<String>, r: List<String>, mapping: Map<String, String>, dayFirst: Boolean): Money? {
        fun col(f: String): Int? = mapping[f]?.let { headers.indexOf(it) }?.takeIf { it >= 0 }
        fun cell(f: String): String = col(f)?.let { r.getOrNull(it) } ?: ""
        if (col("date") == null || (col("amount") == null && col("debit") == null && col("credit") == null)) return null
        val d = parseDate(cell("date"), dayFirst) ?: return null
        var minor: Long? = null
        var ccy: String? = null
        var direction: String? = null
        if (col("amount") != null) parseAmount(cell("amount")).let { minor = it.first; ccy = it.second }
        if (minor == null && (col("debit") != null || col("credit") != null)) {
            val (dm, dc) = parseAmount(cell("debit"))
            val (cm, cc) = parseAmount(cell("credit"))
            if (dm != null && dm != 0L) {
                minor = kotlin.math.abs(dm); ccy = dc; direction = "debit"
            } else if (cm != null) {
                minor = -kotlin.math.abs(cm); ccy = cc; direction = "credit"
            }
        }
        val amount = minor ?: return null
        cell("currency").trim().uppercase().takeIf { it in CURRENCIES }?.let { ccy = it }
        val merchant = cell("merchant").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(200).ifEmpty { null }
        val desc = cell("description").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(200).ifEmpty { null }
        return Money(d, amount, ccy, direction, merchant, desc)
    }

    /** "12.40", "-1,234.50" style display of minor units. */
    fun formatMinor(minor: Long): String {
        val sign = if (minor < 0) "-" else ""
        val a = kotlin.math.abs(minor)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }
}
