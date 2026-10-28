package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.backupFeedMapper
import tachiyomi.domain.source.repository.FeedSavedSearchRepository

/**
 * SY-specific file
 */
@Inject
class FeedBackupCreator(
    private val feedSavedSearchRepository: FeedSavedSearchRepository,
) {

    suspend operator fun invoke(): List<BackupFeed> {
        return feedSavedSearchRepository.getAllFeedWithSavedSearchName().map {
            backupFeedMapper(it.source, it.global, it.savedSearchName)
        }
    }
}
