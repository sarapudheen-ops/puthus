package com.bod3.app.android

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bod3.app.source.SourceBrowser
import com.bod3.app.source.SourceInstaller
import com.bod3.app.source.SourceManifest
import com.bod3.app.source.SourcePage
import com.bod3.app.source.SourceRequest
import com.bod3.app.source.SourceSql
import com.bod3.app.source.SourceSqlRow
import java.io.File

/** APK asset -> immutable private DB -> read-only, paged source queries.
 * Deliberately has no dependency on Bluetooth, sessions, coding or actuator controllers. */
class ToyotaSourceLibrary(context: Context) {
    private val app = context.applicationContext
    private val installer = SourceInstaller(File(app.noBackupFilesDir, "toyota-source"))
    private val manifest: SourceManifest by lazy {
        app.assets.open("toyota-source/manifest.json").bufferedReader(Charsets.UTF_8).use { SourceManifest.parse(it.readText()) }
    }
    private var validated: String? = null

    /** IO thread only. SQLite is closed after every bounded page; no large cursor survives. */
    @Synchronized
    fun load(request: SourceRequest, checkCancelled: () -> Unit = {}): SourcePage {
        val pack = manifest
        val path = installer.ensure(pack, { app.assets.open("toyota-source/toyota-source.zip") }, checkCancelled)
        checkCancelled()
        return SQLiteDatabase.openDatabase(path.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            check(db.isReadOnly) { "Source database must be opened read-only" }
            val browser = SourceBrowser(AndroidSourceSql(db), pack)
            if (validated != path.absolutePath) {
                browser.validateMetadata()
                validated = path.absolutePath
            }
            checkCancelled()
            browser.load(request).also { checkCancelled() }
        }
    }
}

internal class AndroidSourceSql(private val db: SQLiteDatabase) : SourceSql {
    override fun query(sql: String, args: List<String>): List<SourceSqlRow> {
        require(sql.trimStart().startsWith("SELECT ", ignoreCase = true) || sql == "PRAGMA user_version") { "Only source SELECT queries are allowed" }
        return db.rawQuery(sql, args.toTypedArray()).use { cursor ->
            val names = cursor.columnNames
            buildList {
                while (cursor.moveToNext()) {
                    check(size < 64) { "Unbounded source query refused" }
                    add(SourceSqlRow(names.mapIndexed { i, name ->
                        name to when (cursor.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(i)
                            else -> cursor.getString(i)
                        }
                    }.toMap()))
                }
            }
        }
    }
}
