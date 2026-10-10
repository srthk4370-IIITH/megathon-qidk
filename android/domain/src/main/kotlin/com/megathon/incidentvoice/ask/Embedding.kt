package com.megathon.incidentvoice.ask

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import kotlin.math.sqrt

/** Text → unit-length vector. The Android implementation runs all-MiniLM-L6-v2 on ONNX Runtime (CPU, offline). */
interface Embedder {
    val modelId: String
    val dim: Int
    fun embed(text: String): FloatArray
}

/** One [input_ids, attention_mask, token_type_ids] triple (BERT single sentence). */
class Encoded(val ids: LongArray, val mask: LongArray, val types: LongArray)

/**
 * BERT uncased WordPiece tokenizer (`BertTokenizer`, do_lower_case = true), written out so the app needs no
 * tokenizer library: clean → lower-case → strip accents → split on whitespace/punctuation → greedy longest-match
 * word pieces with the `##` continuation prefix. Matches the reference tokenizer on plain English text.
 */
class WordPieceTokenizer(vocabLines: List<String>, private val maxLen: Int = 128) {
    private val vocab: Map<String, Int> = HashMap<String, Int>(vocabLines.size * 2).also { m -> vocabLines.forEachIndexed { i, t -> if (t.isNotEmpty()) m[t] = i } }
    private val unk = vocab["[UNK]"] ?: 100
    private val cls = vocab["[CLS]"] ?: 101
    private val sep = vocab["[SEP]"] ?: 102

    private fun isPunct(c: Char): Boolean {
        val cp = c.code
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    fun basicTokens(text: String): List<String> {
        val norm = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val out = ArrayList<String>(); val cur = StringBuilder()
        fun flush() { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) } }
        for (c in norm) {
            when {
                c.code == 0 || c.code == 0xFFFD || Character.isISOControl(c) && !c.isWhitespace() -> {}
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> {}            // stripped accent
                c.isWhitespace() -> flush()
                isPunct(c) -> { flush(); out += c.toString() }
                else -> cur.append(c)
            }
        }
        flush()
        return out
    }

    fun wordPieces(word: String): List<Int> {
        if (word.length > 100) return listOf(unk)
        val out = ArrayList<Int>(); var start = 0
        while (start < word.length) {
            var end = word.length; var id = -1
            while (start < end) {
                val piece = (if (start > 0) "##" else "") + word.substring(start, end)
                val v = vocab[piece]
                if (v != null) { id = v; break }
                end--
            }
            if (id < 0) return listOf(unk)
            out += id; start = end
        }
        return out
    }

    fun encode(text: String): Encoded {
        val ids = ArrayList<Long>(); ids += cls.toLong()
        loop@ for (w in basicTokens(text)) for (p in wordPieces(w)) { if (ids.size >= maxLen - 1) break@loop; ids += p.toLong() }
        ids += sep.toLong()
        return Encoded(ids.toLongArray(), LongArray(ids.size) { 1 }, LongArray(ids.size))
    }
}

object Vectors {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0; for (x in v) s += x * x
        val n = sqrt(s).toFloat().coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / n }
    }

    /** Dot product; equals cosine similarity for unit vectors. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f; for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Mean of token vectors (attention-masked), then L2-normalised: the sentence-transformers pooling for MiniLM. */
    fun meanPool(tokens: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = tokens.firstOrNull()?.size ?: 0
        val out = FloatArray(dim); var n = 0
        for (t in tokens.indices) if (mask[t] != 0L) { n++; for (d in 0 until dim) out[d] += tokens[t][d] }
        if (n > 0) for (d in 0 until dim) out[d] /= n
        return normalize(out)
    }

    fun toBytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()
    fun fromBytes(b: ByteArray): FloatArray = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).let { bb -> FloatArray(b.size / 4) { bb.getFloat() } }
}
