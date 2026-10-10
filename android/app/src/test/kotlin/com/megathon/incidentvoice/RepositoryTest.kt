package com.megathon.incidentvoice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.megathon.incidentvoice.config.AppConfigBundle
import com.megathon.incidentvoice.core.*
import com.megathon.incidentvoice.data.*
import com.megathon.incidentvoice.data.db.IncidentDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {
    private lateinit var db: IncidentDb
    private lateinit var repo: RoomIncidentRepository
    private lateinit var cfg: AppConfigBundle
    private val reporter = Reporter("Asha", "E100")
    private val now = 1_791_000_000_000L // fixed instant, Oct 2026

    @Before fun setUp() {
        val dir = File(System.getProperty("iv.configDir")!!)
        cfg = AppConfigBundle(
            Taxonomy.parse(File(dir, "taxonomy.json").readText()), SiteRegistry.parse(File(dir, "site_registry.json").readText()),
            SopLibrary.parse(File(dir, "sop.json").readText()), PromptTemplates.load { File(dir, it).readText() },
            AppConfig.parse(File(dir, "app_config.json").readText()),
        )
        db = IncidentDb.inMemory(ApplicationProvider.getApplicationContext<Context>())
        repo = RoomIncidentRepository(db, cfg.taxonomy, cfg.registry, nowMs = { now })
    }
    @After fun tearDown() = db.close()

    private fun seed() = runBlocking { DemoSeeder(cfg, repo).seed(reporter, now) }

    @Test fun seed_createsSubmittedIncidentsAndCounts() = runBlocking {
        assertEquals(10, seed())
        val c = repo.observeCounts().first()
        assertEquals(10, c.total); assertTrue("critical ${c.critical}", c.critical >= 1)
        val list = repo.observeList(DashboardFilter()).first()
        assertEquals(10, list.size)
        assertTrue(list.zipWithNext().all { (a, b) -> a.createdAt >= b.createdAt }) // recency
        assertTrue(list.all { it.status == "SUBMITTED" && it.refNo != null })
    }

    @Test fun everyDemoIncidentIsCompleteAccordingToTheValidator() = runBlocking {
        seed()
        for (i in repo.observeList(DashboardFilter()).first()) {
            val d = repo.load(i.id)!!
            val v = Validator.validate(d.form, cfg.taxonomy, java.time.Instant.ofEpochMilli(now))
            assertTrue("${i.title}: missing ${v.missing} invalid ${v.invalid}", v.complete)
        }
    }

    @Test fun refNumbers_areUniqueAndSequentialPerDay() = runBlocking {
        val a = repo.createDraft(reporter); val b = repo.createDraft(reporter)
        val r1 = repo.submit(a); val r2 = repo.submit(b)
        assertTrue(r1, Regex("INC-\\d{8}-0001").matches(r1)); assertTrue(r2, Regex("INC-\\d{8}-0002").matches(r2)) // PRD: INC-YYYYMMDD-NNNN
    }

    @Test fun ftsSearch_prefixAndSnippetAndHinglishCues() = runBlocking {
        seed()
        val water = repo.search("water", DashboardFilter()).first()
        assertTrue(water.any { it.title.contains("Water leak") }); assertTrue(water.first().snippet?.contains("[") == true)
        assertTrue(repo.search("conf", DashboardFilter()).first().any { it.title.contains("Conference") }) // prefix
        assertTrue(repo.search("aag", DashboardFilter()).first().isNotEmpty()) // FIRE cue word indexed even if not in text
        assertTrue(repo.search("zzzz", DashboardFilter()).first().isEmpty())
        assertTrue(repo.search("a", DashboardFilter()).first().size == 10) // <2 chars → falls back to full list
    }

    @Test fun filters_typeSeverityDate() = runBlocking {
        seed()
        assertTrue(repo.observeList(DashboardFilter(types = setOf("FIRE"))).first().all { it.type == "FIRE" })
        val crit = repo.observeList(DashboardFilter(severities = setOf("CRITICAL"))).first()
        assertTrue(crit.isNotEmpty() && crit.all { it.severity == "CRITICAL" })
        val recent = repo.observeList(DashboardFilter(fromMs = now - 60 * 60_000L)).first()
        assertTrue(recent.size in 1..9)
        assertTrue(repo.search("leak", DashboardFilter(severities = setOf("LOW"))).first().all { it.severity == "LOW" })
    }

    @Test fun detail_roundTripsFormAndRecommendations() = runBlocking {
        seed()
        val id = repo.search("water leak", DashboardFilter()).first().first().id
        val d = repo.load(id)!!
        assertEquals("WATER", d.form.incidentType); assertEquals("A-1-CR2", d.form.locationId)
        assertEquals("STOPPED", d.form.str("leak_status")); assertEquals(IncidentStatus.SUBMITTED, d.meta.status)
        assertTrue(d.recommendations.isNotEmpty() && d.turns.size == 1)
        assertTrue(Validator.validate(d.form, cfg.taxonomy, java.time.Instant.ofEpochMilli(now)).complete)
        repo.setRecommendationDone(d.recommendations.first().id, true)
        assertTrue(repo.load(id)!!.recommendations.first().done)
    }

    @Test fun draftsAreHiddenFromDashboardButResumable() = runBlocking {
        val id = repo.createDraft(reporter)
        repo.saveForm(id, IncidentForm(lang = "en").with(FieldState("description", kotlinx.serialization.json.JsonPrimitive("half done"), FieldStatus.FILLED)), Actor.User("E100"))
        assertTrue(repo.observeList(DashboardFilter()).first().isEmpty())
        assertEquals(id, repo.latestDraft(24 * 3600_000L))
        assertEquals("half done", repo.load(id)!!.form.str("description"))
    }

    @Test fun purgeOldDrafts_andDeleteRemovesSearchRow() = runBlocking {
        val old = RoomIncidentRepository(db, cfg.taxonomy, cfg.registry, nowMs = { now - 8L * 24 * 3600_000 })
        val id = old.createDraft(reporter)
        assertEquals(1, repo.purgeOldDrafts(7L * 24 * 3600_000)); assertNull(repo.load(id))
        seed()
        val sid = repo.observeList(DashboardFilter()).first().first().id
        repo.delete(sid, Actor.System)
        assertNull(repo.load(sid)); assertEquals(9, repo.observeList(DashboardFilter()).first().size)
    }

    @Test fun saveForm_writesAuditTrailAndKeepsMeta() = runBlocking {
        val id = repo.createDraft(reporter)
        val f = IncidentForm().with(FieldState("location", kotlinx.serialization.json.JsonPrimitive("pantry"), FieldStatus.FILLED))
        repo.saveForm(id, f, Actor.Llm, followupRounds = 2, emergency = true)
        val audit = db.dao().audit(id)
        assertTrue(audit.any { it.action == "CREATE" }); assertTrue(audit.any { it.action == "FIELD_SET" && it.field == "location" && it.actor == "LLM" })
        val m = repo.load(id)!!.meta
        assertEquals(2, m.followupRounds); assertTrue(m.emergencyFlag); assertEquals("Asha", m.reporter.name)
    }
}
