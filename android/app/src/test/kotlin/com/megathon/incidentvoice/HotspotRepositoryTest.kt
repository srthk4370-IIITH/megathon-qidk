package com.megathon.incidentvoice

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import java.time.Instant

/** Repeat incidents and hotspots on the real Room database, with the "Seed demo data" set. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotRepositoryTest {
    private lateinit var db: IncidentDb
    private lateinit var repo: RoomIncidentRepository
    private lateinit var cfg: AppConfigBundle
    private val reporter = Reporter("Asha", "E100")
    private val now = 1_791_000_000_000L

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

    private fun seed() = runBlocking { DemoSeeder(cfg, repo).seedHotspots(reporter, now) }

    /** A report made by a person just now (not demo data). */
    private suspend fun report(type: String, place: String, title: String = "$type at $place"): String {
        val call = (ToolCallParser(cfg.taxonomy).parse("""{"name":"fill_incident_form","parameters":{"incident_type":"$type","title":"$title","description":"$title.","location":"$place","severity":"LOW","injuries":"NO"}}""", Tools.FILL) as ParseOutcome.Ok).call
        val merger = FormMerger(cfg.taxonomy, TimeResolver(), LocationMatcher(cfg.registry))
        val form = merger.applyTimeDefault(merger.merge(IncidentForm(), call, FieldSource.LLM_EXTRACT, 0, "$title in $place", Instant.ofEpochMilli(now)).form, Instant.ofEpochMilli(now))
        val id = repo.createDraft(reporter, now)
        repo.saveForm(id, form, Actor.Llm)
        repo.submit(id, Actor.System, now)
        return id
    }

    @Test fun seed_isTwelveCompleteReports_allMarkedDemo() = runBlocking {
        assertEquals(12, seed())
        val list = repo.observeList(DashboardFilter()).first()
        assertEquals(12, list.size)
        assertTrue(list.all { it.demo })
        assertTrue(list.all { it.createdAt in (now - 30 * HotspotRules.DAY_MS)..now })
        for (i in list) {
            val d = repo.load(i.id)!!
            val v = Validator.validate(d.form, cfg.taxonomy, Instant.ofEpochMilli(now))
            assertTrue("${i.title}: missing ${v.missing} invalid ${v.invalid}", v.complete)
            assertTrue(d.meta.demo)
        }
        // nothing in the seed is already a repeat: the card is left for the live report
        assertTrue(list.none { it.tags.repeat != null })
    }

    @Test fun seed_dashboardHotspots_workshopFirst() = runBlocking {
        seed()
        val top = repo.observeHotspots().first()
        assertEquals(listOf("Workshop", "Conference Room 2", "Loading Dock"), top.map { it.location })
        val shop = top[0]
        assertEquals(5, shop.total); assertEquals("EQUIPMENT", shop.topType); assertEquals(3, shop.last7); assertEquals(1, shop.prev7); assertEquals(Trend.MORE, shop.trend)
        assertEquals("A-1-CR2", top[1].key); assertEquals(2, top[1].last7); assertEquals(Trend.MORE, top[1].trend)
        assertEquals(Trend.FEWER, top[2].trend)
        // Hotspot chip on every report at those places, and only there
        val list = repo.observeList(DashboardFilter()).first()
        assertEquals(9, list.count { it.tags.hotspot })
        assertTrue(list.filter { it.tags.hotspot }.all { it.tags.location in setOf("Workshop", "Conference Room 2", "Loading Dock") })
    }

    @Test fun thirdLeakAtConferenceRoom2_isARepeat_andTheCardTextIsRight() = runBlocking {
        seed()
        val id = report("WATER", "near conf room two", "Water leak")
        val tags = repo.reportTags(id)!!
        assertEquals(RepeatTag(3, "WATER", 7), tags.repeat)
        assertTrue(tags.hotspot)
        assertEquals("Recurring hazard: 3 water leak reports at Conference Room 2 in 7 days. Recommend inspection of Conference Room 2.",
            Hotspots.recurring(tags.repeat!!, cfg.taxonomy.category("WATER")!!.label, tags.location!!))
        // stored with the report on save, shown in the list
        assertEquals(tags, ReportTags.parse(db.dao().get(id)!!.tagsJson))
        val item = repo.observeList(DashboardFilter()).first().first { it.id == id }
        assertEquals("3rd water leak here in 7 days", Hotspots.chip(item.tags.repeat!!, cfg.taxonomy.category("WATER")!!.label))
        assertFalse(item.demo)
        // a different type at the same place is not a repeat
        assertNull(repo.reportTags(report("ELECTRICAL", "Conference Room 2"))!!.repeat)
    }

    @Test fun tappingAHotspot_filtersTheListToThatPlace() = runBlocking {
        seed()
        val shop = repo.observeHotspots().first().first()
        val list = repo.observeList(DashboardFilter(locationKey = shop.key)).first()
        assertEquals(5, list.size)
        assertTrue(list.all { it.location!!.contains("workshop", ignoreCase = true) })
        assertEquals(2, repo.observeList(DashboardFilter(locationKey = "A-1-CR2")).first().size)   // "Conference Room 2" and "conf room 2"
        assertEquals(1, repo.observeList(DashboardFilter(locationKey = shop.key, types = setOf("MEDICAL"))).first().size)
    }

    @Test fun clearDemoData_deletesOnlyDemoRows() = runBlocking {
        seed()
        val mine = report("WATER", "Conference Room 2", "Water leak")
        assertEquals(13, repo.countAll())
        assertEquals(12, repo.clearDemo(Actor.System))
        val left = repo.observeList(DashboardFilter()).first()
        assertEquals(listOf(mine), left.map { it.id })
        // with the demo leaks gone it is no longer the 3rd, and nothing is a hotspot
        assertNull(left.single().tags.repeat); assertFalse(left.single().tags.hotspot)
        assertTrue(repo.observeHotspots().first().isEmpty())
        assertEquals(0, repo.clearDemo(Actor.System))
    }

    @Test fun draftsAndLinkedWitnessAccounts_areNotCounted() = runBlocking {
        seed()
        repo.createDraft(reporter, now)                                    // an unfinished report
        val third = report("WATER", "Conference Room 2", "Water leak")
        val witness = report("WATER", "Conference Room 2", "Water leak seen by a second person")
        assertEquals(4, repo.reportTags(witness)!!.repeat!!.n)
        repo.linkWitness(witness, third, Actor.System)                     // the same event told by a second person
        assertEquals(3, repo.reportTags(third)!!.repeat!!.n)
        assertEquals(3, repo.observeHotspots().first().first { it.key == "A-1-CR2" }.total)
    }

    @Test fun migration5to6_addsDemoAndTagsColumns_andKeepsTheRows() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(null).callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE incidents (rowid_pk INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, title TEXT, evidence_json TEXT)")
                    db.execSQL("INSERT INTO incidents (id, title) VALUES ('a', 'Water leak'), ('b', 'Fire')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build(),
        )
        val sdb = helper.writableDatabase
        IncidentDb.MIGRATION_5_6.migrate(sdb)
        sdb.query("SELECT id, title, is_demo, tags_json FROM incidents ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("a", c.getString(0)); assertEquals("Water leak", c.getString(1)); assertEquals(0, c.getInt(2)); assertTrue(c.isNull(3))
        }
        helper.close()
    }
}
