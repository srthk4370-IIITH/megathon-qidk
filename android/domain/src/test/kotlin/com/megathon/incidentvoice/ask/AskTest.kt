package com.megathon.incidentvoice.ask

import com.megathon.incidentvoice.core.Fx
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskTest {
    // now = Sat 2026-10-10 10:07 IST
    private val p = RuleQueryParser(Fx.tx, Fx.registry, Fx.zone)
    private fun q(s: String) = p.parse(s, Fx.now)
    private val day = 24L * 3600_000

    @Test fun count_criticalThisWeek() {
        val x = q("How many critical incidents this week?")
        assertEquals(AskIntent.COUNT, x.intent); assertEquals(setOf("CRITICAL"), x.severities); assertEquals("this week", x.timeLabel)
        assertNotNull(x.fromMs); assertNull(x.toMs); assertEquals("", x.freeText)
    }
    @Test fun location_fromRegistry() {
        val x = q("What happened in Bay 4?")
        assertEquals(AskIntent.DETAIL, x.intent); assertEquals(setOf("B-G-B4"), x.locationIds); assertEquals("Bay 4", x.locationLabel); assertEquals("", x.freeText)
        assertEquals(setOf("A-1-CR2"), q("anything near conference room two").locationIds)
    }
    @Test fun types_andRelativeRange() {
        val x = q("Show fires in the last 7 days")
        assertEquals(setOf("FIRE"), x.types); assertEquals(Fx.now.toEpochMilli() - 7 * day, x.fromMs); assertEquals("in the last 7 days", x.timeLabel)
        assertEquals(setOf("WATER"), q("list all water leaks").types)
        assertEquals(setOf("HAZMAT"), q("any gas leaks yesterday?").types)           // "leak" must not add WATER
        val f = q("forklift accidents last month")                                  // a specific thing is searched by meaning, not as a hard filter
        assertTrue(f.types.isEmpty()); assertEquals("forklift accidents", f.freeText); assertEquals("last month", f.timeLabel)
        assertEquals(setOf("VEHICLE"), q("vehicle incidents").types)
        assertEquals(setOf("SECURITY"), q("were there any thefts").types)
    }
    @Test fun yesterday_isAClosedRange() {
        val x = q("incidents yesterday")
        assertEquals(day, x.toMs!! - x.fromMs!!); assertEquals("yesterday", x.timeLabel)
    }
    @Test fun freeText_isWhatRemains() {
        assertEquals("slipped wet floor", q("did someone slipped on a wet floor").freeText)
        assertEquals("ammonia smell", q("show me reports about ammonia smell").freeText)
        val x = q("similar to a worker fainting in the heat")
        assertTrue(x.freeText.contains("fainting") && x.freeText.contains("heat"), x.freeText); assertTrue(!x.hasFilters)
    }
    @Test fun injuries_andSeverityGroups() {
        assertEquals("YES", q("incidents with injuries this month").injuries)
        assertEquals(setOf("HIGH", "CRITICAL"), q("any serious incidents today").severities)
        assertEquals(AskIntent.LATEST, q("what is the latest incident").intent)
    }
    @Test fun building_expandsToItsLocations() {
        val x = q("fires in block b")
        assertEquals("Block B", x.locationLabel); assertTrue(x.locationIds.size > 1)
    }

    // ---- answers
    private val c = AnswerComposer(Fx.tx, Fx.zone)
    private fun hit(title: String, type: String, sev: String, loc: String) = AskHit("id", "INC-20261010-0001", title, type, sev, "SUBMITTED", loc, Fx.now.toEpochMilli(),
        description = "Smoke and fire from the panel.", actionsTaken = listOf("Area evacuated"), recommendations = listOf("Raise the fire alarm.", "Use the stairs."))

    @Test fun answer_count() {
        val a = c.compose(q("How many critical incidents this week?"), listOf(hit("Fire at DB-3 panel", "FIRE", "CRITICAL", "Block B")), 2)
        assertEquals("There are 2 critical incidents this week. The most recent is: Fire at DB-3 panel at Block B, 10 Oct, 10:07 AM, severity critical.", a)
    }
    @Test fun answer_none() {
        assertEquals("I couldn't find any fire incidents in the last 7 days.", c.compose(q("Show fires in the last 7 days"), emptyList(), 0))
        assertEquals("I couldn't find any report about \"ammonia smell\".", c.compose(q("reports about ammonia smell"), emptyList(), 0))
    }
    @Test fun answer_detail_includesActionsAndNextSteps() {
        val a = c.compose(q("What happened in Bay 4?"), listOf(hit("Forklift hit a rack", "VEHICLE", "MEDIUM", "Bay 4")), 1)
        assertTrue(a.startsWith("Forklift hit a rack at Bay 4, 10 Oct, 10:07 AM, severity medium."), a)
        assertTrue(a.contains("Actions taken: Area evacuated.") && a.contains("Recommended next: Raise the fire alarm; Use the stairs."), a)
    }
    @Test fun answer_semanticList() {
        val a = c.compose(q("reports about ammonia smell"), listOf(hit("Suspected ammonia leak", "HAZMAT", "CRITICAL", "chemical store")), 3)
        assertTrue(a.startsWith("I found 3 reports related to \"ammonia smell\". The closest match is: Suspected ammonia leak at chemical store"), a)
    }

    // ---- tokenizer + vectors
    private val vocab = File(Fx.dir.parentFile, "android/app/src/main/assets/models/minilm/vocab.txt")
    @Test fun wordPiece_matchesBertReference() {
        if (!vocab.exists()) return
        val t = WordPieceTokenizer(vocab.readLines())
        // reference ids from the HuggingFace BertTokenizer for bert-base-uncased vocabulary
        assertEquals(listOf(101L, 7592L, 2088L, 102L), t.encode("Hello world").ids.toList())
        assertEquals(listOf("there", "was", "a", "water", "leak", "near", "conference", "room", "2", "."), t.basicTokens("There was a water leak near Conference Room 2."))
        val e = t.encode("forklift unconscious DB-3")
        assertEquals(101L, e.ids.first()); assertEquals(102L, e.ids.last()); assertTrue(e.ids.none { it == 100L }, "no [UNK] expected: ${e.ids.toList()}")
        assertEquals(e.ids.size, e.mask.size); assertTrue(e.types.all { it == 0L })
        assertEquals(128, WordPieceTokenizer(vocab.readLines()).encode("word ".repeat(500)).ids.size)   // truncated to max length
    }
    @Test fun vectors_roundTripAndPooling() {
        val v = Vectors.normalize(floatArrayOf(3f, 4f)); assertEquals(0.6f, v[0], 1e-6f); assertEquals(1f, Vectors.dot(v, v), 1e-6f)
        assertEquals(v.toList(), Vectors.fromBytes(Vectors.toBytes(v)).toList())
        val pooled = Vectors.meanPool(arrayOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f), floatArrayOf(9f, 9f)), longArrayOf(1, 1, 0))  // third token masked out
        assertEquals(pooled[0], pooled[1], 1e-6f)
        assertEquals(1.0f, HybridRank.score(1f, true), 1e-6f); assertTrue(HybridRank.relevant(0.05f, allWords = true)); assertTrue(!HybridRank.relevant(0.1f, false, anyWord = true))
        assertTrue(HybridRank.relevant(0.2f, false, anyWord = true)); assertTrue(HybridRank.relevant(0.3f, false, false)); assertTrue(!HybridRank.relevant(0.25f, false, false))
    }
}
