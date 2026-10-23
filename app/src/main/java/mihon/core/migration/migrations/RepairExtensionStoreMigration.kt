package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.data.ExtensionStoreRepair

/**
 * SY-specific file
 *
 * Runs the repair that brings a database predating upstream's 40.sqm up to the schema the rest of the app
 * expects, on every start, so that a database which never had that migration applied is fixed and an intact
 * one is left alone. The SQL itself lives in the data module, where access to the driver is confined.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class RepairExtensionStoreMigration(
    private val extensionStoreRepair: ExtensionStoreRepair,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean =
        extensionStoreRepair.repair()
}
