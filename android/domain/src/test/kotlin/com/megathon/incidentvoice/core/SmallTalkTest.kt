package com.megathon.incidentvoice.core

import com.megathon.incidentvoice.domain.engine.RuleBasedLlm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmallTalkTest {
    private val rules = RuleBasedLlm(Fx.tx, Fx.registry)
    private val emergency = EmergencyDetector(Fx.tx)
    private fun cue(t: String) = rules.hasIncidentCue(t) || emergency.scan(t) != null
    private fun none(t: String) = SmallTalk.isNoIncident(t, ::cue)

    /** Ten messy inputs that must not fill a form. */
    @Test fun messyInputs_areNotIncidents() {
        val messy = listOf(
            "hi wassup this is Ravi",                               // greeting + name
            "hello hello, can you hear me",                         // mic check
            "good morning",                                         // greeting only
            "why did the chicken cross the road",                   // joke
            "what's the weather like today",                        // off-topic
            "Asha here",                                            // name only
            "main Ravi bol raha hoon",                              // Hinglish self-introduction
            "there was a",                                          // cut-off sentence
            "um so yeah I wanted to",                               // cut-off, fillers
            "nothing happened, just testing the mic",               // says so themselves
            "namaste, kya haal hai bhai",                           // Hinglish small talk
            "",                                                     // empty
        )
        for (m in messy) assertTrue(none(m), "should not be an incident: \"$m\"")
    }

    @Test fun realReports_areIncidents_evenWhenMessyOrVague() {
        val real = listOf(
            "hi this is Ravi, there is a fire in the server room",
            "um there was a water leak near conference room 2",
            "something happened near gate 2, please send someone",
            "main Ravi bol raha hoon, Bay 4 mein forklift takra gayi",
            "the lift is stuck between floors",
            "paani gir raha hai pantry mein",
            "someone fainted in the canteen",
            "bijli ka taar toot gaya hai",
        )
        for (r in real) assertFalse(none(r), "should be an incident: \"$r\"")
    }

    @Test fun selfIntroduction_givesTheName_andNothingElseDoes() {
        assertEquals("Ravi", SmallTalk.reporterName("hi wassup this is Ravi"))
        assertEquals("Asha", SmallTalk.reporterName("My name is asha and I work in stores"))
        assertEquals("Ravi", SmallTalk.reporterName("main Ravi bol raha hoon"))
        assertEquals("Meena", SmallTalk.reporterName("mera naam Meena hai"))
        assertEquals("Imran", SmallTalk.reporterName("Imran here, there is smoke in bay 2"))
        assertNull(SmallTalk.reporterName("I am hurt"))
        assertNull(SmallTalk.reporterName("this is an emergency"))
        assertNull(SmallTalk.reporterName("this is the security desk"))
        assertNull(SmallTalk.reporterName("there is a fire in the server room"))
    }

    @Test fun prompt_isPlain_andMatchesTheLanguage() {
        assertEquals("I didn't catch an incident. What happened?", SmallTalk.prompt("en"))
        assertEquals("Hi Ravi. I didn't catch an incident. What happened?", SmallTalk.prompt("en", "Ravi"))
        assertEquals("Mujhe koi incident samajh nahi aaya. Kya hua tha?", SmallTalk.prompt("hinglish"))
    }

    /** ML contract: small talk extracts to description "NO_INCIDENT"; the stand-in rules engine does the same. */
    @Test fun sentinel_isRecognised_andTheRulesEngineReturnsIt() {
        assertTrue(SmallTalk.isSentinel("NO_INCIDENT")); assertTrue(SmallTalk.isSentinel(" no_incident. ")); assertFalse(SmallTalk.isSentinel("No incident in bay 4 today")); assertFalse(SmallTalk.isSentinel(null))
        val out = rules.extract("hello hello testing 1 2 3, can you hear me?")
        assertEquals("{\"incident_type\":\"OTHER\",\"description\":\"NO_INCIDENT\",\"lang\":\"en\"}", out.toString())
        assertEquals("\"hinglish\"", rules.extract("kuch nahi bas check kar raha tha")["lang"].toString())
        assertFalse(SmallTalk.isSentinel(rules.extract("there is a water leak near the pantry")["description"].toString().trim('"')))
    }

    /** The ML branch's small-talk set must be rejected, and its real-incident set must not be. */
    @Test fun mlEvalSets_smallTalkRejected_realIncidentsKept() {
        val eval = java.io.File(Fx.dir.parentFile, "eval")
        fun texts(f: String) = java.io.File(eval, f).readLines().filter { it.isNotBlank() }.map { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
            .map { (it["id"] as kotlinx.serialization.json.JsonPrimitive).content to (it["transcript"] as kotlinx.serialization.json.JsonPrimitive).content }
        val missed = texts("smalltalk_cases.jsonl").filter { !none(it.second) }
        val wronglyRejected = texts("text_cases.jsonl").filter { none(it.second) }
        println("SMALLTALK-EVAL small talk not rejected: ${missed.size}/15 ${missed}  | real incidents rejected: ${wronglyRejected.size}/150 ${wronglyRejected}")
        assertTrue(missed.isEmpty(), "small talk filed as an incident: $missed")
        assertTrue(wronglyRejected.isEmpty(), "real incidents rejected: $wronglyRejected")
    }

    // ---- language matching and the reporter's own words
    @Test fun language_followsTheReportersWords() {
        assertEquals("hinglish", LangDetector.ofSpeech("Pantry ke paas paani leak ho raha hai"))
        assertEquals("hinglish", LangDetector.ofSpeech("पैंट्री के पास पानी गिर रहा है"))
        assertEquals("en", LangDetector.ofSpeech("There was a water leak near Conference Room 2 around 10 AM"))
        assertEquals("en", LangDetector.ofSpeech("A forklift hit the rack in Bay 4, nobody was hurt"))
    }
    @Test fun description_lightCleanupOnly() {
        assertEquals("There was a water leak near the pantry, it is still dripping.", DescriptionStyle.light("um, there was a water leak near the pantry, uh it is still dripping"))
        assertEquals("Pantry ke paas paani leak ho raha hai.", DescriptionStyle.light("hello, pantry ke paas paani leak ho raha hai"))
        assertEquals("", DescriptionStyle.light("  "))
    }
    @Test fun description_keepsTheReportersWords_unlessTheModelStayedClose() {
        val said = "the pipe under the sink burst and water is all over the pantry floor"
        // a tidy-up of the same words is accepted
        assertEquals("The pipe under the sink burst; water is all over the pantry floor.", DescriptionStyle.keep("The pipe under the sink burst; water is all over the pantry floor.", said))
        // a formal rewrite is not: the reporter's own sentence is used
        assertEquals("The pipe under the sink burst and water is all over the pantry floor.",
            DescriptionStyle.keep("A plumbing failure resulted in significant flooding of the kitchenette area, posing a slip hazard to personnel.", said))
        assertEquals("The pipe under the sink burst and water is all over the pantry floor.", DescriptionStyle.keep(null, said))
        assertTrue(DescriptionStyle.overlap("A plumbing failure resulted in flooding", said) < 0.3)
    }
}
