package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.data.Database

@Inject
@ContributesIntoSet(AppScope::class)
class DeleteOldMangaDexTracksMigration(
    private val database: Database,
) : Migration {
    override val version: Float = 17f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        // Delete old mangadex trackers
        database.ehQueries.deleteBySyncId(6)
        return@withContext true
    }
}
