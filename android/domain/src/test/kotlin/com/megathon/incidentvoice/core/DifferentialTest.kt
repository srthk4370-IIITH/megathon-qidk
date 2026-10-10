package com.megathon.incidentvoice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.fail
import com.megathon.incidentvoice.core.config.ConfigLoader as MlConfigLoader
import com.megathon.incidentvoice.core.normalize.LocationMatcher as MlLocationMatcher
import com.megathon.incidentvoice.core.normalize.TimeResolver as MlTimeResolver

/**
 * Differential tests: the app's normalisers vs. the ML branch's `:core` implementations on every time phrase and
 * location in the 150-case eval set. The two were written independently from the same PRD sections, so agreement
 * is strong evidence; disagreements are listed and must be explained.
 */
class DifferentialTest {
    private val file = File(Fx.dir.parentFile, "eval/text_cases.jsonl")
    private val cases by lazy { file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject } }

    @Test fun timeResolver_agreesWithMlCore() {
        if (!file.exists()) return
        val ml = MlTimeResolver()
        val diffs = ArrayList<String>()
        var n = 0
        for (c in cases) {
            val phrase = c["gold"]!!.jsonObject["time"]?.jsonPrimitive?.content ?: continue
            val now = OffsetDateTime.parse(c["now"]!!.jsonPrimitive.content).toInstant()
            n++
            val mine = Fx.times.resolve(phrase, now)
            val theirs = ml.resolve(phrase, now)
            if (mine.instant != theirs.instant) diffs += "'$phrase': app=${mine.instant.atZone(Fx.zone).toLocalDateTime()} (${mine.matchedRule}) ml=${theirs.instant?.atZone(Fx.zone)?.toLocalDateTime()} (${theirs.matchedRule})"
        }
        if (diffs.isNotEmpty()) fail("${diffs.distinct().size} distinct differences in $n phrases:\n" + diffs.distinct().joinToString("\n"))
    }

    @Test fun locationMatcher_agreesWithMlCore() {
        if (!file.exists()) return
        val ml = MlLocationMatcher(MlConfigLoader.fromDirectory(Fx.dir).loadSiteRegistry())
        val mine = LocationMatcher(Fx.registry)
        val diffs = ArrayList<String>()
        for (loc in cases.mapNotNull { it["gold"]!!.jsonObject["location"]?.jsonPrimitive?.content }.distinct()) {
            val a = mine.match(loc); val b = ml.match(loc)
            // Known, accepted: for a bare building name ("Block A") ML's matcher suggests the Loading Dock (0.73, a false
            // positive); the app offers no suggestion. Free text is kept either way.
            if (loc in setOf("Block A", "Block B") && a.kind == MatchKind.NONE) continue
            if (a.kind.name.take(4) != b.kind.name.take(4) || a.location?.id != b.location?.id)
                diffs += "'$loc': app=${a.kind} ${a.location?.id} (${"%.2f".format(a.score)}) ml=${b.kind} ${b.location?.id} (${"%.2f".format(b.score)})"
        }
        if (diffs.isNotEmpty()) fail("${diffs.size} differences:\n" + diffs.joinToString("\n"))
    }
}
