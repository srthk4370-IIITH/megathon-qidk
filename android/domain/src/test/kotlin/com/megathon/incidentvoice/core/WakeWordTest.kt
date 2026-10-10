package com.megathon.incidentvoice.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WakeWordTest {
    private val cfg = AppConfig.parse(File(Fx.dir, "app_config.json").readText())
    private val m = WakeMatcher(cfg.wakeVariants, cfg.wakeMinConf)

    @Test fun configCarriesTheWakeSettings() {
        assertEquals("Hey Qalam", cfg.wakeDisplay); assertEquals(0.6, cfg.wakeMinConf); assertTrue("hey kalam" in cfg.wakeVariants && "hey calum" in cfg.wakeVariants)
        assertTrue(cfg.features.wakeWord && cfg.features.quickLaunch && cfg.features.responseClock && cfg.features.multiWitness)
    }
    @Test fun matchesVariantsAndNearSpellings() {
        for (h in listOf("hey kalam", "Hey, Qalam!", "hey calum", "hey column", "okay hey kalam please", "hey kalaam", "hey kallam", "a kalam", "hey calm"))
            assertNotNull(m.match(h), h)
    }
    @Test fun ignoresOrdinarySpeech() {
        for (h in listOf("", "hello there", "there was a water leak near conference room two", "hey can you come here", "the column is cracked", "calm down", "he came along", "pay the claim"))
            assertNull(m.match(h), h)
    }
    @Test fun confidenceGate() {
        assertNull(m.match("hey kalam", 0.4)); assertNotNull(m.match("hey kalam", 0.61)); assertNotNull(m.match("hey kalam", null))
    }
    @Test fun grammarMode_nameAloneCounts_butNotInsideASentence() {
        val m = WakeMatcher(cfg.wakeVariants, cfg.wakeMinConf)
        assertEquals("hey column", m.matchAlone("column", 0.8)); assertEquals("hey column", m.matchAlone("hey column", 0.8))
        assertNull(m.matchAlone("column", 0.5)); assertNull(m.matchAlone("[unk] column [unk] [unk]", 0.9)); assertNull(m.matchAlone("hey", 0.9)); assertNull(m.matchAlone("a", 0.9))
        assertNull(m.matchAlone("[unk] hey column", 0.9))
    }
    @Test fun strategy_grammarOnlyWhenTheNameIsInTheVocabulary() {
        val vocabWith = setOf("hey", "kalam", "calm", "column", "a")
        val g = WakeStrategy.choose(cfg.wakeVariants, vocabWith::contains) as WakeStrategy.Grammar
        assertEquals("hey kalam", g.phrases.first()); assertTrue("hey calm" in g.phrases && "hey qalam" !in g.phrases && "hey calum" !in g.phrases)
        assertEquals("[\"hey kalam\",\"a kalam\",\"hey column\",\"hey calm\",\"[unk]\"]", WakeStrategy.grammarJson(g))
        assertTrue(!g.standIn)
        val s = WakeStrategy.choose(cfg.wakeVariants, setOf("hey", "calm", "column")::contains) as WakeStrategy.Grammar
        assertTrue(s.standIn); assertEquals(listOf("hey column", "hey calm"), s.phrases)
        assertEquals(WakeStrategy.Free, WakeStrategy.choose(cfg.wakeVariants, setOf("hello")::contains))
    }
}
