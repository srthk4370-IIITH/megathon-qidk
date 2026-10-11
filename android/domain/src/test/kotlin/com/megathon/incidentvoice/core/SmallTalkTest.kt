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
}
