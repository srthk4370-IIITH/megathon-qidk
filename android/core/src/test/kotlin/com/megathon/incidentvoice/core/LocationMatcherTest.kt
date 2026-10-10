package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.core.normalize.Fuzzy
import com.megathon.incidentvoice.core.normalize.LocationMatcher.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PRD §15.2. */
class LocationMatcherTest {
    private val m = T.matcher

    private fun auto(spoken: String, id: String) {
        val r = m.match(spoken)
        assertEquals(Kind.AUTO, r.kind, "'$spoken' → ${r.location?.id} score=${r.score} norm='${r.normalized}'")
        assertEquals(id, r.locationId, "'$spoken'")
    }

    @Test fun nearConfRoom2() {
        auto("near conf room 2", "A-1-CR2")
        val r = m.match("near conf room 2")
        assertEquals("near", r.qualifier)
        assertEquals("Conference Room 2 · Block A · Floor 1 (near)", r.display)
    }

    @Test fun slideLocation() = auto("near Conference Room 2", "A-1-CR2")
    @Test fun seedhiIsStairs() = auto("seedhi ke paas", "A-G-STA")
    @Test fun stairs() = auto("near the stairs", "A-G-STA")
    @Test fun cr2Alias() = auto("CR2", "A-1-CR2")
    @Test fun bayFourWords() = auto("bay four", "B-G-B4")
    @Test fun bay4Hinglish() = auto("bay 4 mein", "B-G-B4")
    @Test fun mainGateKePaas() = auto("main gate ke paas", "Y-G1")
    @Test fun kitchenAlias() = auto("in the kitchen", "A-1-PAN")
    @Test fun serverRmAbbrev() = auto("server rm", "A-2-SRV")
    @Test fun meetingRoom1() = auto("meeting room 1", "A-1-CR1")

    @Test fun seedhiResolvesToStaircase() {
        val r = m.match("seedhi")
        assertEquals(Kind.AUTO, r.kind)
        assertTrue(r.location!!.name.startsWith("Staircase"))
    }

    @Test fun numberConflictNeverMatches() {
        val r = m.match("conference room 5")
        assertTrue(r.kind != Kind.AUTO || r.locationId !in setOf("A-1-CR1", "A-1-CR2"), "matched ${r.locationId} ${r.score}")
    }

    @Test fun unmatched() {
        val r = m.match("behind the old banyan tree")
        assertEquals(Kind.NONE, r.kind, "score=${r.score} ${r.location}")
        assertNull(r.locationId)
    }

    @Test fun typoGivesSuggestionOrAuto() {
        val r = m.match("confrence rom 2")
        assertTrue(r.kind != Kind.NONE, "score=${r.score}")
        assertEquals("A-1-CR2", r.locationId)
    }

    @Test fun blankIsNone() = assertEquals(Kind.NONE, m.match("  ").kind)

    @Test fun fuzzyPrimitives() {
        assertEquals(1.0, Fuzzy.tokenSetRatio("room conference 2", "conference room 2"))
        assertTrue(Fuzzy.jaroWinkler("martha", "marhta") > 0.95)
        assertEquals(0.0, Fuzzy.jaroWinkler("abc", "xyz"))
    }
}
