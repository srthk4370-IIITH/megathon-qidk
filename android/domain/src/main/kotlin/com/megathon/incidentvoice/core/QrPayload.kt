package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.data.IncidentDetail
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Incident time: ISO-8601 with offset, and whether it is approximate ("around 10"). */
@Serializable
data class QrTime(val t: String, val approx: Boolean = false)

/**
 * One report as it travels in the offline QR code (schema version 1). This is the contract with web/reader.html:
 * short keys, values already readable (labels, not codes), `crc` always last. See [QrCodec].
 */
@Serializable
data class QrReport(
    val v: Int = 1,
    /** Reference number (INC-YYYYMMDD-NNNN), or the internal id of a report that has none. */
    val id: String,
    /** When the report was created, ISO-8601 with offset. */
    val ts: String,
    val type: String,
    val sev: String = "",
    val loc: String = "",
    val at: QrTime? = null,
    val inj: String = "",
    val per: List<String> = emptyList(),
    val eq: List<String> = emptyList(),
    val act: List<String> = emptyList(),
    val wit: List<String> = emptyList(),
    val desc: String = "",
    /** Category-specific details: label → value. */
    val det: Map<String, String> = emptyMap(),
    /** SOP next steps, in order. */
    val sop: List<String> = emptyList(),
    /** Repeat / hotspot information, as sentences. */
    val tags: List<String> = emptyList(),
    /** Engine / model that filled the form. */
    val eng: String = "",
    /** Transcript; the first thing dropped when the code would get too big. */
    val tx: String? = null,
    /** CRC32 (8 lower-case hex digits) of the JSON text with this field empty. Detects transmission errors; it is not a signature. */
    val crc: String = "",
)

/**
 * `QALAM1:` + base64url (no padding) of raw DEFLATE (no zlib header) of the compact UTF-8 JSON of a [QrReport].
 *
 * The JSON ends with `"crc":"xxxxxxxx"}`. The checksum is CRC32 over the UTF-8 bytes of the same text with that tail
 * replaced by `"crc":""}`, so a reader verifies it on the text it received and never has to re-serialise.
 */
@OptIn(ExperimentalSerializationApi::class)
object QrCodec {
    const val PREFIX = "QALAM1:"
    /** Target size of the compressed payload; keeps the code easy to photograph at error-correction level M. */
    const val MAX_BYTES = 1200
    private const val EMPTY_TAIL = "\"crc\":\"\"}"
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    class Encoded(val text: String, val compressedBytes: Int, val report: QrReport, val droppedTranscript: Boolean, val truncatedDescription: Boolean)
    class Decoded(val report: QrReport, val crcOk: Boolean, val json: String)

    private fun crcOf(emptyCrcJson: String): String = CRC32().apply { update(emptyCrcJson.toByteArray(Charsets.UTF_8)) }.value.toString(16).padStart(8, '0')

    /** Compact JSON with the checksum filled in. */
    fun toJson(r: QrReport): String {
        val empty = json.encodeToString(QrReport.serializer(), r.copy(crc = ""))
        check(empty.endsWith(EMPTY_TAIL))
        return empty.dropLast(EMPTY_TAIL.length) + "\"crc\":\"${crcOf(empty)}\"}"
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(9, true)
        d.setInput(bytes); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray {
        val i = Inflater(true)
        i.setInput(bytes)
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!i.finished()) {
            val n = i.inflate(buf)
            if (n == 0 && (i.needsInput() || i.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() <= 1 shl 20) { "payload too large" }
        }
        i.end()
        return out.toByteArray()
    }

    private fun packed(r: QrReport): ByteArray = deflate(toJson(r).toByteArray(Charsets.UTF_8))

    /** Too big for [maxBytes]: the transcript goes first, then the description is cut; as a last resort the details and the later SOP steps. */
    fun encode(report: QrReport, maxBytes: Int = MAX_BYTES): Encoded {
        var r = report.copy(crc = "")
        var bytes = packed(r)
        var dropped = false; var cut = false
        if (bytes.size > maxBytes && r.tx != null) { r = r.copy(tx = null); bytes = packed(r); dropped = true }
        if (bytes.size > maxBytes && r.desc.isNotEmpty()) {
            val full = r.desc
            var lo = 0; var hi = full.length - 1               // longest prefix that fits
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (packed(r.copy(desc = full.take(mid).trimEnd() + "…")).size <= maxBytes) lo = mid else hi = mid - 1
            }
            r = r.copy(desc = if (lo == 0) "" else full.take(lo).trimEnd() + "…"); bytes = packed(r); cut = true
        }
        if (bytes.size > maxBytes && r.sop.size > 3) { r = r.copy(sop = r.sop.take(3)); bytes = packed(r) }
        if (bytes.size > maxBytes && r.det.isNotEmpty()) { r = r.copy(det = emptyMap()); bytes = packed(r) }
        val text = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return Encoded(text, bytes.size, r.copy(crc = toJson(r).takeLast(10).take(8)), dropped, cut)
    }

    /** @throws IllegalArgumentException when [text] is not a Qalam code at all. A damaged one decodes with [Decoded.crcOk] false if it still parses. */
    fun decode(text: String): Decoded {
        val t = text.trim()
        require(t.startsWith(PREFIX)) { "not a Qalam code" }
        val raw = try { String(inflate(Base64.getUrlDecoder().decode(t.substring(PREFIX.length))), Charsets.UTF_8) } catch (e: Exception) { throw IllegalArgumentException("damaged code", e) }
        val report = try { json.decodeFromString(QrReport.serializer(), raw) } catch (e: Exception) { throw IllegalArgumentException("damaged code", e) }
        val tail = "\"crc\":\"${report.crc}\"}"
        val ok = report.crc.length == 8 && raw.endsWith(tail) && crcOf(raw.dropLast(tail.length) + EMPTY_TAIL) == report.crc
        return Decoded(report, ok, raw)
    }
}

/** Saved report → what goes into the QR code. Everything is turned into readable text here, so the reader needs no taxonomy. */
object QrReportBuilder {
    private val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun build(
        d: IncidentDetail, tx: Taxonomy, registry: SiteRegistry, tags: ReportTags?, engine: String, zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    ): QrReport {
        val f = d.form
        fun time(ms: Long) = iso.format(Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).atZone(zone))
        fun pretty(key: String, raw: String): String = when (tx.field(key)?.type) {
            "enum_category", "enum_category_list" -> tx.category(raw)?.label ?: raw
            "enum" -> raw.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
            else -> raw
        }
        fun text(key: String): String? {
            val st = f.state(key) ?: return null
            if (st.status == FieldStatus.UNKNOWN) return "Unknown"
            return when (val v = st.value) {
                null -> null
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ") { pretty(key, it) }.ifBlank { null }
                is JsonPrimitive -> v.booleanOrNull?.takeIf { !v.isString }?.let { if (it) "Yes" else "No" } ?: v.contentOrNull?.let { pretty(key, it) }
                else -> v.toString()
            }
        }
        fun people(key: String): List<String> = (f.value(key) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o ->
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            buildString {
                append(s("name") ?: "Unnamed")
                s("role")?.takeIf { key == "persons" }?.let { append(" (${it.lowercase()})") }
                s("injury")?.let { append(": $it") }
                s("contact")?.let { append(", $it") }
            }
        }
        val site = registry.locations.firstOrNull { it.id == f.locationId }
        val said = f.str("location")
        val count = (f.value("injured_count") as? JsonPrimitive)?.longOrNull
        val details = LinkedHashMap<String, String>()
        for (def in tx.detailFields(f.types)) text(def.key)?.let { details[def.label.ifBlank { def.key }] = it }
        text("hazard_ongoing")?.let { details[tx.label("hazard_ongoing")] = it }
        if (f.str("near_miss") == "true") details["Near miss"] = "Yes"
        return QrReport(
            id = d.meta.refNo ?: d.meta.id,
            ts = time(d.meta.createdAt),
            type = f.types.joinToString(" + ") { tx.category(it)?.label ?: it }.ifBlank { "Not set" },
            sev = f.str("severity").orEmpty(),
            // as said, plus the registry place in brackets; just the registry place when that is exactly what was said
            loc = when {
                site == null -> said.orEmpty()
                said == null || said.equals(site.name, ignoreCase = true) -> site.display
                else -> "$said (${site.display})"
            },
            at = (f.value("occurred_at") as? JsonPrimitive)?.longOrNull?.let { QrTime(time(it), f.occurredApprox) },
            inj = when (f.state("injuries")?.status) {
                FieldStatus.UNKNOWN -> "Unknown"
                else -> when (f.str("injuries")) { "YES" -> "Yes" + (count?.let { ", $it ${if (it == 1L) "person" else "people"}" } ?: ""); "NO" -> "No"; null -> ""; else -> "Unknown" }
            },
            per = people("persons"), eq = f.value("equipment").asStringList(), act = f.value("actions_taken").asStringList(), wit = people("witnesses"),
            desc = f.str("description").orEmpty(),
            det = details,
            sop = d.recommendations.map { it.text },
            tags = listOfNotNull(
                tags?.repeat?.let { "Repeat incident: " + Hotspots.chip(it, tx.category(it.type)?.label ?: it.type) },
                "Hotspot location".takeIf { tags?.hotspot == true },
                "Emergency".takeIf { d.meta.emergencyFlag },
            ),
            eng = engine,
            tx = d.turns.filter { it.role == "USER" }.joinToString("\n") { it.text }.ifBlank { null },
        )
    }
}
