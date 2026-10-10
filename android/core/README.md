# `:core` — IncidentVoice domain library (pure Kotlin/JVM)

No Android dependencies. Everything that decides *what the form contains* lives here: config contracts,
validator, follow-up questions, prompt rendering, LLM output parsing, merging with provenance, normalisers
(time, location, people, language), emergency detection and SOP recommendations. Spec: `PRD.md` (rev 1.1 table §4.0
wins), `config/prompts/RENDERING.md` (normative prompt bytes). Behaviour mirrors the Python twin in `ml/common/`.

## Build / test

```bash
cd android
./gradlew :core:test                 # JVM only, no Android SDK needed
./gradlew -PwithAndroid=true ...     # also includes :engine (and later :app); needs ANDROID_HOME
```
Tests find the repo `config/` dir by walking up from the working directory. Golden prompt files
(`config/prompts/golden/*.txt` + `*.input.json`) are compared byte-for-byte.

## Packages (`com.megathon.incidentvoice.core.*`)

| Package | Main types | What |
|---|---|---|
| `config` | `ConfigLoader`, `CoreConfig`, `Taxonomy`, `FieldDef`, `CategoryDef`, `PairTemplate`, `SopLibrary`, `SopStep`, `SiteRegistry`, `AppConfig` | Loads `taxonomy.json`, `sop.json`, `site_registry.json`, `app_config.json`. Nothing else hard-codes field keys/enums. |
| `model` | `IncidentForm`, `FieldState`, `FieldStatus`, `FieldSource`, `Severity`, `ResolvedTime`, `Turn`, `Recommendation` | The draft. All fields (common + category details) in one map keyed by taxonomy key; `occurred_at` = ISO offset date-time, spoken phrase in `timeExpression`. |
| `validation` | `Validator`, `ValidationResult`, `FollowUpQuestion`, `SeverityRules` | PRD §14 (mandatory set, validity, ordering, pairing, askedCount ≥ 2 skip, emergency mode) and §10.6 severity floor. |
| `llm` | `PromptBuilder`, `ToolCallParser`, `PartialJson`, `Grounding`, `FormMerger`, `CompactJson`, `Tools` | Prompts (RENDERING.md), §13.6 parse pipeline (+ streaming), §13.8 grounding, merging with provenance. |
| `normalize` | `TimeResolver`, `LocationMatcher`, `PeopleNormaliser`, `LangDetector`, `Fuzzy`, `NumberWords` | §15 normalisers. |
| `safety` | `EmergencyDetector`, `EmergencyAlert`, `EmergencyLevel` | §10.7 keyword (AMBER) / severity CRITICAL (RED). |
| `recommend` | `SopRecommender` | P0 SOP templates (§12.2 step 1, top 6, immediate first) + emergency banner steps. |
| `dto` | `EngineInfo`, `AsrResult`, `GenStats`, `EngineError`, `EngineJson` | Appendix D.5 engine DTOs (snake_case JSON). |

## How the App uses it

```kotlin
// 1. Load once (assets/config/** copied from repo config/)
val loader = ConfigLoader { path -> context.assets.open("config/$path").bufferedReader().readText() }
val cfg = loader.loadAll()                       // taxonomy, sop, sites, app config
val tx = cfg.taxonomy
val timeResolver = TimeResolver()                // Asia/Kolkata
val merger = FormMerger(tx, timeResolver, LocationMatcher(cfg.sites))
val parser = ToolCallParser(tx)
val prompts = PromptBuilder(tx, loader::read)
val emergency = EmergencyDetector(tx)
val sops = SopRecommender(cfg.sop, cfg.app.recommendMaxItems)

// 2. Narration
var form = IncidentForm(reportedAt = Instant.now())
emergency.detect(transcript)?.let { showBanner(it, sops.emergencySteps(it.category)) }   // AMBER, before the LLM
val prompt = prompts.extract(transcript, cfg.app.promptVariant)                          // → engine.generate(EXTRACT)
// while streaming: parser.parsePartial(buffer, Tools.FILL)?.fields → shimmer UI
when (val r = parser.parse(llmOutput, Tools.FILL, transcripts = listOf(transcript))) {
    is ParseResult.Ok -> form = merger.applyFill(form, r.call, turnId, transcript).form
    is ParseResult.Failed -> when (r.reason) {
        ParseFailure.EMPTY_OUTPUT -> /* engine error IV-E6xx → retry policy, NOT a content failure */ Unit
        else -> /* one repair call: prompts.repair(Tools.FILL, llmOutput); still bad → */
            form = merger.manualFillFallback(form, transcript, turnId)
    }
}
emergency.evaluate(transcript, form.severity, form.incidentType)  // RED when CRITICAL

// 3. Follow-up loop (≤ cfg.app.maxFollowupRounds)
val v = Validator.validate(form, tx, Instant.now())
val q = Validator.nextQuestion(v, form, tx, emergencyMode = form.severity == Severity.CRITICAL)
if (q != null) {
    tts.speak(q.text(form.lang)); form = merger.markAsked(form, q.fields)
    val p = prompts.patch(form, q.fields, q.text(form.lang), answer)                      // → engine (PATCH)
    (parser.parse(out, Tools.UPDATE, allTranscripts) as? ParseResult.Ok)?.let { form = merger.applyPatch(form, it.call, turnId, answer).form }
}

// 4. Review / submit
form = merger.applyUserEdit(form, "location", JsonPrimitive("Pantry"))   // USER_EDITED, never overwritten by the LLM
form = merger.markUnknown(form, "injuries")                              // "Unknown" quick action
val recs = sops.recommend(form)                                          // P0: first 6 SOP templates
val canSubmit = Validator.validate(form, tx, Instant.now()).complete
```

Review-screen hints: `FieldState.status` `INFERRED` / `DEFAULTED` → amber "check" chip (ungrounded strings, rule-raised
severity, assumed time); `form.locationSuggestionId` → "Did you mean …?" chip (`merger.acceptLocationSuggestion`);
`form.flags` lists rule actions (`SEVERITY_RAISED_BY_RULE`, `INJURIES_RAISED_BY_RULE`). `cfg.sop.disclaimer` must be shown.

## Spec resolutions (shared with `ml/common`)

- **Time (§15.1):** T3 checked before T2; T1 only when the whole phrase is a "now" phrase; "a few minutes ago" /
  "thodi der pehle" = −15 min; day-only phrases → 12:00 that day (approx, DAY); "today" alone → now; weekday = most
  recent past (same weekday = a week ago); dates without year are always past; "last night" = yesterday 22:00, with an
  hour < 06:00 → today. T10: no phrase → report time, approximate, `DEFAULTED`.
- **SOP:** `when` applies to immediate steps too; missing field passes, `"*"` = has a value; UNKNOWN compares as
  `"UNKNOWN"`; done removal = ≥ 60 % of a done-keyword's tokens in the union of `actions_taken` tokens; order = primary,
  secondaries, GENERAL, stable-sorted immediate first. `emergencySteps` ignores `when` (banner fires before extraction).
- **Validator:** invalid values count as missing; emergency mode = flag or severity CRITICAL (only common mandatory
  fields asked); Hinglish group-rule questions are not lower-cased.
- **Emergency:** keyword hits are AMBER (PRD §10.7); RED only via severity CRITICAL. Excluded phrases (`fire drill`,
  `drill`, …) are masked, not used to veto the whole transcript.
- **Location:** extra guards — numbers must agree, generic-word-only overlap gets no subset bonus.
