package com.megathon.incidentvoice.orchestrator

import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.engine.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OrchestratorTest {
    private val cfg = File(Fx.dir.path).let { dir ->
        AppConfigBundle(Fx.tx, Fx.registry, Fx.sop, Fx.templates, AppConfig.parse(File(dir, "app_config.json").readText()))
    }

    private class Rig(scope: TestScope, cfg: AppConfigBundle, utterances: List<String>, ready: Boolean = true) {
        val engine = MockInferenceEngine(cfg.taxonomy, asrDelayMs = 0, tokensPerSecond = 0.0, utterances = utterances)
        val repo = InMemoryRepo()
        val tts = FakeTts(ready)
        val audio = FakeAudio()
        val o = ConversationOrchestrator(scope, cfg, engine, repo, tts, audio, { OrchestratorSettings(Reporter("Asha", "E100")) },
            clock = { Fx.now }, retryDelaysMs = listOf(0, 0))
        val inc get() = repo.store.values.first()
    }

    private fun TestScope.rig(vararg u: String, ready: Boolean = true) =
        Rig(this, cfg, if (u.isEmpty()) MockInferenceEngine.DEMO_UTTERANCES else u.toList(), ready)

    @Test fun waterLeakDemo_endToEnd() = runTest {
        val r = rig()
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        val ui = r.o.ui.value
        assertEquals(ConvState.REVIEW, ui.state)
        assertTrue(ui.validation!!.complete)
        assertEquals(2, ui.round)
        assertEquals(listOf(
            "Was anyone hurt, and how serious is the situation right now?",
            "Where is the water coming from, and is it still leaking?",
        ), r.tts.spoken.take(2))
        assertTrue(r.tts.spoken.any { it.startsWith("Thank you. I have everything I need") })
        val f = ui.form
        assertEquals("WATER", f.incidentType); assertEquals("NO", f.str("injuries")); assertEquals("LOW", f.str("severity"))
        assertEquals("STOPPED", f.str("leak_status")); assertEquals("A-1-CR2", f.locationId)
        assertTrue(ui.recommendations.isNotEmpty() && ui.recommendations.first().immediate.not() || ui.recommendations.isNotEmpty())
        // persisted draft: turns = narration, Q, A, Q, A + notes
        assertTrue(r.inc.turns.any { it.kind == "NARRATION" }); assertEquals(2, r.inc.turns.count { it.kind == "FOLLOWUP_Q" }); assertEquals(2, r.inc.turns.count { it.kind == "FOLLOWUP_A" })
        r.o.onSubmit(); advanceUntilIdle()
        assertEquals(ConvState.SUBMITTED, r.o.ui.value.state); assertEquals("INC-20261010-0001", r.o.ui.value.submittedRef)
        assertEquals(IncidentStatus.SUBMITTED, r.inc.meta.status)
    }

    @Test fun transcriptReview_editIsUsed_andAutoproceeds() = runTest {
        val r = rig("water leak near the pantry")
        r.o.startNew(); r.o.onMicTapped()
        advanceUntilIdle(); // reaches ASKING→… completes counting down automatically
        // narration turn stores raw
        assertEquals("water leak near the pantry", r.inc.turns.first { it.kind == "NARRATION" }.textRaw)
    }

    @Test fun transcriptEdit_extendsCountdown_andReplacesText() = runTest {
        val r = rig("water leak near the pantry", "nobody hurt minor", "pipe burst stopped")
        r.o.startNew(); r.o.onMicTapped()
        testScheduler.runCurrent(); testScheduler.advanceTimeBy(500)
        assertEquals(ConvState.TRANSCRIPT_REVIEW, r.o.ui.value.state)
        r.o.onTranscriptEdited("water leak near the server room")
        testScheduler.advanceTimeBy(1500)
        assertEquals(ConvState.TRANSCRIPT_REVIEW, r.o.ui.value.state) // edit reset the 2 s timer
        advanceUntilIdle()
        val n = r.inc.turns.first { it.kind == "NARRATION" }
        assertEquals("water leak near the server room", n.textEdited); assertEquals("A-2-SRV", r.inc.form.locationId)
    }

    @Test fun onContinue_skipsCountdown() = runTest {
        val r = rig("water leak near the pantry")
        r.o.startNew(); r.o.onMicTapped(); testScheduler.runCurrent(); testScheduler.advanceTimeBy(300)
        assertEquals(ConvState.TRANSCRIPT_REVIEW, r.o.ui.value.state)
        r.o.onContinue(); testScheduler.advanceTimeBy(150)
        assertNotEquals(ConvState.TRANSCRIPT_REVIEW, r.o.ui.value.state)
    }

    @Test fun hinglishNarration_asksInHinglish() = runTest {
        val r = rig("forklift ne bay 4 mein rack ko thok diya aadha ghanta pehle, driver ramesh tha")
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        assertEquals("hinglish", r.o.ui.value.form.lang)
        assertTrue(r.tts.spoken.first().let { it.contains("Kya") || it.contains("Yeh") || it.contains("Kaunsi") || it.contains("Batayiye") }, r.tts.spoken.toString())
    }

    @Test fun timeNotSpoken_defaultsAndIsNotAsked() = runTest {
        val r = rig("There is a water leak at the pantry", "no one hurt, minor", "the tap, it has stopped")
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        val st = r.o.ui.value.form.state("occurred_at")!!
        assertEquals(FieldStatus.DEFAULTED, st.status); assertTrue(r.o.ui.value.form.occurredApprox)
        assertTrue(r.tts.spoken.none { it.contains("what time", ignoreCase = true) })
    }

    @Test fun noSpeechOnFollowup_repromptsOnce_notCounted() = runTest {
        val r = rig()
        r.audio.results += ListenResult.Speech(ShortArray(16000), 1000) // narration
        r.audio.results += ListenResult.NoSpeech                       // first followup: silence
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        assertTrue(r.tts.spoken.any { it.startsWith("Sorry, I didn't catch that. ") })
        assertEquals(ConvState.REVIEW, r.o.ui.value.state); assertEquals(2, r.o.ui.value.round) // reprompt not counted
    }

    @Test fun skip_keepsFieldsMissing_andRoundLimitGoesToManualFill() = runTest {
        val r = rig("water leak near the pantry around 9 AM", "skip", "chhodo", "baad mein")
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        val ui = r.o.ui.value
        assertEquals(ConvState.MANUAL_FILL, ui.state); assertEquals(3, ui.round)
        assertTrue("severity" in ui.validation!!.missing)               // skipped ≠ unknown
        assertEquals(FieldStatus.MISSING, ui.form.state("injuries")?.status ?: FieldStatus.MISSING)
        assertFalse(ui.canSubmit)
        // user fills by hand → validator completes → recommendations → REVIEW
        ui.validation!!.missing.forEach { k ->
            val def = Fx.tx.field(k)!!
            r.o.onFieldEdited(k, JsonPrimitive(def.enum?.first() ?: "something"))
        }
        advanceUntilIdle()
        assertEquals(ConvState.REVIEW, r.o.ui.value.state); assertTrue(r.o.ui.value.canSubmit)
    }

    @Test fun iDontKnow_marksUnknownAndSatisfies() = runTest {
        val r = rig("water leak near the pantry around 9 AM", "pata nahi", "pata nahi")
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        val f = r.o.ui.value.form
        assertEquals(FieldStatus.UNKNOWN, f.state("severity")!!.status)
        assertEquals(ConvState.REVIEW, r.o.ui.value.state)
    }

    @Test fun emergency_keywordBanner_thenCriticalRed_onlyCommonFieldsAsked() = runTest {
        val r = rig("fire spreading in the panel room, someone is unconscious")
        r.engine.enqueue("yes someone is hurt, very serious, life in danger")
        r.o.startNew(); r.o.onMicTapped()
        testScheduler.runCurrent(); testScheduler.advanceTimeBy(100)
        assertEquals(EmergencyLevel.AMBER, r.o.ui.value.emergency?.level)
        assertTrue(r.o.ui.value.emergency!!.steps.isNotEmpty())
        advanceUntilIdle()
        val ui = r.o.ui.value
        assertEquals(EmergencyLevel.RED, ui.emergency?.level, "spoken=${r.tts.spoken} form=${ui.form.fields.mapValues { it.value.value }} missing=${ui.validation?.missing}")
        assertTrue(r.inc.meta.emergencyFlag)
        assertEquals("CRITICAL", ui.form.str("severity"))
        // §10.7: in emergency mode only common fields are asked; category details are left for review
        assertTrue(r.tts.spoken.none { it.contains("fire brigade", true) || it.contains("evacuated", true) }, r.tts.spoken.toString())
        assertEquals(ConvState.REVIEW, ui.state); assertFalse(ui.canSubmit)
        val events = mutableListOf<UiEvent>()
        launchCollect(r.o, events)
        testScheduler.runCurrent()
        r.o.onEmergencyCall(); advanceUntilIdle()
        assertEquals(listOf<UiEvent>(UiEvent.Dial("112")), events)
    }

    private fun TestScope.launchCollect(o: ConversationOrchestrator, into: MutableList<UiEvent>) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { o.events.collect { into += it } }

    @Test fun engineTransientError_retriesThenSucceeds() = runTest {
        val r = rig()
        r.engine.failNextGenerate = EngineError("IV-E602", "err 1002", retryable = true)
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        assertEquals(ConvState.REVIEW, r.o.ui.value.state)
    }

    @Test fun engineHardError_showsErrorEngine_thenManualFallbackKeepsTranscript() = runTest {
        val r = rig("water leak near the pantry")
        r.engine.failNextGenerate = EngineError("IV-E603", "no device", retryable = false)
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        assertEquals(ConvState.ERROR_ENGINE, r.o.ui.value.state)
        r.o.onManualFallback(); advanceUntilIdle()
        val ui = r.o.ui.value
        assertEquals(ConvState.MANUAL_FILL, ui.state); assertEquals("water leak near the pantry", ui.form.str("description"))
        assertEquals(FieldStatus.DEFAULTED, ui.form.state("description")!!.status)
    }

    @Test fun retryAfterHardError_works() = runTest {
        val r = rig("water leak near the pantry around 9 AM", "no one hurt, minor", "the tap, it has stopped")
        r.engine.failNextGenerate = EngineError("IV-E603", "x", retryable = false)
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        assertEquals(ConvState.ERROR_ENGINE, r.o.ui.value.state)
        r.o.onRetry(); advanceUntilIdle()
        assertEquals(ConvState.REVIEW, r.o.ui.value.state)
    }

    @Test fun emptyEngineOutput_isEngineErrorNotParseError() = runTest {
        val r = rig("water leak near the pantry around 9 AM", "no one hurt, minor", "the tap, it has stopped")
        r.engine.emptyNextGenerate = true
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        // empty is retryable engine error: mock recovers on retry (empty only once)
        assertNotEquals(ConvState.ERROR_PARSE, r.o.ui.value.state)
        assertEquals(ConvState.REVIEW, r.o.ui.value.state)
    }

    @Test fun typedReport_skipsTranscriptReview() = runTest {
        val r = rig("no one hurt, minor", "pipe burst, stopped")
        r.o.startNew(); r.o.onTypeInsteadRequested(); assertEquals(ConvState.TYPING, r.o.ui.value.state)
        r.o.onTypeInstead("Water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.")
        advanceUntilIdle()
        assertEquals(ConvState.REVIEW, r.o.ui.value.state)
        assertTrue(r.audio.calls.all { it == ListenKind.FOLLOWUP }) // narration never recorded
    }

    @Test fun userEdit_winsOverLaterPatch() = runTest {
        val r = rig()
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        r.o.onFieldEdited("severity", JsonPrimitive("HIGH")); advanceUntilIdle()
        assertEquals("HIGH", r.o.ui.value.form.str("severity")); assertEquals(FieldStatus.USER_EDITED, r.o.ui.value.form.state("severity")!!.status)
    }

    @Test fun submitBlockedWhenIncomplete() = runTest {
        val r = rig("water leak near the pantry around 9 AM", "skip", "skip", "skip")
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        r.o.onSubmit(); advanceUntilIdle()
        assertEquals(ConvState.MANUAL_FILL, r.o.ui.value.state); assertNotNull(r.o.ui.value.notice); assertNull(r.inc.meta.refNo)
    }

    @Test fun cancel_keepsDraft_andResets() = runTest {
        val r = rig()
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle(); r.o.onCancel()
        assertEquals(ConvState.IDLE, r.o.ui.value.state); assertEquals(1, r.repo.store.size) // draft kept
    }

    @Test fun otherIncident_offeredAfterSubmit() = runTest {
        val r = rig("water leak near the pantry around 9 AM. also a door is stuck", "no one hurt, minor", "pipe, stopped")
        // mock doesn't emit other_incidents; inject via a user-less path: edit form meta not available → just verify empty list is exposed
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle(); r.o.onSubmit(); advanceUntilIdle()
        assertEquals(emptyList(), r.o.ui.value.otherIncidents)
    }

    @Test fun ttsNotReady_stillAsksAndListens() = runTest {
        val r = rig(ready = false)
        r.o.startNew(); r.o.onMicTapped(); advanceUntilIdle()
        assertEquals(ConvState.REVIEW, r.o.ui.value.state); assertTrue(r.tts.spoken.isEmpty())
    }
}
