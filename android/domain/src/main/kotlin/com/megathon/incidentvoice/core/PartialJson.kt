package com.megathon.incidentvoice.core

/** Tolerant JSON helpers for LLM output (PRD §13.6 steps 1–2). */
object PartialJson {

    /** Strip code fences; return text from the first `{`. Null if there is none. */
    fun fromFirstBrace(raw: String): String? {
        var s = raw.trim()
        s = s.replace(Regex("^```[a-zA-Z]*\\s*"), "").replace(Regex("```\\s*$"), "")
        val i = s.indexOf('{')
        return if (i < 0) null else s.substring(i)
    }

    /**
     * Take the first balanced object; if the text is cut off, auto-close open strings/arrays/objects
     * (dropping a dangling key or comma). Returns null if no object start exists.
     */
    fun extractObject(raw: String): String? {
        val s = fromFirstBrace(raw) ?: return null
        val scan = scan(s)
        if (scan.balancedEnd >= 0) return s.substring(0, scan.balancedEnd + 1)
        return autoClose(s, scan)
    }

    private class Scan(val balancedEnd: Int, val stackAtEnd: List<Char>, val inString: Boolean, val commas: List<Pair<Int, List<Char>>>)

    private fun scan(s: String): Scan {
        val stack = ArrayList<Char>()
        var inStr = false; var quote = '"'; var esc = false
        val commas = ArrayList<Pair<Int, List<Char>>>()
        for ((i, c) in s.withIndex()) {
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == quote) inStr = false
                continue
            }
            when (c) {
                '"', '\'' -> { inStr = true; quote = c }
                '{' -> stack += '}'
                '[' -> stack += ']'
                '}', ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); if (stack.isEmpty()) return Scan(i, emptyList(), false, commas) }
                ',' -> commas += i to stack.toList()
            }
        }
        return Scan(-1, stack.toList(), inStr, commas)
    }

    private fun autoClose(s: String, sc: Scan? = null): String {
        val scan = sc ?: scan(s)
        var body = s
        if (scan.inString) body += "\"" // close the string; dangling key/value handled below
        val candidate = stripDangling(body) + closers(stripDangling(body))
        return if (LenientParse.tryParse(candidate)) candidate else {
            // fall back: cut at the last comma outside strings and close from that stack
            val cut = scan.commas.lastOrNull()
            if (cut != null) s.substring(0, cut.first) + cut.second.reversed().joinToString("") else candidate
        }
    }

    private fun closers(s: String): String = scan(s).stackAtEnd.reversed().joinToString("")

    private fun stripDangling(s: String): String {
        var t = s.trimEnd()
        // trailing comma or colon (key without value)
        while (t.endsWith(",")) t = t.dropLast(1).trimEnd()
        if (t.endsWith(":")) {
            // remove the dangling key
            val q2 = t.dropLast(1).trimEnd()
            if (q2.endsWith("\"")) {
                val q1 = q2.dropLast(1).lastIndexOf('"')
                if (q1 >= 0) t = q2.substring(0, q1).trimEnd().trimEnd(',')
            }
        }
        return t
    }

    /** §13.6 step 2: trailing commas, single quotes, Python literals, unquoted keys/enum values. */
    fun lenientFix(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> {
                    val q = c
                    out.append('"'); i++
                    while (i < s.length && s[i] != q) {
                        if (s[i] == '\\' && i + 1 < s.length) { out.append(s[i]).append(s[i + 1]); i += 2; continue }
                        if (s[i] == '"') out.append("\\\"") else out.append(s[i])
                        i++
                    }
                    out.append('"'); i++
                }
                c.isLetter() || c == '_' -> {
                    val st = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.' || s[i] == '-')) i++
                    val word = s.substring(st, i)
                    var j = i; while (j < s.length && s[j].isWhitespace()) j++
                    val isKey = j < s.length && s[j] == ':'
                    when {
                        isKey -> out.append('"').append(word).append('"')
                        word == "true" || word == "false" || word == "null" -> out.append(word)
                        word == "True" -> out.append("true")
                        word == "False" -> out.append("false")
                        word == "None" -> out.append("null")
                        else -> out.append('"').append(word).append('"')
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString().replace(Regex(",\\s*([}\\]])"), "$1")
    }
}

internal object LenientParse {
    private val json = kotlinx.serialization.json.Json { isLenient = true; ignoreUnknownKeys = true }
    fun tryParse(s: String): Boolean = try { json.parseToJsonElement(PartialJson.lenientFix(s)); true } catch (e: Exception) { false }
    fun parse(s: String): kotlinx.serialization.json.JsonElement? = try { json.parseToJsonElement(PartialJson.lenientFix(s)) } catch (e: Exception) { null }
}
