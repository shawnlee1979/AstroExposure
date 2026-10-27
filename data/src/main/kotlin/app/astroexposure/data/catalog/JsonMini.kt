package app.astroexposure.data.catalog

/**
 * A minimal JSON parser for the bundled catalogue resources — written
 * in-house so the catalogue adapters need no new third-party dependency
 * (see docs/adr/0003-catalog-ports-and-resources.md).
 *
 * Supports the full JSON data model: objects, arrays, strings with all
 * standard escapes (including \uXXXX), numbers, booleans and null.
 * Any malformed input throws [JsonParseException] carrying the character
 * position of the failure; nothing is recovered or guessed.
 */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/** JSON syntax failure; [position] is the character index into the input. */
class JsonParseException(message: String, val position: Int) :
    IllegalArgumentException("$message (at position $position)")

object JsonMini {

    /**
     * Maximum nesting depth for arrays/objects. Bundled catalogues nest a
     * handful of levels; imported documents are user-supplied, and an
     * unbounded recursive-descent parse would end in a StackOverflowError
     * instead of a catchable [JsonParseException].
     */
    const val MAX_DEPTH = 128

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.parseValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw parser.error("trailing content after the top-level value")
        return value
    }

    private class Parser(private val text: String) {
        var pos = 0
            private set

        fun atEnd(): Boolean = pos >= text.length

        fun error(message: String): JsonParseException = JsonParseException(message, pos)

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\n\r") pos++
        }

        private fun peek(): Char =
            if (pos < text.length) text[pos] else throw error("unexpected end of input")

        private fun expect(c: Char) {
            if (peek() != c) throw error("expected '$c' but found '${text[pos]}'")
            pos++
        }

        fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) {
                throw error("nesting deeper than $MAX_DEPTH levels")
            }
            return when (val c = peek()) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> JsonValue.Str(parseString())
                't' -> parseLiteral("true", JsonValue.Bool(true))
                'f' -> parseLiteral("false", JsonValue.Bool(false))
                'n' -> parseLiteral("null", JsonValue.Null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw error("unexpected character '$c'")
            }
        }

        private fun parseLiteral(word: String, value: JsonValue): JsonValue {
            if (!text.regionMatches(pos, word, 0, word.length)) {
                throw error("invalid literal, expected '$word'")
            }
            pos += word.length
            return value
        }

        private fun parseObject(depth: Int): JsonValue.Obj {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw error("expected a string object key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                // A duplicate key means the document itself is corrupt
                // (typically a generator bug); fail loudly instead of
                // silently keeping the later value.
                if (entries.containsKey(key)) {
                    throw error("duplicate object key '$key'")
                }
                entries[key] = parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.Obj(entries)
                    }
                    else -> throw error("expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(depth: Int): JsonValue.Arr {
            expect('[')
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += parseValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.Arr(items)
                    }
                    else -> throw error("expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) throw error("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) throw error("unterminated escape sequence")
                        when (val e = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw error("truncated \\u escape")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: throw error("invalid \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw error("invalid escape character '$e'")
                        }
                    }
                    else -> {
                        if (c < ' ') throw error("unescaped control character in string")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            // Integer part: a single 0, or a non-zero digit followed by digits.
            when {
                pos < text.length && text[pos] == '0' -> pos++
                pos < text.length && text[pos] in '1'..'9' -> {
                    while (pos < text.length && text[pos].isDigit()) pos++
                }
                else -> throw error("invalid number")
            }
            if (pos < text.length && text[pos] == '.') {
                pos++
                if (pos >= text.length || !text[pos].isDigit()) {
                    throw error("invalid number: digit required after '.'")
                }
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                if (pos >= text.length || !text[pos].isDigit()) {
                    throw error("invalid number: digit required in exponent")
                }
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw error("invalid number '$raw'")
            return JsonValue.Num(value)
        }
    }
}

// --- Typed access helpers for catalogue documents -------------------------
// These fail loudly (IllegalStateException naming the field) when a bundled
// document does not match the expected schema: a corrupt data product must
// never degrade into silently empty catalogues (DEVELOPMENT-RULES.md E.5).

internal fun JsonValue.asObject(context: String): Map<String, JsonValue> =
    (this as? JsonValue.Obj)?.entries
        ?: throw IllegalStateException("$context: expected a JSON object")

internal fun JsonValue.asArray(context: String): List<JsonValue> =
    (this as? JsonValue.Arr)?.items
        ?: throw IllegalStateException("$context: expected a JSON array")

internal fun Map<String, JsonValue>.required(key: String, context: String): JsonValue =
    this[key] ?: throw IllegalStateException("$context: missing required field '$key'")

internal fun Map<String, JsonValue>.stringField(key: String, context: String): String =
    (required(key, context) as? JsonValue.Str)?.value
        ?: throw IllegalStateException("$context: field '$key' must be a string")

internal fun Map<String, JsonValue>.doubleField(key: String, context: String): Double =
    (required(key, context) as? JsonValue.Num)?.value
        ?: throw IllegalStateException("$context: field '$key' must be a number")

internal fun Map<String, JsonValue>.intField(key: String, context: String): Int {
    val value = doubleField(key, context)
    if (value % 1.0 != 0.0) {
        throw IllegalStateException("$context: field '$key' must be an integer, was $value")
    }
    // Double.toInt() saturates silently outside the Int range; a corrupt
    // catalogue field must fail loudly instead of becoming 2147483647.
    if (value > Int.MAX_VALUE.toDouble() || value < Int.MIN_VALUE.toDouble()) {
        throw IllegalStateException("$context: field '$key' is out of Int range, was $value")
    }
    return value.toInt()
}

internal fun Map<String, JsonValue>.optionalString(key: String, context: String): String? =
    when (val v = this[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> v.value
        else -> throw IllegalStateException("$context: field '$key' must be a string or null")
    }

internal fun Map<String, JsonValue>.optionalDouble(key: String, context: String): Double? =
    when (val v = this[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> v.value
        else -> throw IllegalStateException("$context: field '$key' must be a number or null")
    }

internal fun Map<String, JsonValue>.optionalInt(key: String, context: String): Int? =
    optionalDouble(key, context)?.let { value ->
        if (value % 1.0 != 0.0) {
            throw IllegalStateException("$context: field '$key' must be an integer, was $value")
        }
        if (value > Int.MAX_VALUE.toDouble() || value < Int.MIN_VALUE.toDouble()) {
            throw IllegalStateException("$context: field '$key' is out of Int range, was $value")
        }
        value.toInt()
    }

internal fun Map<String, JsonValue>.optionalBoolean(key: String, context: String): Boolean? =
    when (val v = this[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Bool -> v.value
        else -> throw IllegalStateException("$context: field '$key' must be a boolean or null")
    }
