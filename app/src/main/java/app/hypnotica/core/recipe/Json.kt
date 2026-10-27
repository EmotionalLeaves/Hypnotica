package app.hypnotica.core.recipe

internal class JsonError(message: String) : Exception(message)

/**
 * Just enough JSON to read what the app itself writes: a run from the Code tab,
 * a Studio project from preferences.
 *
 * Deliberately not a dependency and deliberately not org.json: this file is one
 * of the few the local type-checker and test harness can reach, and a parser
 * that can only be compiled by CI is a parser that gets tested once a round.
 * Objects come back as maps, arrays as lists, whole numbers as Longs and the
 * rest as Doubles. Whole numbers are kept exact because a seed is a 64-bit
 * value, a double holds 53 bits of it, and a seed that comes back changed is a
 * different run.
 */
internal class Json(private val s: String) {

    private var i = 0

    fun parse(): Any? {
        val v = value(0)
        ws()
        if (i < s.length) throw JsonError("There is something after the closing brace.")
        return v
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw JsonError("That is nested far too deeply to be a run.")
        ws()
        if (i >= s.length) throw JsonError("That is not a run — it stops too early.")
        return when (val c = s[i]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else ->
                if (c == '-' || c in '0'..'9') number()
                else throw JsonError("That is not a run: unexpected '$c'.")
        }
    }

    private fun obj(depth: Int): Map<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        ws()
        if (peek() == '}') { i++; return out }
        while (true) {
            ws()
            if (peek() != '"') throw JsonError("A name inside { } has to be in quotes.")
            val key = string()
            ws()
            expect(':')
            out[key] = value(depth + 1)
            ws()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return out }
                else -> throw JsonError("Expected a comma or a } after \"$key\".")
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        ws()
        if (peek() == ']') { i++; return out }
        while (true) {
            out.add(value(depth + 1))
            ws()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return out }
                else -> throw JsonError("Expected a comma or a ] inside a list.")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw JsonError("A quoted name or value is never closed.")
            when (val c = s[i++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) throw JsonError("A quoted value ends in a backslash.")
                    when (val e = s[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) throw JsonError("A \\u escape is cut short.")
                            val hex = s.substring(i, i + 4)
                            val code = hex.toIntOrNull(16)
                                ?: throw JsonError("\\u$hex is not a character code.")
                            sb.append(code.toChar())
                            i += 4
                        }
                        else -> throw JsonError("\\$e is not an escape.")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        var whole = true
        if (i < s.length && s[i] == '.') {
            whole = false
            i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            whole = false
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        val raw = s.substring(start, i)
        if (whole) raw.toLongOrNull()?.let {
            // "-0" is negative zero, which a Long cannot hold: as a whole number
            // it would come back as plain 0 and the sign the writer kept would
            // be lost on the way in.
            return if (it == 0L && raw.startsWith("-")) -0.0 else it
        }
        return raw.toDoubleOrNull() ?: throw JsonError("\"$raw\" is not a number.")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!s.startsWith(word, i)) throw JsonError("That is not a run: unexpected '${s[i]}'.")
        i += word.length
        return value
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun expect(c: Char) {
        ws()
        if (i >= s.length || s[i] != c) throw JsonError("Expected a '$c' and did not find one.")
        i++
    }

    private fun ws() {
        while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
    }

    private companion object {
        /** A run is three levels deep; this is only here so a bad paste cannot recurse away. */
        const val MAX_DEPTH = 32
    }
}
