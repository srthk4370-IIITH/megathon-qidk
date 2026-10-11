package com.megathon.incidentvoice.core

import java.io.File
import java.util.Base64
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrPayloadTest {
    private val leak = QrReport(
        id = "INC-20261011-0005", ts = "2026-10-11T10:07:00+05:30", type = "Water Leak / Flooding", sev = "LOW",
        loc = "near Conference Room 2 (Conference Room 2 · Block A · Floor 1)", at = QrTime("2026-10-11T10:00:00+05:30", true), inj = "No",
        act = listOf("Maintenance isolated the area"), desc = "A water leak occurred near Conference Room 2. Maintenance has isolated the area.",
        det = mapOf("Source of leak" to "burst pipe under the sink", "Leak status" to "Stopped"),
        sop = listOf("Isolate the water supply valve feeding the leak.", "Switch off electrical equipment and sockets in the wet area if it is safe to do so.", "Cordon off the wet area and place wet-floor signs to prevent slips."),
        tags = listOf("Repeat incident: 3rd water leak here in 7 days", "Hotspot location"), eng = "qwen3-4b (NPU)",
        tx = "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.\nNo one is hurt, it's minor.",
    )
    private val fire = QrReport(
        id = "INC-20261011-0002", ts = "2026-10-11T09:12:30+05:30", type = "Fire / Smoke / Explosion + Electrical Hazard", sev = "CRITICAL",
        loc = "DB-3 panel Block B floor 2", at = QrTime("2026-10-11T09:12:00+05:30"), inj = "Yes, 2 people",
        per = listOf("Ramesh (affected): burn on the left hand", "Unnamed (affected)"), eq = listOf("db-3 panel"), wit = listOf("Suresh, ext. 4411"),
        act = listOf("Two people evacuated", "Fire brigade called"),
        desc = "DB-3 panel se dhuan aa raha hai aur aag lagi hai, do log bahar nikale gaye — “fire brigade” ko call kar diya.",
        det = mapOf("Fire status" to "Active", "Evacuation" to "In progress", "Fire brigade called" to "Yes"),
        sop = listOf("Raise the fire alarm and call the emergency team.", "Evacuate by the nearest safe exit; do not use lifts."),
        tags = listOf("Emergency"), eng = "rules (CPU)",
        tx = "Smoke coming from the DB-3 electrical panel in Block B floor 2, aag lagi hai, two people evacuated, fire brigade called",
    )
    /** Far too long for one code: the transcript must go, then the description is cut. */
    private val long = leak.copy(
        id = "INC-20261011-0009", tags = emptyList(),
        desc = (1..60).joinToString(" ") { "Sentence $it of the description says that pump P-${100 + it * 7} near bay ${it % 9} leaked ${it * 13 % 97} litres at ${it % 12}:${10 + it % 50}." },
        tx = (1..500).joinToString(" ") { Integer.toString(it * 7919 % 99991 * 31, 36) },   // hard to compress, like real speech
    )

    private fun rawJson(code: String): String {
        val i = Inflater(true); i.setInput(Base64.getUrlDecoder().decode(code.removePrefix("QALAM1:")))
        val buf = ByteArray(1 shl 16); val n = i.inflate(buf); i.end()
        return String(buf, 0, n, Charsets.UTF_8)
    }

    @Test fun encodeThenDecode_givesTheIdenticalObject() {
        for (r in listOf(leak, fire)) {
            val e = QrCodec.encode(r)
            assertTrue(e.text.startsWith("QALAM1:")); assertFalse('=' in e.text); assertFalse('+' in e.text); assertFalse('/' in e.text.removePrefix("QALAM1:"))
            assertTrue(e.compressedBytes <= QrCodec.MAX_BYTES, "${e.compressedBytes}")
            assertFalse(e.droppedTranscript); assertFalse(e.truncatedDescription)
            val d = QrCodec.decode(e.text)
            assertTrue(d.crcOk)
            assertEquals(r, d.report.copy(crc = ""))
            assertEquals(e.report, d.report)
            assertEquals(8, d.report.crc.length)
        }
    }

    @Test fun wireFormat_isRawDeflateOfCompactJsonWithCrcLast() {
        val raw = rawJson(QrCodec.encode(leak).text)
        assertTrue(raw.startsWith("""{"v":1,"id":"INC-20261011-0005","ts":"2026-10-11T10:07:00+05:30","type":"Water Leak / Flooding","sev":"LOW","loc":"""), raw.take(120))
        assertTrue(Regex("\"crc\":\"[0-9a-f]{8}\"}$").containsMatchIn(raw))
        assertTrue(""""at":{"t":"2026-10-11T10:00:00+05:30","approx":true}""" in raw)
        // CRC32 of the same text with the crc value emptied
        val crc = java.util.zip.CRC32().apply { update(raw.replace(Regex("\"crc\":\"[0-9a-f]{8}\"}$"), "\"crc\":\"\"}").toByteArray()) }.value.toString(16).padStart(8, '0')
        assertTrue(raw.endsWith("\"crc\":\"$crc\"}"))
    }

    @Test fun tooBig_dropsTheTranscriptFirst_thenCutsTheDescription() {
        // only the transcript is too much
        val e1 = QrCodec.encode(leak.copy(tx = long.tx))
        assertTrue(e1.droppedTranscript); assertFalse(e1.truncatedDescription)
        assertNull(e1.report.tx); assertEquals(leak.desc, e1.report.desc)
        assertFalse("\"tx\"" in rawJson(e1.text))
        // the description alone is too much as well
        val e2 = QrCodec.encode(long)
        assertTrue(e2.droppedTranscript && e2.truncatedDescription)
        assertTrue(e2.compressedBytes <= QrCodec.MAX_BYTES, "${e2.compressedBytes}")
        assertTrue(e2.compressedBytes > QrCodec.MAX_BYTES - 40, "uses the room it has: ${e2.compressedBytes}")
        val d = QrCodec.decode(e2.text)
        assertTrue(d.crcOk); assertNull(d.report.tx)
        assertTrue(d.report.desc.endsWith("…") && long.desc.startsWith(d.report.desc.dropLast(1)))
        assertEquals(long.sop, d.report.sop); assertEquals(long.det, d.report.det)
    }

    @Test fun damagedOrForeignCode_isReported() {
        val good = QrCodec.encode(leak).text
        assertFailsWith<IllegalArgumentException> { QrCodec.decode("https://example.com/x") }
        assertFailsWith<IllegalArgumentException> { QrCodec.decode("QALAM1:!!!!") }
        assertFailsWith<IllegalArgumentException> { QrCodec.decode(good.dropLast(40)) }
        // a payload whose text was changed after the checksum was computed still parses, and fails the check
        val tampered = rawJson(good).replace("\"sev\":\"LOW\"", "\"sev\":\"HIGH\"")
        val d = java.util.zip.Deflater(9, true).let { z -> z.setInput(tampered.toByteArray()); z.finish(); val b = ByteArray(1 shl 16); val n = z.deflate(b); z.end(); b.copyOf(n) }
        val bad = QrCodec.decode("QALAM1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(d))
        assertFalse(bad.crcOk); assertEquals("HIGH", bad.report.sev)
    }

    @Test fun builtFromASavedReport_readsWithoutTheTaxonomy() {
        val transcript = "Ramesh slipped on the wet floor near the pantry about an hour ago and hurt his ankle. First aid was given, he is conscious."
        val call = (Fx.parser.parse("""{"name":"fill_incident_form","parameters":{"lang":"en","incident_type":"MEDICAL","secondary_types":["STRUCTURAL"],"title":"Slip near pantry","description":"Ramesh slipped on the wet floor near the pantry and hurt his ankle.","location":"near pantry","time":"about an hour ago","injuries":"YES","injured_count":1,"severity":"MEDIUM","persons":[{"name":"Ramesh","role":"AFFECTED","injured":true,"injury":"ankle injury"}],"actions_taken":["First aid given"],"details":{"patient_condition":"CONSCIOUS_STABLE","first_aid_given":"YES","ambulance_called":"NO","damage_type":"wet floor","area_cordoned":"YES"}}}""", Tools.FILL) as ParseOutcome.Ok).call
        val form = Fx.merger.merge(IncidentForm(), call, FieldSource.LLM_EXTRACT, 0, transcript, Fx.now).form
        val meta = IncidentMeta("uuid-1", IncidentStatus.SUBMITTED, "INC-20261010-0003", Reporter("Asha", "E100"), createdAt = Fx.now.toEpochMilli(), reportedAt = Fx.now.toEpochMilli())
        val detail = com.megathon.incidentvoice.data.IncidentDetail(
            meta, form, listOf(com.megathon.incidentvoice.data.TurnRecord("USER", "NARRATION", textRaw = transcript), com.megathon.incidentvoice.data.TurnRecord("ASSISTANT", "FOLLOWUP_Q", textRaw = "Anything else?")),
            listOf(com.megathon.incidentvoice.data.RecRow(1, "MED-01", "Do not move the injured person unless they are in danger.", true, "SOP", false)),
        )
        val r = QrReportBuilder.build(detail, Fx.tx, Fx.registry, ReportTags(RepeatTag(3, "MEDICAL", 7), true, "Pantry"), "qwen3-4b (NPU)", Fx.zone)
        assertEquals("INC-20261010-0003", r.id); assertEquals("2026-10-10T10:07:00+05:30", r.ts)
        assertEquals(Fx.tx.category("MEDICAL")!!.label + " + " + Fx.tx.category("STRUCTURAL")!!.label, r.type)
        assertEquals("MEDIUM", r.sev); assertEquals("Yes, 1 person", r.inj)
        assertEquals("near pantry (Pantry · Block A · Floor 1)", r.loc)
        assertEquals(QrTime("2026-10-10T09:07:00+05:30", true), r.at)
        assertEquals(listOf("Ramesh (affected): ankle injury"), r.per); assertEquals(listOf("First aid given"), r.act)
        assertEquals("Conscious stable", r.det[Fx.tx.label("patient_condition")]); assertEquals("wet floor", r.det[Fx.tx.label("damage_type")])
        assertEquals(listOf("Do not move the injured person unless they are in danger."), r.sop)
        assertEquals(2, r.tags.size); assertTrue(r.tags[0].startsWith("Repeat incident: 3rd ")); assertEquals("Hotspot location", r.tags[1])
        assertEquals(transcript, r.tx)                                            // only what the reporter said
        // no reporter name or employee id travels in the code
        val json = QrCodec.toJson(r)
        assertFalse("Asha" in json); assertFalse("E100" in json)
        assertTrue(QrCodec.decode(QrCodec.encode(r).text).crcOk)
    }

    /** The three sample codes the website tests decode (web/samples). Rewritten on every run so they always match this encoder. */
    @Test fun writeSamplesForTheReaderWebsite() {
        val dir = File(Fx.dir.parentFile, "web/samples").apply { mkdirs() }
        for ((name, r) in listOf("water_leak" to leak, "fire_hinglish" to fire, "long_truncated" to long)) {
            val e = QrCodec.encode(r)
            File(dir, "$name.txt").writeText(e.text)
            File(dir, "$name.json").writeText(QrCodec.toJson(e.report))            // what the reader must show
            assertTrue(QrCodec.decode(File(dir, "$name.txt").readText()).crcOk)
        }
    }
}
