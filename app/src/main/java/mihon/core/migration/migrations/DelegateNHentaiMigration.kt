package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.online.all.NHentai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.migration.MigrateUtils
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.data.Database

@Inject
@ContributesIntoSet(AppScope::class)
class DelegateNHentaiMigration(
    private val database: Database,
) : Migration {
    override val version: Float = 6f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        MigrateUtils.updateSourceId(database, NHentai.otherId, 6907)

        return@withContext true
    }
}
