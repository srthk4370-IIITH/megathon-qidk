package com.megathon.incidentvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.megathon.incidentvoice.ask.*
import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.data.*
import com.megathon.incidentvoice.data.db.IncidentDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/** The Ask tab end to end: real MiniLM model (ONNX Runtime), real SQLite (Room + FTS4 + vector table), demo incidents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AskServiceTest {
    private lateinit var db: IncidentDb
    private lateinit var repo: RoomIncidentRepository
    private lateinit var ask: AskService
    private lateinit var cfg: AppConfigBundle
    private val now = 1_791_000_000_000L

    companion object {
        private val dir = File(System.getProperty("iv.configDir")!!)
        private val embedder: OnnxMiniLmEmbedder by lazy {
            val m = File(dir.parentFile, "android/app/src/main/assets/models/minilm")
            OnnxMiniLmEmbedder(File(m, "model_quantized.onnx").readBytes(), File(m, "vocab.txt").readLines())
        }
    }

    @Before fun setUp() = runBlocking {
        cfg = AppConfigBundle(
            Taxonomy.parse(File(dir, "taxonomy.json").readText()), SiteRegistry.parse(File(dir, "site_registry.json").readText()),
            SopLibrary.parse(File(dir, "sop.json").readText()), PromptTemplates.load { File(dir, it).readText() }, AppConfig.parse(File(dir, "app_config.json").readText()),
        )
        db = IncidentDb.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = RoomIncidentRepository(db, cfg.taxonomy, cfg.registry, nowMs = { now })
        DemoSeeder(cfg, repo).seed(Reporter("Asha", "E1"), now)
        ask = AskService(repo, RuleQueryParser(cfg.taxonomy, cfg.registry), AnswerComposer(cfg.taxonomy), embedder) { Instant.ofEpochMilli(now) }
    }
    @After fun tearDown() = db.close()

    @Test fun embedder_meaningNotWords() {
        val fire = embedder.embed("Smoke and flames from the electrical panel")
        assertEquals(384, fire.size); assertEquals(1f, Vectors.dot(fire, fire), 1e-3f)
        val blaze = embedder.embed("there was a blaze in the building"); val leak = embedder.embed("water dripping from a burst pipe")
        assertTrue("blaze~fire ${Vectors.dot(fire, blaze)} vs blaze~leak ${Vectors.dot(leak, blaze)}", Vectors.dot(fire, blaze) > Vectors.dot(leak, blaze) + 0.1f)
    }

    @Test fun zz_printScores() = runBlocking {
        ask.syncIndex()
        val vecs = repo.vectors(embedder.modelId); val items = repo.listFiltered(DashboardFilter()).associateBy { it.id }
        for (q in listOf("blaze switchboard", "somebody twisted their foot", "twisted foot", "toxic fumes", "stolen laptop", "insects nesting", "unicorn stampede helipad", "forklift accidents", "worker fainting heat", "oil in the drain", "machine stopped working")) {
            val qv = embedder.embed(q)
            println("SCORES [$q] " + vecs.map { items[it.key]!!.title.take(22) to Vectors.dot(qv, it.value) }.sortedByDescending { it.second }.take(4).joinToString(" | ") { "%s %.2f".format(it.first, it.second) })
        }
    }

    @Test fun index_isBuiltOnce_andStoredInSqlite() = runBlocking {
        assertEquals(10, ask.syncIndex()); assertEquals(0, ask.syncIndex())                       // nothing stale the second time
        val v = repo.vectors(embedder.modelId); assertEquals(10, v.size); assertTrue(v.values.all { it.size == 384 })
    }

    @Test fun semantic_findsByMeaning_withoutSharedWords() = runBlocking {
        // no demo incident contains the words "blaze", "burning" or "switchboard"
        val a = ask.ask("was there a blaze at a switchboard")
        assertTrue(a.text, a.hits.isNotEmpty()); assertEquals("Fire at DB-3 panel", a.hits.first().title); assertTrue(a.mode.contains("vector"))
        assertEquals("Slip near pantry", ask.ask("somebody twisted their foot").hits.first().title)
        assertEquals("Suspected ammonia leak at chemical store", ask.ask("toxic fumes").hits.first().title)
        assertTrue(ask.ask("stolen laptop").hits.firstOrNull()?.title.orEmpty().contains("Unauthorised entry") || ask.ask("stolen laptop").hits.isEmpty())
    }

    @Test fun structured_countAndFilters() = runBlocking {
        val c = ask.ask("How many critical incidents are there?")
        assertEquals(AskIntent.COUNT, c.query.intent); assertEquals(2, c.total); assertTrue(c.text, c.text.startsWith("There are 2 critical incidents."))
        val f = ask.ask("show fires"); assertEquals(1, f.total); assertEquals("FIRE", f.hits.single().type)
        val recent = ask.ask("incidents in the last 3 hours"); assertTrue(recent.total in 1..4)
        assertEquals(1, ask.ask("any explosions in the last 2 hours").total)        // the DB-3 fire, 55 minutes old
        assertEquals(0, ask.ask("thefts in the last 2 hours").total)
    }

    @Test fun location_question_readsOutTheReport() = runBlocking {
        val a = ask.ask("What happened in Bay 4?")
        assertEquals(1, a.total); assertTrue(a.text, a.text.startsWith("Forklift hit a rack in Bay 4"))
        assertTrue(a.text, a.text.contains("Actions taken: Area closed off"))
    }

    @Test fun secondaryType_isMatchedByTypeFilter() = runBlocking {
        // "Fire at DB-3 panel" is FIRE with ELECTRICAL secondary → an electrical question must find it too
        assertTrue(ask.ask("electrical incidents").hits.any { it.title == "Fire at DB-3 panel" })
    }

    @Test fun similarIncidents_andNoMatch() = runBlocking {
        val fireId = repo.listFiltered(DashboardFilter(types = setOf("FIRE"))).single().id
        val sim = ask.similar(fireId)
        assertTrue(sim.toString(), sim.isNotEmpty() && sim.none { it.id == fireId }); assertEquals("Sparking socket at reception", sim.first().title)
        val none = ask.ask("unicorn stampede on the helipad"); assertEquals(0, none.total); assertTrue(none.text.startsWith("I couldn't find"))
    }

    @Test fun editedReport_isReEmbedded_andDeletedReportLeavesTheIndex() = runBlocking {
        ask.syncIndex()
        val id = repo.listFiltered(DashboardFilter(types = setOf("WATER"))).first().id
        val d = repo.load(id)!!
        repo.saveForm(id, d.form.with(FieldState("description", kotlinx.serialization.json.JsonPrimitive("A swarm of bees built a hive above the entrance"), FieldStatus.USER_EDITED)), Actor.System)
        assertEquals(1, ask.syncIndex())
        assertEquals(id, ask.ask("bees hive at the entrance").hits.first().id)
        repo.delete(id, Actor.System); assertEquals(9, repo.vectors(embedder.modelId).size)
    }
}
