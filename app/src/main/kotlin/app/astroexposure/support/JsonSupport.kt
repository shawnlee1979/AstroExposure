package app.astroexposure.support

/**
 * Writing JSON text by hand (DEVELOPMENT-RULES C.2: no third-party
 * JSON library), shared by the library export and the saved-plan
 * payloads: [obj] renders `"key": value` pairs joined by ", " inside
 * braces, [quoted] renders a string literal with the escapes the
 * app's own reader accepts, and [num] renders a number —
 * integer-valued doubles as integers, everything else in the
 * double's own text. Payloads built with these helpers are stored
 * and exported as opaque text; the app never parses them back.
 */
fun obj(vararg fields: Pair<String, String>): String =
    fields.joinToString(", ", "{", "}") { (k, v) -> "${quoted(k)}: $v" }

fun num(v: Double): String {
    // NaN / Infinity are not legal JSON numbers: refuse the export
    // loudly rather than writing a backup that can never be re-imported.
    require(v.isFinite()) { "non-finite value cannot be exported as JSON: $v" }
    return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}

fun num(v: Int): String = v.toString()

fun optNum(v: Double?): String = v?.let { num(it) } ?: "null"

fun optInt(v: Int?): String = v?.toString() ?: "null"

fun quoted(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.append('"').toString()
}
