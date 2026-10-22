package mihon.core.migration.migrations

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.data.Database
import tachiyomi.data.category.CategoryMapper
import tachiyomi.domain.library.service.LibraryPreferences

@Inject
@ContributesIntoSet(AppScope::class)
class MoveSortingModeSettingsMigration(
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
    private val database: Database,
) : Migration {
    override val version: Float = 38f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        // Handle renamed enum values
        val newSortingMode = when (
            val oldSortingMode = prefs.getString(libraryPreferences.sortingMode.key(), "ALPHABETICAL")
        ) {
            "LAST_CHECKED" -> "LAST_MANGA_UPDATE"
            "UNREAD" -> "UNREAD_COUNT"
            "DATE_FETCHED" -> "CHAPTER_FETCH_DATE"
            "DRAG_AND_DROP" -> "ALPHABETICAL"
            else -> oldSortingMode
        }
        prefs.edit {
            putString(libraryPreferences.sortingMode.key(), newSortingMode)
        }
        database.transaction {
            database.categoriesQueries.getCategories(CategoryMapper::mapCategory).awaitAsList()
                .filter { (it.flags and 0b00111100L) == 0b00100000L }
                .forEach {
                    database.categoriesQueries.updateFlags(
                        categoryId = it.id,
                        flags = it.flags and 0b00111100L.inv(),
                    )
                }
        }

        return@withContext true
    }
}
