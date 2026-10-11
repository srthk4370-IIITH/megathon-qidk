package com.megathon.incidentvoice.orchestrator

import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.data.*
import com.megathon.incidentvoice.domain.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * PRD §8 conversation state machine. UI → orchestrator via the on* methods (§22.2); state out via [ui].
 * Pure Kotlin (JVM-testable); audio, TTS, engine and storage are ports.
 */
class ConversationOrchestrator(
    private val scope: CoroutineScope,
    private val cfg: AppConfigBundle,
    private val engine: InferenceEngine,
    private val repo: IncidentRepository,
    private val tts: TtsSpeaker,
    private val audio: AudioInput,
    private val settings: () -> OrchestratorSettings,
    private val clock: () -> Instant = { Instant.now() },
    /** §31.3 back-off between engine retries; tests pass an empty list. */
    private val retryDelaysMs: List<Long> = listOf(3_000, 7_000, 15_000),
) {
    private val tx = cfg.taxonomy
    private val parser = ToolCallParser(tx)
    private val merger = FormMerger(tx, TimeResolver(), LocationMatcher(cfg.registry))
    private val prompts = PromptBuilder(tx, cfg.templates, cfg.app.models.promptVariant)
    private val sop = SopSelector(cfg.sop, cfg.app.recommend.maxItems)
    private val emergencyDetector = EmergencyDetector(tx)
    /** Keyword rules: tell an incident from small talk, and stand in for the model for one turn if it fails. */
    private val rules = RuleBasedLlm(tx, cfg.registry)
    private val conv = cfg.app.conversation

    private val _ui = MutableStateFlow(ConversationUiState())
    val ui: StateFlow<ConversationUiState> = _ui.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private var job: Job? = null
    private var form = IncidentForm()
    private var incidentId: String? = null
    private var rounds = 0
    private var emergencyFlag = false
    private var bubbleSeq = 0L
    private var question: FollowUpQuestion? = null
    private var lastAskedFields: List<String> = emptyList()
    private var repromptedNoSpeech = false
    private var emergencyLocationAsked = false
    private var lastFromVoice = true
    private var narrationReprompted = false
    private var noIncidentCount = 0
    private var spokenReporter: String? = null
    private var retryAction: (suspend () -> Unit)? = null
    private val userTexts = StringBuilder() // everything the speaker said (grounding)
    private val userTurns = ArrayList<String>() // the same, one entry per utterance, in the order of the stored USER turns (evidence spans)
    private var narrationText = ""
    private var edited = false
    private var cont: CompletableDeferred<Unit>? = null
    private var spokeComplete = false
    private var editingRef: String? = null
    private var responseStart: Long? = null
    private val responseActions = ArrayList<ResponseAction>()
    private var hintCategory: String? = null
    private var reminderJob: Job? = null
    @Volatile private var generating = false
    @Volatile private var transcriptFocused = false

    // =========================================================================================
    // UI API (§22.2)
    // =========================================================================================

    fun startNew(prefillNarration: String? = null) {
        cancelAll()
        reset()
        if (prefillNarration != null) step { processText(prefillNarration, fromVoice = false, audioPath = null, audioMs = null, asrMs = null) }
    }

    fun onMicTapped() {
        when (ui.value.state) {
            ConvState.LISTENING -> onStopTapped()
            ConvState.IDLE, ConvState.TRANSCRIPT_REVIEW, ConvState.ERROR_AUDIO, ConvState.TYPING ->
                step { if (question != null && ui.value.state == ConvState.TYPING) listenFollowup() else listenNarration() }
            else -> {}
        }
    }

    fun onStopTapped() { if (ui.value.state == ConvState.LISTENING) audio.stop() }

    /** "Type instead" pressed: stop the mic, show the text field. */
    fun onTypeInsteadRequested() {
        if (ui.value.state in setOf(ConvState.IDLE, ConvState.LISTENING, ConvState.ASKING, ConvState.TRANSCRIPT_REVIEW)) {
            cancelAll(); tts.stop(); audio.cancel()
            update { it.copy(state = ConvState.TYPING, notice = null) }
        }
    }

    /** Typed narration or typed answer. */
    fun onTypeInstead(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val answering = question != null && form.fields.isNotEmpty()
        step { if (answering) handleAnswer(t, audioPath = null, audioMs = null, asrMs = null) else processText(t, fromVoice = false, null, null, null) }
    }

    fun onTranscriptEdited(text: String) { narrationText = text; edited = true; update { it.copy(transcriptDraft = text) } }
    /** While the transcript field has focus the auto-proceed countdown is held: the user is correcting a mis-heard word. */
    fun onTranscriptFocus(focused: Boolean) { transcriptFocused = focused; if (focused) edited = true }
    fun onContinue() { cont?.complete(Unit) }

    fun onReviewNow() {
        if (ui.value.state in setOf(ConvState.SUBMITTED, ConvState.IDLE)) return
        cancelAll(); audio.cancel(); tts.stop()
        step { gotoManualFill(speak = false) }
    }

    fun onFieldEdited(key: String, value: JsonElement?) {
        form = merger.userEdit(form, key, value, clock())
        form = merger.applyRules(form, clock()).first.let { it.copy(evidence = it.evidence - key) }
        afterReviewEdit(key, if (value == null) "cleared" else "edited")
    }

    fun onFieldUnknown(key: String) {
        form = merger.userUnknown(form, key).let { it.copy(evidence = it.evidence - key) }
        afterReviewEdit(key, "unknown")
    }

    fun onRecommendationEdited(list: List<Recommendation>) {
        update { it.copy(recommendations = list) }
        val id = incidentId ?: return
        scope.launch { repo.saveRecommendations(id, list) }
    }

    /** Detail → Edit (PRD §23.2): load a saved incident into the review screen. Saving keeps its reference number. */
    fun openForEdit(id: String) {
        cancelAll(); audio.cancel(); tts.stop(); reset()
        step {
            val d = repo.load(id) ?: return@step
            incidentId = id; form = d.form; rounds = d.meta.followupRounds; emergencyFlag = d.meta.emergencyFlag
            editingRef = d.meta.refNo
            d.turns.filter { it.role == "USER" }.forEach { userTexts.append(it.text).append(' '); userTurns += it.text }
            update {
                it.copy(state = ConvState.REVIEW, incidentId = id, form = form, userTurns = userTurns.toList(), validation = Validator.validate(form, tx, clock()),
                    recommendations = d.recommendations.map { r -> Recommendation(r.sopId, r.text, r.immediate, r.source, r.done) }, editingExisting = editingRef != null)
            }
        }
    }

    fun onSubmit() {
        val id = incidentId ?: return
        step {
            val res = Validator.validate(form, tx, clock())
            if (!res.complete) {
                update { it.copy(validation = res, notice = "Fill the highlighted fields first: " + res.missing.take(3).joinToString { k -> tx.label(k) }) }
                return@step
            }
            repo.saveForm(id, form, Actor.User(settings().reporter.empId), rounds, emergencyFlag)
            repo.saveRecommendations(id, ui.value.recommendations)
            val ref = editingRef ?: repo.submit(id, Actor.User(settings().reporter.empId))
            val wasEdit = editingRef != null
            update { it.copy(state = ConvState.SUBMITTED, submittedRef = ref, otherIncidents = form.otherIncidents, notice = null) }
            if (!wasEdit) runCatching { proposeDuplicate(id) }
        }
    }

    // ---- Extra 3: multi-witness merging. The app only asks; it never links two reports on its own.
    private suspend fun proposeDuplicate(id: String) {
        if (!cfg.app.features.multiWitness) return
        val mine = repo.load(id)?.let { WitnessAccount.of(it.meta, it.form) } ?: return
        val now = clock().toEpochMilli()
        val others = repo.recentSubmitted(now - cfg.app.multiWitness.lookbackH * 3_600_000L).map { WitnessAccount.of(it.meta, it.form) }
        val best = DuplicateFinder.find(mine, others, cfg.app.multiWitness, now).firstOrNull() ?: return
        val shared = mine.types.firstOrNull { it in best.account.types } ?: return
        val label = tx.category(shared)?.label ?: shared
        // link to the case, not to another linked account
        val rootId = best.account.rootId
        val rootRef = if (rootId == best.account.id) best.account.refNo else repo.load(rootId)?.meta?.refNo
        update { it.copy(duplicate = DuplicatePrompt(id, rootId, rootRef, DuplicateFinder.question(best, label), best.score)) }
    }

    fun onLinkWitness() {
        val d = ui.value.duplicate ?: return
        scope.launch {
            repo.linkWitness(d.incidentId, d.parentId, Actor.User(settings().reporter.empId))
            update { it.copy(duplicate = null, linkedTo = d.parentRef ?: "the earlier report") }
        }
    }

    fun onKeepSeparate() { update { it.copy(duplicate = null) } }

    /** Discard: the draft stays in the DB (§8.2). */
    fun onCancel() {
        // a report the reporter closed must not be picked up again at the next launch (the response clock would reopen it)
        val id = incidentId
        if (id != null && responseStart != null && editingRef == null) {
            val closed = ResponseAction(CLOSED, "Report closed", clock().toEpochMilli(), "USER")
            scope.launch { runCatching { repo.addResponseAction(id, closed) } }
        }
        cancelAll(); audio.cancel(); tts.stop(); reset()
    }

    fun onEmergencyCall() { if (ui.value.response != null) onResponseStep(ResponseClock.ERT) else _events.tryEmit(UiEvent.Dial(settings().ertNumber)) }

    // =========================================================================================
    // Extra 1 — response clock (runs alongside reporting, never blocks it)
    // =========================================================================================

    /** A step on the response clock card was tapped: log it with its time and add it to "actions taken". */
    fun onResponseStep(stepId: String) {
        val st = ui.value.response?.steps?.firstOrNull { it.id == stepId } ?: return
        if (stepId == ResponseClock.ERT) _events.tryEmit(UiEvent.Dial(settings().ertNumber))      // the dialler opens every time it is tapped
        if (st.done) return
        val action = ResponseAction(stepId, st.label, clock().toEpochMilli(), "TAP")
        responseActions += action
        val label = if (stepId == ResponseClock.ERT) "Called the emergency team (ERT)" else st.label.trimEnd('.')
        val ex = form.state("actions_taken")
        val list = (ex?.value.let { v -> (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } } ?: emptyList()) + label
        form = form.with(FieldState("actions_taken", listValue(list.distinct()), if (ex?.status == FieldStatus.USER_EDITED) FieldStatus.USER_EDITED else FieldStatus.FILLED, FieldSource.USER, ex?.turnId, true, ex?.askedCount ?: 0))
        update { it.copy(form = form, response = it.response?.copy(reminder = if (stepId == ResponseClock.ERT) false else it.response.reminder)) }
        refreshResponseUi()
        scope.launch { incidentId?.let { id -> runCatching { repo.addResponseAction(id, action); repo.saveForm(id, form, Actor.User(settings().reporter.empId), rounds, emergencyFlag) } } }
    }

    private fun maybeStartResponseClock() {
        if (!cfg.app.features.responseClock) return
        if (responseStart == null) {
            if (!ResponseClock.shouldStart(form, ui.value.emergency != null)) return
            val start = clock().toEpochMilli()
            responseStart = start
            scope.launch { incidentId?.let { runCatching { repo.setResponseStart(it, start) } } }
            startErtReminder(cfg.app.responseClock.ertReminderS * 1000L)
        }
        refreshResponseUi()
    }

    private fun startErtReminder(afterMs: Long) {
        reminderJob?.cancel()
        reminderJob = scope.launch {
            delay(afterMs.coerceAtLeast(0))
            if (responseActions.any { it.stepId == ResponseClock.ERT } || responseStart == null) return@launch
            update { it.copy(response = it.response?.copy(reminder = true)) }                  // card pulses amber
            var waited = 0
            while (ui.value.state == ConvState.LISTENING && waited < 30_000) { delay(500); waited += 500 }   // never talk into an open mic
            if (settings().speakQuestions && responseActions.none { it.stepId == ResponseClock.ERT }) tts.speak(ResponseClock.REMINDER, form.lang)
        }
    }

    private fun refreshResponseUi() {
        val start = responseStart ?: return
        val steps = ResponseClock.steps(cfg.sop, form.incidentType ?: hintCategory, settings().ertNumber, responseActions)
        update { it.copy(response = ResponseClockUi(start, steps, it.response?.reminder ?: false, settings().ertNumber)) }
    }

    /** After an app restart: pick up a draft whose response clock was running (started within the last hour). */
    fun resumeActiveResponse() {
        if (!cfg.app.features.responseClock || ui.value.state != ConvState.IDLE) return
        step {
            val id = repo.activeResponseDraft(clock().toEpochMilli() - 60 * 60_000L) ?: return@step
            val d = repo.load(id) ?: return@step
            val start = d.meta.responseStartedAt ?: return@step
            if (d.responseActions.any { it.stepId == CLOSED }) return@step
            incidentId = id; form = d.form; rounds = d.meta.followupRounds; emergencyFlag = d.meta.emergencyFlag
            responseStart = start; responseActions.clear(); responseActions.addAll(d.responseActions)
            d.turns.filter { it.role == "USER" }.forEach { userTexts.append(it.text).append(' ') }
            update { it.copy(incidentId = id, form = form, recommendations = d.recommendations.map { r -> Recommendation(r.sopId, r.text, r.immediate, r.source, r.done) }, notice = "Picked up the report that was in progress.") }
            refreshResponseUi()
            startErtReminder(cfg.app.responseClock.ertReminderS * 1000L - (clock().toEpochMilli() - start))
            gotoManualFill(speak = false)
        }
    }
    fun onEmergencyDismiss() { update { it.copy(emergency = null) } }

    fun onRetry() { retryAction?.let { act -> step { update { u -> u.copy(error = null) }; act() } } }

    /** From ERROR_ENGINE / ERROR_PARSE: give up on the model and fill the form by hand. */
    fun onManualFallback() { step { ensureDescriptionFromTranscript(); gotoManualFill(speak = false) } }

    fun onReportAnother(prefill: String? = null) = startNew(prefill)

    // =========================================================================================
    // Listening / transcribing
    // =========================================================================================

    private suspend fun listenNarration() {
        val id = ensureDraft()
        update { it.copy(state = ConvState.LISTENING, listeningFor = ListenKind.NARRATION, error = null, notice = null, level = 0f, elapsedMs = 0) }
        val maxMs = cfg.app.asr.maxNarrationS * 1000L // rev 1.1 R1-08: one Whisper window (30 s)
        when (val r = audio.listen(ListenKind.NARRATION, maxMs, settings().endSilenceMs, id) { lvl, ms -> update { u -> u.copy(level = lvl, elapsedMs = ms) } }) {
            is ListenResult.Speech -> transcribeAndHandle(r, narration = true)
            ListenResult.NoSpeech -> {
                if (!narrationReprompted) {
                    // say it once and listen once more; a second silence goes back to the start
                    narrationReprompted = true
                    val msg = if (form.lang == "en" || form.lang.isBlank()) "Sorry, I didn't catch that." else "Maaf kijiye, sunaai nahi diya."
                    say(msg); update { it.copy(state = ConvState.ASKING) }
                    if (settings().speakQuestions) tts.speak(msg, form.lang)
                    markSpeakingDone(); delay(conv.ttsPostGapMs.toLong())
                    listenNarration()
                } else {
                    runCatching { persist() }
                    reset()
                    update { it.copy(notice = "Sorry, I didn't catch that. Tap the button and try again.") }
                }
            }
            ListenResult.Cancelled -> {}
            is ListenResult.Failure -> { runCatching { persist() }; update { it.copy(state = ConvState.ERROR_AUDIO, error = r.message) } }
        }
    }

    private suspend fun listenFollowup() {
        val id = ensureDraft()
        update { it.copy(state = ConvState.LISTENING, listeningFor = ListenKind.FOLLOWUP, level = 0f, elapsedMs = 0) }
        when (val r = audio.listen(ListenKind.FOLLOWUP, 30_000, settings().followupEndSilenceMs, id) { lvl, ms -> update { u -> u.copy(level = lvl, elapsedMs = ms) } }) {
            is ListenResult.Speech -> transcribeAndHandle(r, narration = false)
            ListenResult.NoSpeech -> {
                val q = question ?: return
                if (!repromptedNoSpeech) {
                    repromptedNoSpeech = true
                    val text = reprompt("no_speech", q.text(form.lang))
                    say(text); speakThenListen(text)
                } else {
                    // second silence: treat as skipped (fields stay MISSING, round counted)
                    rounds++; validateAndAdvance()
                }
            }
            ListenResult.Cancelled -> {}
            is ListenResult.Failure -> update { it.copy(state = ConvState.ERROR_AUDIO, error = r.message) }
        }
    }

    private suspend fun transcribeAndHandle(r: ListenResult.Speech, narration: Boolean) {
        update { it.copy(state = ConvState.TRANSCRIBING) }
        val mode = when (cfg.app.asr.languageMode) { "force_en" -> LangMode.FORCE_EN; "force_hi" -> LangMode.FORCE_HI; else -> LangMode.AUTO_EN_HI }
        val t0 = System.nanoTime()
        val asr = withEngineRetry { engine.transcribe(r.pcm, 16_000, mode) } ?: return
        val asrMs = (System.nanoTime() - t0) / 1_000_000
        update { it.copy(debug = it.debug.copy(asrMs = asrMs, lastTask = "ASR")) }
        val text = asr.text.trim()
        if (text.isEmpty()) {
            if (narration) update { it.copy(state = ConvState.IDLE, notice = "I couldn't make out any words. Please try again.") }
            else {
                // treat as no speech
                if (!repromptedNoSpeech) { repromptedNoSpeech = true; val q = question ?: return; val t = reprompt("no_speech", q.text(form.lang)); say(t); speakThenListen(t) }
                else { rounds++; validateAndAdvance() }
            }
            return
        }
        if (narration) processText(text, fromVoice = true, r.audioPath, r.durationMs, asrMs)
        else handleAnswer(text, r.audioPath, r.durationMs, asrMs)
    }

    // =========================================================================================
    // Narration → extraction
    // =========================================================================================

    private suspend fun processText(text: String, fromVoice: Boolean, audioPath: String?, audioMs: Long?, asrMs: Long?) {
        ensureDraft()
        narrationText = text
        lastFromVoice = fromVoice
        update { it.copy(notice = null) }
        scanEmergency(text)
        var finalText = text
        if (fromVoice) {
            // §8.2 TRANSCRIPT_REVIEW: editable, auto-proceed after 2 s without edits
            edited = false; transcriptFocused = false; cont = CompletableDeferred()
            update { it.copy(state = ConvState.TRANSCRIPT_REVIEW, transcriptDraft = text, countdownMs = conv.transcriptAutoproceedMs) }
            var remaining = conv.transcriptAutoproceedMs
            while (remaining > 0 && cont?.isCompleted != true) {
                delay(100)
                if (edited || transcriptFocused) { edited = false; remaining = conv.transcriptAutoproceedMs } else remaining -= 100
                update { it.copy(countdownMs = remaining.coerceAtLeast(0)) }
            }
            finalText = narrationText.trim().ifEmpty { text }
        }
        userTexts.append(finalText).append(' '); heard(finalText)
        val id = incidentId!!
        repo.appendTurn(id, TurnRecord("USER", "NARRATION", textRaw = text, textEdited = finalText.takeIf { it != text }, lang = form.lang,
            audioPath = audioPath, audioMs = audioMs, asrMs = asrMs))
        addBubble(BubbleRole.USER, finalText)
        SmallTalk.reporterName(finalText)?.let { rememberReporter(it) }
        // small talk, a joke, a cut-off sentence: nothing to file. Ask what happened instead of filling the form with it.
        // The second try is taken at its word if it says anything of substance (the rules must never block a real report).
        val chatter = SmallTalk.isNoIncident(finalText, ::hasIncidentCue)
        if (chatter && !(noIncidentCount >= 1 && finalText.trim().split(Regex("\\s+")).size >= 6)) { noIncident(finalText); return }
        extract(finalText)
    }

    private fun hasIncidentCue(text: String) = rules.hasIncidentCue(text) || emergencyDetector.scan(text) != null

    private suspend fun rememberReporter(name: String) {
        if (name == spokenReporter) return
        spokenReporter = name
        incidentId?.let { repo.setReporter(it, settings().reporter.copy(name = name)) }
        update { it.copy(reporterName = name) }
        _events.tryEmit(UiEvent.ReporterName(name))
    }

    /** Nothing reportable was said: keep the form empty, say so, and listen (or wait for typing) again. Gives up after three tries. */
    private suspend fun noIncident(text: String) {
        noIncidentCount++
        val lang = LangDetector.detect(text)
        userTexts.clear(); narrationText = ""
        if (noIncidentCount > 3) {
            val keepName = spokenReporter
            // nothing was reported: do not leave an empty draft behind
            incidentId?.let { id -> if (form.fields.values.none { it.value != null }) runCatching { repo.delete(id, Actor.System) } }
            reset(); spokenReporter = keepName
            update { it.copy(notice = "No incident was reported. Tap the button when you are ready.", reporterName = keepName) }
            return
        }
        form = form.copy(lang = lang)
        val msg = SmallTalk.prompt(lang, spokenReporter.takeIf { noIncidentCount == 1 })
        id()?.let { repo.appendTurn(it, TurnRecord("ASSISTANT", "NO_INCIDENT", textRaw = msg, lang = lang)) }
        say(msg)
        if (lastFromVoice) {
            update { it.copy(state = ConvState.ASKING, form = form) }
            if (settings().speakQuestions) tts.speak(msg, lang)
            markSpeakingDone()
            delay(conv.ttsPostGapMs.toLong())
            listenNarration()
        } else { markSpeakingDone(); update { it.copy(state = ConvState.TYPING, question = null, form = form) } }
    }

    private suspend fun extract(text: String) {
        retryAction = { extract(text) }
        update { it.copy(state = ConvState.EXTRACTING, streamingFields = emptySet(), error = null) }
        val gen = generateWithRetry(LlmTask.EXTRACT, prompts.extract(text), SamplerName.GREEDY, cfg.app.llm.maxTokens.extract, preview = true) { rules.extract(text).toString() } ?: return
        var call = parseWithRepair(gen.text, Tools.FILL, null)
        // the model itself found nothing to file (no type, no description)
        val said = (call?.fields?.get("description") as? JsonPrimitive)?.content
        val nothing = call != null && (SmallTalk.isSentinel(said) || (call.fields["incident_type"] == null && said == null && !hasIncidentCue(text)))
        if (nothing) { form = IncidentForm(lang = form.lang); update { it.copy(streamingFields = emptySet(), form = form) }; noIncident(text); return }
        if (call == null) { // §13.7: unparseable after one repair → manual fill with the transcript
            ensureDescriptionFromTranscript()
            update { it.copy(state = ConvState.ERROR_PARSE, error = "Couldn't understand fully — please check the form.", streamingFields = emptySet()) }
            persist()
            gotoManualFill(speak = false)
            return
        }
        val merged = merger.merge(form, call, FieldSource.LLM_EXTRACT, null, userTexts.toString(), clock())
        form = merger.applyTimeDefault(merged.form, clock())
        form = Evidence.attach(form, userTurns, tx, clock())
        // answer in the language the reporter used (their words decide, not the model's label)
        form = form.copy(lang = LangDetector.ofSpeech(text))
        // the description stays in the reporter's own words: a model rewrite that drifted from them is replaced by the lightly cleaned narration
        form.state("description")?.takeIf { it.status != FieldStatus.USER_EDITED && it.status != FieldStatus.MISSING }?.let { st ->
            val kept = DescriptionStyle.keep(form.str("description"), text)
            if (kept.isNotBlank() && kept != form.str("description")) form = form.with(st.copy(value = JsonPrimitive(kept)))
        }
        ensureDescriptionFromTranscript()
        rememberTool(gen, "Form updated: " + merged.changed.filter { it != "lang" }.joinToString { tx.label(it) })
        update { it.copy(streamingFields = emptySet(), form = form) }
        persist()
        validateAndAdvance()
    }

    // =========================================================================================
    // Validate / ask / patch
    // =========================================================================================

    private suspend fun validateAndAdvance() {
        update { it.copy(state = ConvState.VALIDATING) }
        val res = Validator.validate(form, tx, clock())
        val critical = form.str("severity") == "CRITICAL"
        if (critical) raiseRed()
        update { it.copy(validation = res, form = form, round = rounds) }
        maybeStartResponseClock()
        persist()
        // §10.7, emergency: do not hold the reporter in a question loop. Ask only where it is (once, if missing), then go
        // to review, where the response clock and Call ERT are pinned and every other required field waits as "Required".
        val emergency = critical || ui.value.response != null || ui.value.emergency?.level == EmergencyLevel.RED
        when {
            res.complete -> goRecommend(fromConversation = true)
            emergency && ("location" !in res.missing || emergencyLocationAsked) -> goRecommend(fromConversation = true)
            rounds >= conv.maxFollowupRounds -> gotoManualFill(speak = true)
            else -> ask(res, emergency)
        }
    }

    private suspend fun ask(res: ValidationResult, emergencyMode: Boolean) {
        if (emergencyMode) emergencyLocationAsked = true
        val q = Validator.nextQuestion(if (emergencyMode) res.copy(missing = listOf("location")) else res, form, tx, emergencyMode)
        if (q == null) { if (emergencyMode) goRecommend(fromConversation = true) else gotoManualFill(speak = true); return }
        form = merger.markAsked(form, q.fields)
        question = q
        repromptedNoSpeech = false
        val noFill = q.fields == lastAskedFields
        lastAskedFields = q.fields
        val base = q.text(form.lang)
        val text = if (noFill) reprompt("no_fill", base, q.fields.joinToString { tx.label(it) }) else base
        id()?.let { repo.appendTurn(it, TurnRecord("ASSISTANT", "FOLLOWUP_Q", textRaw = text, lang = form.lang, askedFields = q.fields)) }
        persist()
        update { it.copy(state = ConvState.ASKING, question = q, form = form) }
        say(text)
        speakThenListen(text)
    }

    private suspend fun speakThenListen(text: String) {
        update { it.copy(state = ConvState.ASKING) }
        // If TTS is not ready the speaker plays a short chime and returns at once (PRD §17.2); the question stays on screen.
        if (settings().speakQuestions) tts.speak(text, form.lang)
        markSpeakingDone()
        delay(conv.ttsPostGapMs.toLong()) // never open the mic while TTS is speaking
        listenFollowup()
    }

    private suspend fun handleAnswer(text: String, audioPath: String?, audioMs: Long?, asrMs: Long?) {
        val q = question ?: return
        // a full sentence in the other language switches the language of the next question; "yes" or "haan" does not
        if (text.trim().split(Regex("\\s+")).size >= 3) form = form.copy(lang = LangDetector.ofSpeech(text))
        retryAction = { handleAnswer(text, audioPath, audioMs, asrMs) }
        val id = ensureDraft()
        scanEmergency(text)
        userTexts.append(text).append(' '); heard(text)
        repo.appendTurn(id, TurnRecord("USER", "FOLLOWUP_A", textRaw = text, lang = form.lang, audioPath = audioPath, audioMs = audioMs, asrMs = asrMs, askedFields = q.fields))
        addBubble(BubbleRole.USER, text)
        update { it.copy(state = ConvState.PATCHING, error = null) }
        val prompt = prompts.patch(form, q.text(form.lang), q.fields, text)
        val gen = generateWithRetry(LlmTask.PATCH, prompt, SamplerName.GREEDY, cfg.app.llm.maxTokens.patch, preview = false) { rules.patch(q.fields, text).toString() } ?: return
        val call = parseWithRepair(gen.text, Tools.UPDATE, form.types)
            ?.let { c -> if (SmallTalk.isSentinel((c.fields["description"] as? JsonPrimitive)?.content)) c.copy(fields = emptyMap()) else c }   // sentinel in an answer = no new info, never a description
        if (call != null) {
            val m = merger.merge(form, call, FieldSource.LLM_PATCH, null, userTexts.toString(), clock())
            form = Evidence.attach(m.form, userTurns, tx, clock(), changed = m.changed, answerTurn = userTurns.lastIndex)
            rememberTool(gen, "Form updated: " + (m.changed.filter { it != "lang" }).joinToString { tx.label(it) }.ifBlank { "nothing new" })
        } else {
            rememberTool(gen, "Couldn't read that answer")
        }
        question = null
        rounds++
        update { it.copy(form = form, question = null) }
        persist()
        validateAndAdvance()
    }

    // =========================================================================================
    // Recommendations / review
    // =========================================================================================

    private suspend fun goRecommend(fromConversation: Boolean) {
        update { it.copy(state = ConvState.RECOMMENDING) }
        val recs = sop.recommend(form)
        id()?.let { repo.saveRecommendations(it, recs) }
        update { it.copy(recommendations = recs, state = ConvState.REVIEW, validation = Validator.validate(form, tx, clock()), form = form) }
        if (fromConversation && !spokeComplete && settings().speakQuestions && tts.ready.value) {
            spokeComplete = true
            val phrase = tx.reprompts["complete"]?.text(form.lang)
            if (phrase != null) scope.launch { tts.speak(phrase, form.lang) } // don't block the review screen
        }
    }

    private suspend fun gotoManualFill(speak: Boolean) {
        val res = Validator.validate(form, tx, clock())
        update { it.copy(state = ConvState.MANUAL_FILL, validation = res, form = form, question = null, error = if (it.state == ConvState.ERROR_PARSE) it.error else null) }
        persist()
        if (speak && settings().speakQuestions && tts.ready.value) {
            tx.reprompts["round_limit"]?.text(form.lang)?.let { scope.launch { tts.speak(it, form.lang) } }
        }
        if (res.complete) goRecommend(fromConversation = false)
    }

    private fun afterReviewEdit(key: String, what: String) {
        val res = Validator.validate(form, tx, clock())
        update { it.copy(form = form, validation = res, notice = null) }
        maybeStartResponseClock()
        step {
            persist(actor = Actor.User(settings().reporter.empId))
            if (ui.value.state == ConvState.MANUAL_FILL && res.complete) goRecommend(fromConversation = false)
        }
    }

    // =========================================================================================
    // Emergency (§10.7)
    // =========================================================================================

    private fun scanEmergency(text: String) {
        val hit = emergencyDetector.scan(text) ?: return
        if (ui.value.emergency?.level == EmergencyLevel.RED) return
        emergencyFlag = true
        val steps = sop.emergencySteps(form, hit.categoryHint ?: form.incidentType).map { it.text }
        update { it.copy(emergency = EmergencyBanner(EmergencyLevel.AMBER, steps, settings().ertNumber, hit.categoryHint)) }
        hintCategory = hit.categoryHint
        maybeStartResponseClock()
        if (settings().speakQuestions && tts.ready.value) {
            val phrase = tx.reprompts["emergency"]?.text(form.lang)
            if (phrase != null) scope.launch { tts.speak(phrase, form.lang) }
        }
    }

    private fun raiseRed() {
        emergencyFlag = true
        val cur = ui.value.emergency
        if (cur?.level == EmergencyLevel.RED) return
        val steps = sop.emergencySteps(form).map { it.text }.ifEmpty { cur?.steps ?: emptyList() }
        update { it.copy(emergency = EmergencyBanner(EmergencyLevel.RED, steps, settings().ertNumber, cur?.categoryHint)) }
    }

    // =========================================================================================
    // LLM plumbing
    // =========================================================================================

    private class Gen(val text: String, val stats: GenStats?, val ms: Long)

    /** Streams one generation. Empty output = engine error, never a content failure (§13.7). */
    private suspend fun generateOnce(task: LlmTask, prompt: String, sampler: SamplerName, maxTokens: Int, preview: Boolean): Gen {
        val sb = StringBuilder(); var stats: GenStats? = null; var n = 0
        val t0 = System.nanoTime()
        generating = true
        try {
        engine.generate(GenRequest(task, prompt, sampler, maxTokens)).collect { ev ->
            when (ev) {
                is GenEvent.Token -> {
                    sb.append(ev.piece); n++
                    if (preview && n % 5 == 0) previewPartial(sb.toString())
                }
                is GenEvent.Done -> { stats = ev.stats; if (sb.isBlank() && !ev.text.isNullOrBlank()) sb.append(ev.text) }
                is GenEvent.Error -> throw EngineException(ev.error)
            }
        }
        } finally { generating = false }
        if (sb.isBlank()) throw EngineException(EngineError("IV-E601", "Empty output from the engine", retryable = true))
        return Gen(sb.toString(), stats, (System.nanoTime() - t0) / 1_000_000)
    }

    private fun previewPartial(partial: String) {
        val out = parser.parse(partial, Tools.FILL) as? ParseOutcome.Ok ?: return
        // never show the NO_INCIDENT sentinel (or its first letters) as a description while it streams in
        (out.call.fields["description"] as? JsonPrimitive)?.content?.let { d -> if (d.isNotEmpty() && SmallTalk.SENTINEL.startsWith(d.trim().uppercase())) return }
        val pv = merger.merge(form.copy(lang = form.lang), out.call, FieldSource.LLM_EXTRACT, null, userTexts.toString(), clock()).form
        update { it.copy(form = pv, streamingFields = out.call.fields.keys) }
    }

    /**
     * One generation with the §31.3 retries. If the model still fails and [fallback] is given, the keyword rules answer
     * this one turn instead (same tool-call format) and the screen says so; the next turn tries the model again.
     */
    private suspend fun generateWithRetry(task: LlmTask, prompt: String, sampler: SamplerName, maxTokens: Int, preview: Boolean, fallback: (() -> String)? = null): Gen? {
        val gen = withEngineRetry(giveUpQuietly = fallback != null) {
            generateOnce(task, prompt, sampler, maxTokens, preview).also { g ->
                update { it.copy(debug = it.debug.copy(llmMs = g.ms, tokensOut = g.stats?.tokensOut ?: 0, decodeTps = g.stats?.decodeTps ?: 0.0, lastToolJson = g.text, lastTask = task.name)) }
            }
        }
        if (gen != null) { if (ui.value.notice == FALLBACK_NOTICE) update { it.copy(notice = null) }; return gen }   // the model is back
        if (fallback == null) return null
        val text = fallback()
        update { it.copy(notice = FALLBACK_NOTICE, streamingFields = emptySet(), debug = it.debug.copy(llmMs = 0, tokensOut = 0, decodeTps = 0.0, lastToolJson = text, lastTask = task.name + " (rules)")) }
        return Gen(text, null, 0)
    }

    /** §31.3: retryable engine errors retry after 3/7/15 s, then surface ERROR_ENGINE. Returns null after reporting. */
    private suspend fun <T> withEngineRetry(giveUpQuietly: Boolean = false, block: suspend () -> T): T? {
        var attempt = 0
        while (true) {
            try { return block() }
            catch (e: CancellationException) { throw e }
            catch (e: EngineException) {
                if (e.error.retryable && attempt < retryDelaysMs.size) {
                    update { it.copy(notice = "Engine busy — retrying (${attempt + 1}/${retryDelaysMs.size})…") }
                    delay(retryDelaysMs[attempt]); attempt++
                } else {
                    if (!giveUpQuietly) {
                        update { it.copy(state = ConvState.ERROR_ENGINE, error = "On-device AI engine unavailable (${e.error.code}).", retryable = true, notice = null, streamingFields = emptySet()) }
                        runCatching { persist() }      // the draft is saved whatever happens
                    }
                    return null
                }
            }
        }
    }

    private suspend fun parseWithRepair(raw: String, tool: String, types: List<String>?): ParsedCall? {
        (parser.parse(raw, tool, types) as? ParseOutcome.Ok)?.let { return it.call }
        val rep = try { generateOnce(LlmTask.REPAIR, prompts.repair(tool, raw), SamplerName.GREEDY, cfg.app.llm.maxTokens.repair, false) } catch (e: EngineException) { return null }
        return (parser.parse(rep.text, tool, types) as? ParseOutcome.Ok)?.call
    }

    // =========================================================================================
    // helpers
    // =========================================================================================

    private fun reprompt(kind: String, question: String, fields: String = ""): String =
        (tx.reprompts[kind]?.text(form.lang) ?: "{question}").replace("{question}", question).replace("{fields}", fields)

    private fun id() = incidentId

    private suspend fun ensureDraft(): String {
        incidentId?.let { return it }
        val id = repo.createDraft(settings().reporter.let { r -> spokenReporter?.let { r.copy(name = it) } ?: r })
        incidentId = id
        update { it.copy(incidentId = id) }
        return id
    }

    private fun ensureDescriptionFromTranscript() {
        val st = form.state("description")
        if (st == null || st.status == FieldStatus.MISSING) {
            val t = narrationText.trim().ifEmpty { userTexts.toString().trim() }
            if (t.isNotEmpty()) form = form.with(FieldState("description", JsonPrimitive(t.take(300)), FieldStatus.DEFAULTED, FieldSource.SYSTEM))
        }
        if (form.state("occurred_at")?.status.let { it == null || it == FieldStatus.MISSING }) form = merger.applyTimeDefault(form, clock())
    }

    private suspend fun persist(actor: Actor = Actor.Llm) {
        val id = incidentId ?: return
        repo.saveForm(id, form, actor, rounds, emergencyFlag)
    }

    private suspend fun rememberTool(g: Gen, note: String) {
        val id = incidentId ?: return
        repo.appendTurn(id, TurnRecord("SYSTEM", "NOTE", textRaw = note, lang = form.lang, llmMs = g.ms, llmToolJson = g.text,
            llmStatsJson = g.stats?.let { "{\"tokens_out\":${it.tokensOut},\"decode_tps\":${it.decodeTps}}" }))
        addBubble(BubbleRole.SYSTEM, note)
    }

    private fun heard(text: String) { userTurns += text; update { it.copy(userTurns = userTurns.toList()) } }

    private fun say(text: String) { addBubble(BubbleRole.ASSISTANT, text, speaking = true) }
    private fun markSpeakingDone() = update { u -> u.copy(timeline = u.timeline.map { if (it.speaking) it.copy(speaking = false) else it }) }
    private fun addBubble(role: BubbleRole, text: String, speaking: Boolean = false) =
        update { it.copy(timeline = it.timeline + Bubble(bubbleSeq++, role, text, speaking)) }

    private fun update(f: (ConversationUiState) -> ConversationUiState) = _ui.update(f)

    private fun step(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: EngineException) { update { it.copy(state = ConvState.ERROR_ENGINE, error = e.message, retryable = e.error.retryable) } }
            catch (e: Exception) { update { it.copy(state = ConvState.ERROR_ENGINE, error = "Unexpected error: ${e.message}", retryable = false) } }
        }
    }

    private fun cancelAll() {
        // a native generation keeps running after its collector is cancelled → tell the engine to stop (frees the NPU)
        if (generating) scope.launch { runCatching { engine.abort() } }
        job?.cancel(); job = null; cont?.complete(Unit)
    }

    private fun reset() {
        form = IncidentForm(); incidentId = null; rounds = 0; emergencyFlag = false; question = null; lastAskedFields = emptyList()
        repromptedNoSpeech = false; emergencyLocationAsked = false; noIncidentCount = 0; spokenReporter = null; narrationReprompted = false; lastFromVoice = true; retryAction = null; userTexts.clear(); userTurns.clear(); narrationText = ""; spokeComplete = false; bubbleSeq = 0; editingRef = null
        responseStart = null; responseActions.clear(); hintCategory = null; reminderJob?.cancel(); reminderJob = null
        _ui.value = ConversationUiState()
    }

    companion object { private const val CLOSED = "CLOSED"; const val FALLBACK_NOTICE = "Using offline fallback" }
}
