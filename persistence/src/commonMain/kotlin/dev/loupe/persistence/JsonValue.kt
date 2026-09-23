package dev.loupe.persistence

/**
 * A parsed JSON value: the small tree the app's own files need, in common code.
 *
 * Hand-rolled for the same reason as the engine's emitter: no JSON library is on the iOS side, and
 * reading back files this app wrote is a small, testable amount of code. Accessors mirror the Gson
 * ones the desktop used (`asString` reads a number's text, `asDouble` reads a numeric string), so a
 * line the desktop could read, this reads too.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    /** A number, kept as written so re-emitting it changes no byte. */
    data class Num(val text: String) : JsonValue

    data class Str(val value: String) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    /** Keys in file order; a repeated key keeps its last value (as Gson did). */
    data class Obj(val fields: LinkedHashMap<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
    }

    val isNull: Boolean get() = this is Null

    val asString: String
        get() = when (this) {
            is Str -> value
            is Num -> text
            is Bool -> value.toString()
            else -> throw IllegalStateException("not a string: $this")
        }

    val asDouble: Double
        get() = when (this) {
            is Num -> text.toDouble()
            is Str -> value.toDouble()
            else -> throw IllegalStateException("not a number: $this")
        }

    val asInt: Int
        get() = when (this) {
            is Num -> text.toIntOrNull() ?: text.toDouble().toInt()
            is Str -> value.toInt()
            else -> throw IllegalStateException("not a number: $this")
        }

    val asBoolean: Boolean
        get() = when (this) {
            is Bool -> value
            is Str -> value.toBooleanStrict()
            else -> throw IllegalStateException("not a boolean: $this")
        }

    val asObj: Obj get() = this as? Obj ?: throw IllegalStateException("not an object")

    val asArr: Arr get() = this as? Arr ?: throw IllegalStateException("not an array")

    companion object {
        fun parse(text: String): JsonValue = JsonParser(text).parseDocument()

        fun obj(vararg fields: Pair<String, JsonValue>): Obj = Obj(linkedMapOf(*fields))

        fun str(value: String?): JsonValue = value?.let(::Str) ?: Null

        fun num(value: Int): JsonValue = Num(value.toString())

        fun num(value: Double): JsonValue = Num(value.toString())

        fun strings(values: Collection<String>): Arr = Arr(values.map(::Str))
    }
}

/** Strict JSON (RFC 8259) to [JsonValue]. Throws [IllegalArgumentException] on malformed input. */
internal class JsonParser(private val s: String) {
    private var i = 0

    fun parseDocument(): JsonValue {
        val v = value()
        ws()
        if (i != s.length) fail("trailing characters")
        return v
    }

    private fun fail(msg: String): Nothing = throw IllegalArgumentException("malformed JSON at $i: $msg")

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    }

    private fun value(): JsonValue {
        ws()
        if (i >= s.length) fail("unexpected end")
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonValue.Str(string())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') number() else fail("unexpected '$c'")
        }
    }

    private fun literal(word: String, v: JsonValue): JsonValue {
        if (!s.startsWith(word, i)) fail("expected $word")
        i += word.length
        return v
    }

    private fun obj(): JsonValue.Obj {
        i++ // {
        val fields = LinkedHashMap<String, JsonValue>()
        ws()
        if (i < s.length && s[i] == '}') { i++; return JsonValue.Obj(fields) }
        while (true) {
            ws()
            if (i >= s.length || s[i] != '"') fail("expected a key")
            val key = string()
            ws()
            if (i >= s.length || s[i] != ':') fail("expected ':'")
            i++
            fields.remove(key)
            fields[key] = value()
            ws()
            if (i >= s.length) fail("unterminated object")
            when (s[i++]) {
                ',' -> continue
                '}' -> return JsonValue.Obj(fields)
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun arr(): JsonValue.Arr {
        i++ // [
        val items = ArrayList<JsonValue>()
        ws()
        if (i < s.length && s[i] == ']') { i++; return JsonValue.Arr(items) }
        while (true) {
            items += value()
            ws()
            if (i >= s.length) fail("unterminated array")
            when (s[i++]) {
                ',' -> continue
                ']' -> return JsonValue.Arr(items)
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun number(): JsonValue.Num {
        val start = i
        if (s[i] == '-') i++
        if (i >= s.length) fail("bad number")
        if (s[i] == '0') i++ else if (s[i] in '1'..'9') { while (i < s.length && s[i].isAsciiDigit()) i++ } else fail("bad number")
        if (i < s.length && s[i] == '.') {
            i++
            if (i >= s.length || !s[i].isAsciiDigit()) fail("bad fraction")
            while (i < s.length && s[i].isAsciiDigit()) i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            if (i >= s.length || !s[i].isAsciiDigit()) fail("bad exponent")
            while (i < s.length && s[i].isAsciiDigit()) i++
        }
        return JsonValue.Num(s.substring(start, i))
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun string(): String {
        i++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) fail("unterminated string")
            val c = s[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (i >= s.length) fail("bad escape")
                    when (val e = s[i++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) fail("bad \\u escape")
                            val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> fail("bad escape '\\$e'")
                    }
                }
                c < ' ' -> fail("control character in string")
                else -> out.append(c)
            }
        }
    }
}

/**
 * JSON text in Gson's two layouts, so files written here are byte-identical to what the desktop
 * wrote with Gson: compact (`JsonElement.toString()`) and pretty (`setPrettyPrinting()`, two-space
 * indent). Escaping is Gson's with HTML escaping off: quotes, backslash, control characters and
 * U+2028/U+2029.
 */
object JsonText {
    fun compact(v: JsonValue): String = StringBuilder().also { write(it, v, null, 0) }.toString()

    fun pretty(v: JsonValue): String = StringBuilder().also { write(it, v, "  ", 0) }.toString()

    private fun write(out: StringBuilder, v: JsonValue, indent: String?, depth: Int) {
        when (v) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(v.value)
            is JsonValue.Num -> out.append(v.text)
            is JsonValue.Str -> quote(out, v.value)
            is JsonValue.Arr -> container(out, '[', ']', v.items.map { null to it }, indent, depth)
            is JsonValue.Obj -> container(out, '{', '}', v.fields.entries.map { it.key to it.value }, indent, depth)
        }
    }

    private fun container(
        out: StringBuilder,
        open: Char,
        close: Char,
        entries: List<Pair<String?, JsonValue>>,
        indent: String?,
        depth: Int,
    ) {
        out.append(open)
        if (entries.isEmpty()) { out.append(close); return }
        entries.forEachIndexed { n, (key, value) ->
            if (n > 0) out.append(',')
            if (indent != null) { out.append('\n'); repeat(depth + 1) { out.append(indent) } }
            if (key != null) {
                quote(out, key)
                out.append(if (indent != null) ": " else ":")
            }
            write(out, value, indent, depth + 1)
        }
        if (indent != null) { out.append('\n'); repeat(depth) { out.append(indent) } }
        out.append(close)
    }

    /** A quoted string, escaped exactly as Gson's JsonWriter does with HTML escaping off. */
    fun quote(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\u000C' -> out.append("\\f")
                ' ' -> out.append("\\u2028")
                ' ' -> out.append("\\u2029")
                else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else out.append(ch)
            }
        }
        out.append('"')
    }
}
