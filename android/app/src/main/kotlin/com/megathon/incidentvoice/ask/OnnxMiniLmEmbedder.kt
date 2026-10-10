package com.megathon.incidentvoice.ask

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer

/**
 * all-MiniLM-L6-v2 (quantized, 23 MB, 384-d) on ONNX Runtime CPU: the embedding model behind the vector index.
 * Bundled in the APK (`assets/models/minilm`), so it works fully offline. One sentence takes a few ms on the board.
 */
class OnnxMiniLmEmbedder(modelBytes: ByteArray, vocabLines: List<String>, threads: Int = 2) : Embedder {
    override val modelId = "all-MiniLM-L6-v2-q8"
    override val dim = 384
    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(modelBytes, OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads); setInterOpNumThreads(1) })
    private val tokenizer = WordPieceTokenizer(vocabLines, maxLen = 128)
    private val hasTypeIds = "token_type_ids" in session.inputNames

    @Synchronized
    override fun embed(text: String): FloatArray {
        val e = tokenizer.encode(text)
        val shape = longArrayOf(1, e.ids.size.toLong())
        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(e.ids), shape)
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(e.mask), shape)
        val types = OnnxTensor.createTensor(env, LongBuffer.wrap(e.types), shape)
        try {
            val inputs = HashMap<String, OnnxTensor>().apply { put("input_ids", ids); put("attention_mask", mask); if (hasTypeIds) put("token_type_ids", types) }
            session.run(inputs).use { out ->
                @Suppress("UNCHECKED_CAST")
                val hidden = (out[0].value as Array<Array<FloatArray>>)[0]       // [tokens][384]
                return Vectors.meanPool(hidden, e.mask)
            }
        } finally { ids.close(); mask.close(); types.close() }
    }

    companion object {
        /** Null when the model asset is missing or ORT fails to load: the Ask tab then uses keyword search only. */
        fun fromAssets(context: Context): OnnxMiniLmEmbedder? = try {
            val model = context.assets.open("models/minilm/model_quantized.onnx").use { it.readBytes() }
            val vocab = context.assets.open("models/minilm/vocab.txt").bufferedReader().use { it.readLines() }
            OnnxMiniLmEmbedder(model, vocab)
        } catch (t: Throwable) { android.util.Log.e("IV", "embedding model unavailable: ${t.message}"); null }
    }
}
