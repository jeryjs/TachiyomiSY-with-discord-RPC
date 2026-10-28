package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import tachiyomi.domain.search.repository.SavedSearchRepository
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.repository.FeedSavedSearchRepository

/**
 * SY-specific file
 */
@Inject
class FeedRestorer(
    private val feedSavedSearchRepository: FeedSavedSearchRepository,
    private val savedSearchRepository: SavedSearchRepository,
) {
    suspend fun restoreFeeds(backupFeeds: List<BackupFeed>) {
        if (backupFeeds.isEmpty()) return

        val currentFeeds = feedSavedSearchRepository.getAllFeedWithSavedSearchName()

        backupFeeds
            .filter { backupFeed ->
                currentFeeds.none {
                    backupFeed.source == it.source &&
                        backupFeed.global == it.global &&
                        backupFeed.savedSearchName == it.savedSearchName
                }
            }.forEach { backupFeed ->
                val savedSearchId = backupFeed.savedSearchName?.let {
                    savedSearchRepository.findId(
                        source = backupFeed.source,
                        name = backupFeed.savedSearchName,
                    ) ?: return@forEach
                }

                feedSavedSearchRepository.insert(
                    FeedSavedSearch(
                        id = 0,
                        source = backupFeed.source,
                        savedSearch = savedSearchId,
                        global = backupFeed.global,
                        feedOrder = 0,
                    ),
                )
            }
    }
}
