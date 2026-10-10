package com.megathon.incidentvoice.core.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JSON recovery for LLM output (PRD §13.6 steps 1–3):
 *  - [extractObject]: strip code fences, take the first `{` to its matching `}` (string-aware, both quote styles);
 *    if the output was truncated, auto-close it ([autoClose]).
 *  - [lenientNormalize]: single quotes → double quotes, Python literals, unquoted keys/enum values, trailing commas,
 *    missing commas between members.
 *  - [parseObject]: all of the above, then kotlinx parse.
 * Also used every few tokens while streaming ([parseObject] with auto-close).
 */
object PartialJson {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    data class Extracted(val text: String, val truncated: Boolean)

    fun stripFences(s: String): String {
        var t = s.replace("<|python_tag|>", "").replace("<|eot_id|>", "").replace("<|eom_id|>", "")
        // ```json ... ``` (possibly unterminated)
        t = t.replace(Regex("""```[a-zA-Z]*"""), "")
        return t
    }

    /** First balanced `{...}` (or the truncated tail, auto-closed). Null if no `{`. */
    fun extractObject(raw: String): Extracted? {
        val s = stripFences(raw)
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var quote: Char? = null
        var escape = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == quote) quote = null
            } else {
                when (c) {
                    '"' -> quote = '"'
                    '\'' -> if (isQuoteStart(s, i)) quote = '\''
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return Extracted(s.substring(start, i + 1), false)
                    }
                }
            }
            i++
        }
        return Extracted(autoClose(s.substring(start)), true)
    }

    /** A single quote opens a string only at a value/key position (not an apostrophe inside a bare word). */
    private fun isQuoteStart(s: String, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && s[j].isWhitespace()) j--
        return j < 0 || s[j] in "{[,:"
    }

    private enum class Ctx { OBJ, ARR }

    /**
     * Closes a truncated JSON prefix: closes an open value string, drops a dangling key / colon / comma / partial
     * literal, and appends the missing `]`/`}`.
     */
    fun autoClose(prefix: String): String {
        val stack = ArrayDeque<Ctx>()
        // Per open container: index where the current member/element started (for cutting a dangling key).
        val memberStart = ArrayDeque<Int>()
        // For objects: true while we expect a key (after '{' or ',').
        val expectKey = ArrayDeque<Boolean>()
        var quote: Char? = null
        var escape = false
        var stringStart = -1
        var stringIsKey = false
        var i = 0
        val s = prefix
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == quote) quote = null
                i++; continue
            }
            when (c) {
                '"', '\'' -> if (c == '"' || isQuoteStart(s, i)) {
                    quote = c; stringStart = i
                    stringIsKey = stack.lastOrNull() == Ctx.OBJ && expectKey.lastOrNull() == true
                }
                '{' -> { stack.addLast(Ctx.OBJ); expectKey.addLast(true); memberStart.addLast(i + 1) }
                '[' -> { stack.addLast(Ctx.ARR); expectKey.addLast(false); memberStart.addLast(i + 1) }
                '}', ']' -> {
                    if (stack.isNotEmpty()) { stack.removeLast(); expectKey.removeLast(); memberStart.removeLast() }
                }
                ':' -> if (expectKey.isNotEmpty()) { expectKey.removeLast(); expectKey.addLast(false) }
                ',' -> if (stack.isNotEmpty()) {
                    expectKey.removeLast(); expectKey.addLast(stack.last() == Ctx.OBJ)
                    memberStart.removeLast(); memberStart.addLast(i + 1)
                }
            }
            i++
        }
        if (stack.isEmpty()) return s
        var out = s
        if (quote != null) {
            if (stringIsKey) {
                out = out.substring(0, stringStart)
            } else {
                if (escape) out = out.dropLast(1)
                out += quote
            }
        }
        // Clean the tail: dangling comma, colon (drop its key), bare key, partial literal.
        out = cleanTail(out, stack.lastOrNull(), memberStart.lastOrNull() ?: out.length)
        val sb = StringBuilder(out)
        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == Ctx.OBJ) '}' else ']')
        return sb.toString()
    }

    private fun cleanTail(input: String, ctx: Ctx?, memberStartIdx: Int): String {
        var out = input.trimEnd()
        if (out.endsWith(",")) return out.dropLast(1).trimEnd()
        if (ctx == Ctx.OBJ) {
            val member = if (memberStartIdx <= out.length) out.substring(memberStartIdx).trim() else ""
            if (member.isEmpty()) return out
            // Member is complete only if it looks like  key : value  with a complete value.
            val colon = topLevelColon(member)
            if (colon < 0 || member.substring(colon + 1).isBlank() || isPartialLiteral(member.substring(colon + 1).trim())) {
                out = out.substring(0, memberStartIdx).trimEnd()
                if (out.endsWith(",")) out = out.dropLast(1).trimEnd()
                return out
            }
            return out.trimEnd()
        }
        if (ctx == Ctx.ARR) {
            val member = if (memberStartIdx <= out.length) out.substring(memberStartIdx).trim() else ""
            if (member.isNotEmpty() && isPartialLiteral(member)) {
                out = out.substring(0, memberStartIdx).trimEnd()
                if (out.endsWith(",")) out = out.dropLast(1).trimEnd()
            }
        }
        return out
    }

    private fun topLevelColon(member: String): Int {
        var quote: Char? = null
        var escape = false
        for ((i, c) in member.withIndex()) {
            if (quote != null) {
                if (escape) escape = false else if (c == '\\') escape = true else if (c == quote) quote = null
                continue
            }
            if (c == '"' || c == '\'') quote = c
            if (c == ':') return i
            if (c == '{' || c == '[') return -1
        }
        return -1
    }

    /** `tru`, `fals`, `nul`, `Tr`, `12.` … (incomplete literal at the end of a truncated stream). */
    private fun isPartialLiteral(v: String): Boolean {
        if (v.isEmpty()) return true
        val c = v[0]
        if (c == '"' || c == '\'' || c == '{' || c == '[') return false
        val lit = v.lowercase()
        val full = setOf("true", "false", "null", "none")
        if (lit in full) return false
        if (listOf("true", "false", "null", "none").any { it.startsWith(lit) }) return true
        if (lit.endsWith(".") || lit.endsWith("-")) return true
        return false
    }

    /**
     * Rewrites relaxed JSON into strict JSON: 'single' → "double" quotes, True/False/None → true/false/null,
     * bare words (unquoted keys or enum values, may contain spaces) quoted, trailing commas removed,
     * missing commas inserted.
     */
    fun lenientNormalize(input: String): String {
        val sb = StringBuilder()
        var i = 0
        val s = input
        // last significant emitted token kind: 'v' = value end, 'k' = colon, 'o' = open, ',' = comma
        var last = 'o'
        fun emitValueStart() {
            if (last == 'v') sb.append(',')
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> { i++ }
                c == '"' || c == '\'' -> {
                    emitValueStart()
                    val (str, next) = readString(s, i, c)
                    sb.append(str); i = next; last = 'v'
                }
                c == '{' || c == '[' -> { emitValueStart(); sb.append(c); i++; last = 'o' }
                c == '}' || c == ']' -> {
                    // drop trailing comma
                    while (sb.isNotEmpty() && (sb.last() == ',' || sb.last().isWhitespace())) sb.setLength(sb.length - 1)
                    sb.append(c); i++; last = 'v'
                }
                c == ':' -> { sb.append(':'); i++; last = 'k' }
                c == ',' -> {
                    if (last != ',' && last != 'o') sb.append(',')
                    i++; last = ','
                }
                else -> {
                    // bare word up to a structural char
                    var j = i
                    while (j < s.length && s[j] !in ",:{}[]\"\n") j++
                    // a single quote inside a bare word is an apostrophe, keep it
                    val word = s.substring(i, j).trim()
                    i = j
                    if (word.isEmpty()) continue
                    emitValueStart()
                    sb.append(bareToJson(word)); last = 'v'
                }
            }
        }
        return sb.toString()
    }

    private fun bareToJson(word: String): String = when {
        word == "true" || word == "false" || word == "null" -> word
        word == "True" || word == "TRUE" -> "true"
        word == "False" || word == "FALSE" -> "false"
        word == "None" || word == "NULL" || word == "Null" || word == "undefined" -> "null"
        Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""").matches(word) -> word
        else -> quote(word)
    }

    /** Reads a quoted string starting at [start] with [q]; returns a strict JSON string literal and the next index. */
    private fun readString(s: String, start: Int, q: Char): Pair<String, Int> {
        val sb = StringBuilder("\"")
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                if (n == '\'') sb.append('\'') else { sb.append('\\'); sb.append(n) }
                i += 2; continue
            }
            if (c == q) {
                // For single-quoted strings, an apostrophe followed by a letter is part of the word ("it's").
                if (q == '\'' && i + 1 < s.length && s[i + 1].isLetter() && i > start + 1 && s[i - 1].isLetter()) {
                    sb.append('\''); i++; continue
                }
                sb.append('"'); return sb.toString() to i + 1
            }
            when (c) {
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
        return sb.toString() to i
    }

    private fun quote(w: String): String = buildString {
        append('"')
        for (c in w) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(c)
        }
        append('"')
    }

    /** Full recovery pipeline. Returns the object and whether it was truncated, or null if unrecoverable. */
    fun parseObject(raw: String): Pair<JsonObject, Boolean>? {
        val ex = extractObject(raw) ?: return null
        val strict = runCatching { json.parseToJsonElement(ex.text) }.getOrNull()
        if (strict is JsonObject) return strict to ex.truncated
        val fixed = lenientNormalize(ex.text)
        val el: JsonElement = runCatching { json.parseToJsonElement(fixed) }.getOrNull()
            ?: runCatching { json.parseToJsonElement(lenientNormalize(autoClose(fixed))) }.getOrNull()
            ?: return null
        return (el as? JsonObject)?.let { it to ex.truncated }
    }
}
