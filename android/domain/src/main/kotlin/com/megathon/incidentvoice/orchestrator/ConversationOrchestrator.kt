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
    private var retryAction: (suspend () -> Unit)? = null
    private val userTexts = StringBuilder() // everything the speaker said (grounding)
    private var narrationText = ""
    private var edited = false
    private var cont: CompletableDeferred<Unit>? = null
    private var spokeComplete = false
    private var editingRef: String? = null
    @Volatile private var generating = false

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
    fun onContinue() { cont?.complete(Unit) }

    fun onReviewNow() {
        if (ui.value.state in setOf(ConvState.SUBMITTED, ConvState.IDLE)) return
        cancelAll(); audio.cancel(); tts.stop()
        step { gotoManualFill(speak = false) }
    }

    fun onFieldEdited(key: String, value: JsonElement?) {
        form = merger.userEdit(form, key, value, clock())
        form = merger.applyRules(form, clock()).first
        afterReviewEdit(key, if (value == null) "cleared" else "edited")
    }

    fun onFieldUnknown(key: String) {
        form = merger.userUnknown(form, key)
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
            d.turns.filter { it.role == "USER" }.forEach { userTexts.append(it.text).append(' ') }
            update {
                it.copy(state = ConvState.REVIEW, incidentId = id, form = form, validation = Validator.validate(form, tx, clock()),
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
            update { it.copy(state = ConvState.SUBMITTED, submittedRef = ref, otherIncidents = form.otherIncidents, notice = null) }
        }
    }

    /** Discard: the draft stays in the DB (§8.2). */
    fun onCancel() { cancelAll(); audio.cancel(); tts.stop(); reset() }

    fun onEmergencyCall() { _events.tryEmit(UiEvent.Dial(settings().ertNumber)) }
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
            ListenResult.NoSpeech -> update { it.copy(state = ConvState.IDLE, notice = "I didn't hear anything. Tap the mic and try again.") }
            ListenResult.Cancelled -> {}
            is ListenResult.Failure -> update { it.copy(state = ConvState.ERROR_AUDIO, error = r.message) }
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
        update { it.copy(notice = null) }
        scanEmergency(text)
        var finalText = text
        if (fromVoice) {
            // §8.2 TRANSCRIPT_REVIEW: editable, auto-proceed after 2 s without edits
            edited = false; cont = CompletableDeferred()
            update { it.copy(state = ConvState.TRANSCRIPT_REVIEW, transcriptDraft = text, countdownMs = conv.transcriptAutoproceedMs) }
            var remaining = conv.transcriptAutoproceedMs
            while (remaining > 0 && cont?.isCompleted != true) {
                delay(100)
                if (edited) { edited = false; remaining = conv.transcriptAutoproceedMs } else remaining -= 100
                update { it.copy(countdownMs = remaining.coerceAtLeast(0)) }
            }
            finalText = narrationText.trim().ifEmpty { text }
        }
        userTexts.append(finalText).append(' ')
        val id = incidentId!!
        repo.appendTurn(id, TurnRecord("USER", "NARRATION", textRaw = text, textEdited = finalText.takeIf { it != text }, lang = form.lang,
            audioPath = audioPath, audioMs = audioMs, asrMs = asrMs))
        addBubble(BubbleRole.USER, finalText)
        extract(finalText)
    }

    private suspend fun extract(text: String) {
        retryAction = { extract(text) }
        update { it.copy(state = ConvState.EXTRACTING, streamingFields = emptySet(), error = null) }
        val gen = generateWithRetry(LlmTask.EXTRACT, prompts.extract(text), SamplerName.GREEDY, cfg.app.llm.maxTokens.extract, preview = true) ?: return
        var call = parseWithRepair(gen.text, Tools.FILL, null)
        if (call == null) { // §13.7: unparseable after one repair → manual fill with the transcript
            ensureDescriptionFromTranscript()
            update { it.copy(state = ConvState.ERROR_PARSE, error = "Couldn't understand fully — please check the form.", streamingFields = emptySet()) }
            persist()
            gotoManualFill(speak = false)
            return
        }
        val merged = merger.merge(form, call, FieldSource.LLM_EXTRACT, null, userTexts.toString(), clock())
        form = merger.applyTimeDefault(merged.form, clock())
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
        persist()
        when {
            res.complete -> goRecommend(fromConversation = true)
            critical && res.missing.none { tx.field(it)?.isCommon == true } -> goRecommend(fromConversation = true) // §10.7: details later in review
            rounds >= conv.maxFollowupRounds -> gotoManualFill(speak = true)
            else -> ask(res, critical)
        }
    }

    private suspend fun ask(res: ValidationResult, emergencyMode: Boolean) {
        val q = Validator.nextQuestion(res, form, tx, emergencyMode)
        if (q == null) { gotoManualFill(speak = true); return }
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
        retryAction = { handleAnswer(text, audioPath, audioMs, asrMs) }
        val id = ensureDraft()
        scanEmergency(text)
        userTexts.append(text).append(' ')
        repo.appendTurn(id, TurnRecord("USER", "FOLLOWUP_A", textRaw = text, lang = form.lang, audioPath = audioPath, audioMs = audioMs, asrMs = asrMs, askedFields = q.fields))
        addBubble(BubbleRole.USER, text)
        update { it.copy(state = ConvState.PATCHING, error = null) }
        val prompt = prompts.patch(form, q.text(form.lang), q.fields, text)
        val gen = generateWithRetry(LlmTask.PATCH, prompt, SamplerName.GREEDY, cfg.app.llm.maxTokens.patch, preview = false) ?: return
        val call = parseWithRepair(gen.text, Tools.UPDATE, form.types)
        if (call != null) {
            val m = merger.merge(form, call, FieldSource.LLM_PATCH, null, userTexts.toString(), clock())
            form = m.form
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
        val pv = merger.merge(form.copy(lang = form.lang), out.call, FieldSource.LLM_EXTRACT, null, userTexts.toString(), clock()).form
        update { it.copy(form = pv, streamingFields = out.call.fields.keys) }
    }

    private suspend fun generateWithRetry(task: LlmTask, prompt: String, sampler: SamplerName, maxTokens: Int, preview: Boolean): Gen? =
        withEngineRetry {
            generateOnce(task, prompt, sampler, maxTokens, preview).also { g ->
                update { it.copy(debug = it.debug.copy(llmMs = g.ms, tokensOut = g.stats?.tokensOut ?: 0, decodeTps = g.stats?.decodeTps ?: 0.0, lastToolJson = g.text, lastTask = task.name)) }
            }
        }

    /** §31.3: retryable engine errors retry after 3/7/15 s, then surface ERROR_ENGINE. Returns null after reporting. */
    private suspend fun <T> withEngineRetry(block: suspend () -> T): T? {
        var attempt = 0
        while (true) {
            try { return block() }
            catch (e: CancellationException) { throw e }
            catch (e: EngineException) {
                if (e.error.retryable && attempt < retryDelaysMs.size) {
                    update { it.copy(notice = "Engine busy — retrying (${attempt + 1}/${retryDelaysMs.size})…") }
                    delay(retryDelaysMs[attempt]); attempt++
                } else {
                    update { it.copy(state = ConvState.ERROR_ENGINE, error = "On-device AI engine unavailable (${e.error.code}).", retryable = true, notice = null, streamingFields = emptySet()) }
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
        val id = repo.createDraft(settings().reporter)
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
        repromptedNoSpeech = false; retryAction = null; userTexts.clear(); narrationText = ""; spokeComplete = false; bubbleSeq = 0; editingRef = null
        _ui.value = ConversationUiState()
    }
}
