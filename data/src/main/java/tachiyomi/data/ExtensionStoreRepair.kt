package tachiyomi.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitQuery
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SY-specific file
 *
 * Upstream's 40.sqm (extension_store, its extension_list_url column and the memo columns on mangas/chapters)
 * never made it into this fork's .sqm chain, because that slot was taken by a renumbered fork migration. A
 * database created before extension_store existed therefore still has extension_repos and no memo columns,
 * which breaks library reads and the extensions screen. Fresh installs are unaffected, as their schema is
 * created from the .sq files.
 *
 * A .sqm file cannot repair this: migrations are keyed to the database version, and every affected database
 * has already passed that version, so it would never run. Running always instead lets us inspect the schema
 * and apply only what is missing, making this a no-op on an intact database.
 *
 * The raw SQL lives here because access to the driver is confined to this module; the migration that runs it
 * lives with the other migrations.
 */
class ExtensionStoreRepair(
    private val driver: SqlDriver,
) {

    suspend fun repair(): Boolean = withContext(Dispatchers.IO) {
        if (driver.tableExists("extension_repos")) {
            driver.execSql(
                """
                CREATE TABLE IF NOT EXISTS extension_store(
                    index_url TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    badge_label TEXT NOT NULL,
                    signing_key TEXT NOT NULL,
                    contact_website TEXT NOT NULL,
                    contact_discord TEXT,
                    is_legacy INTEGER NOT NULL,
                    extension_list_url TEXT
                )
                """.trimIndent(),
            )
            driver.execSql(
                """
                INSERT OR IGNORE INTO extension_store(
                    index_url, name, badge_label, signing_key, contact_website, contact_discord, is_legacy
                )
                SELECT base_url || '/repo.json', name, coalesce(short_name, name), signing_key_fingerprint, website, NULL, 1
                FROM extension_repos
                """.trimIndent(),
            )
            driver.execSql("DROP TABLE extension_repos")
        }

        driver.addColumn("mangas", "memo", "BLOB NOT NULL DEFAULT '{}'")
        driver.addColumn("chapters", "memo", "BLOB NOT NULL DEFAULT '{}'")
        driver.addColumn("extension_store", "extension_list_url", "TEXT")

        true
    }

    private suspend fun SqlDriver.tableExists(table: String): Boolean = exists(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'",
    )

    private suspend fun SqlDriver.columnExists(table: String, column: String): Boolean = exists(
        "SELECT 1 FROM pragma_table_info('$table') WHERE name = '$column'",
    )

    private suspend fun SqlDriver.exists(sql: String): Boolean = awaitQuery(
        null,
        sql,
        { cursor -> cursor.next().await() },
        0,
    )

    private suspend fun SqlDriver.execSql(sql: String) {
        await(null, sql, 0)
    }

    private suspend fun SqlDriver.addColumn(table: String, column: String, definition: String) {
        if (tableExists(table) && !columnExists(table, column)) {
            execSql("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }
}
