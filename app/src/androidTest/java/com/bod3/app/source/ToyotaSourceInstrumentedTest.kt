package com.bod3.app.source

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bod3.app.android.ToyotaSourceLibrary
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real APK data and Android's SQLite/CursorWindow/asset streams. No adapter or network.
 * Run on minSdk 24 as well as a current device; JVM SQLite alone is not platform proof. */
@RunWith(AndroidJUnit4::class)
class ToyotaSourceInstrumentedTest {
    @Test fun completeBundledCorpusInstallsIntoNonBackedUpPrivateStorage() {
        val page = library.load(SourceRequest())
        assertTrue(page.rows.isNotEmpty())
        assertTrue(manifest.databaseBytes > 114_797_280L) // Complete decoded bytes, not a tiny sample pack.
        assertEquals(manifest.databaseBytes, path.length())
        database { db ->
            for ((table, expected) in manifest.counts.filterKeys { it in setOf("source_file", "data_table", "text_record", "menu", "system", "vehicle", "frame", "function_frame") }) {
                db.rawQuery("SELECT count(*) FROM $table", null).use { cursor -> cursor.moveToFirst(); assertEquals(table, expected, cursor.getLong(0)) }
            }
        }
    }

    @Test fun manualModelSearchIsSeparateFromAutomaticSourceBranchesAndAppSelection() {
        val page = library.load(SourceRequest(SourceLocation.Menu(), "Innova"))
        assertTrue(page.rows.isNotEmpty())
        assertTrue(page.rows.all { (it.destination as SourceLocation.Menu).manualOnly })
        assertTrue(page.rows.all { it.detail.contains("Manual Select") })
        val leaf = library.load(SourceRequest(page.rows.first().destination!!))
        assertTrue(leaf.fields.any { it.name == "Source path" && it.value.contains("Innova") })
        assertTrue(leaf.links.none { it.location is SourceLocation.Systems })
    }

    @Test fun realFrameDictionaryReferencesResolveWithoutSendingAnything() {
        for (region in SourceRegion.entries) {
            val frame = library.load(SourceRequest(SourceLocation.Frame(region, 10001)))
            assertEquals(listOf("21 00", "ff ff", "61 00"), frame.rows.map { it.detail })
            assertEquals(SourceLocation.Command(region, 138), frame.rows.first().destination)
            val bad = library.load(SourceRequest(SourceLocation.Command(region, 3258)))
            assertTrue(bad.rows.single().warning)
            assertTrue(bad.rows.single().title.contains("INVALID"))
            assertTrue(bad.rows.single().detail.contains("outside"))
        }
    }

    @Test fun actualTextSearchWorksAfterEmbeddedNulsOnAndroidSQLite() {
        val matches = library.load(SourceRequest(SourceLocation.Texts("TCODE_EN.GAG"), "engine"))
        assertTrue(matches.rows.isNotEmpty())
        assertTrue(matches.rows.any { it.detail.contains("\\0") && it.detail.contains("engine", ignoreCase = true) })
        val record = library.load(SourceRequest(matches.rows.first().destination!!))
        assertTrue(record.rows.first().detail.contains("\\0"))
        assertTrue(record.rows.any { it.title == "Exact byte representation" })
    }

    @Test fun actualNonUtf8RecordAndOpaqueFilesAreNotDiscarded() {
        val id = database { db -> db.rawQuery("SELECT id FROM text_record WHERE library='VEHICLEADAS.GAG' AND value IS NULL LIMIT 1", null).use { it.moveToFirst(); it.getString(0) } }
        val raw = library.load(SourceRequest(SourceLocation.TextRecord("VEHICLEADAS.GAG", id)))
        assertTrue(raw.explanation.contains("Unknown encoding"))
        assertTrue(raw.rows.isNotEmpty())
        val opaque = library.load(SourceRequest(SourceLocation.FileDetail("SYSTEMENTER.BIN")))
        assertEquals("opaque", opaque.fields.single { it.name == "Parser status" }.value)
        val bytes = library.load(SourceRequest(SourceLocation.FileBytes("SYSTEMENTER.BIN")))
        assertEquals(32, bytes.rows.size)
        assertTrue(bytes.hasNext)
    }

    @Test fun realVehicleSystemsLeadToActualEcuFilesWithBoundedRows() {
        val key = database { db -> db.rawQuery("SELECT v.id FROM vehicle v JOIN system s ON s.region=v.region AND s.block=v.block WHERE v.region='EU' AND s.ecu_file LIKE 'Ecu_Data_%' LIMIT 1", null).use { it.moveToFirst(); it.getLong(0) } }
        val systems = library.load(SourceRequest(SourceLocation.Systems(SourceRegion.EU, key)))
        val ecu = systems.rows.first { it.destination is SourceLocation.FileDetail }
        assertTrue(ecu.links.any { it.location is SourceLocation.Function })
        val file = library.load(SourceRequest(ecu.destination!!))
        assertTrue(file.rows.isNotEmpty())
        val populated = file.rows.firstOrNull { !it.detail.contains("· 0 rows") }
        if (populated != null) {
            val table = library.load(SourceRequest(populated.destination!!))
            assertTrue(table.rows.size <= SourceRequest.PAGE_SIZE)
        }
    }

    @Test fun sqliteConnectionCannotMutateSourceData() {
        database { db ->
            assertTrue(db.isReadOnly)
            try { db.execSQL("UPDATE metadata SET value='false' WHERE key='manualSelectionOnly'"); fail("Source DB must be read-only") }
            catch (_: SQLiteReadOnlyDatabaseException) { }
        }
    }

    private fun <T> database(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(path.absolutePath, null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use(block)

    companion object {
        private lateinit var app: Context
        private lateinit var library: ToyotaSourceLibrary
        private lateinit var manifest: SourceManifest
        private lateinit var path: File
        @JvmStatic @BeforeClass fun install() {
            app = InstrumentationRegistry.getInstrumentation().targetContext
            manifest = app.assets.open("toyota-source/manifest.json").bufferedReader().use { SourceManifest.parse(it.readText()) }
            library = ToyotaSourceLibrary(app)
            library.load(SourceRequest())
            path = File(app.noBackupFilesDir, "toyota-source/${manifest.databaseSha256}.db")
        }
    }
}
