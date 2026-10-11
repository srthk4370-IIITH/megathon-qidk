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

/** "Show your evidence": spans are stored with the report (DB v5) and old reports lose nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EvidenceStorageTest {
    private lateinit var db: IncidentDb
    private lateinit var repo: RoomIncidentRepository
    private lateinit var cfg: AppConfigBundle
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

    private fun IncidentDetail.words(key: String): List<String> {
        val said = turns.filter { it.role == "USER" }.map { it.text }
        return form.evidence[key].orEmpty().map { said[it.turn].substring(it.start, it.end) }
    }

    @Test fun demoReports_keepTheirSpansThroughTheDatabase() = runBlocking {
        DemoSeeder(cfg, repo).seed(Reporter("Asha", "E100"), now)
        val leak = repo.observeList(DashboardFilter(types = setOf("WATER"))).first().single()
        val d = repo.load(leak.id)!!
        assertEquals(listOf("near Conference Room 2"), d.words("location"))
        assertEquals(listOf("around 10 AM"), d.words("occurred_at"))
        assertEquals(listOf("Maintenance has already isolated the area"), d.words("actions_taken"))
        assertNotNull(db.dao().get(leak.id)!!.evidenceJson)
    }

    @Test fun reportSavedBeforeV5_getsItsSpansWhenOpened() = runBlocking {
        DemoSeeder(cfg, repo).seed(Reporter("Asha", "E100"), now)
        val leak = repo.observeList(DashboardFilter(types = setOf("WATER"))).first().single()
        // what the v4 → v5 migration leaves on an existing row
        db.openHelper.writableDatabase.execSQL("UPDATE incidents SET evidence_json = NULL WHERE id = ?", arrayOf(leak.id))
        val d = repo.load(leak.id)!!
        assertEquals(listOf("near Conference Room 2"), d.words("location"))
        assertEquals(listOf("water", "leak"), d.words("incident_type"))
    }

    @Test fun migration4to5_addsTheColumnAndKeepsTheRows() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx).name(null).callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE incidents (rowid_pk INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, title TEXT)")
                    db.execSQL("INSERT INTO incidents (id, title) VALUES ('a', 'Water leak'), ('b', 'Fire')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            }).build(),
        )
        val sdb = helper.writableDatabase
        IncidentDb.MIGRATION_4_5.migrate(sdb)
        sdb.query("SELECT id, title, evidence_json FROM incidents ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("a", c.getString(0)); assertEquals("Water leak", c.getString(1)); assertTrue(c.isNull(2))
        }
        helper.close()
    }
}
