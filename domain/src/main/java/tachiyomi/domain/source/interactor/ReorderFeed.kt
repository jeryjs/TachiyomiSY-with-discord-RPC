package tachiyomi.domain.source.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.repository.FeedSavedSearchRepository

/**
 * SY-specific file
 */
@Inject
class ReorderFeed(
    private val feedSavedSearchRepository: FeedSavedSearchRepository,
) {

    private val mutex = Mutex()

    suspend fun changeOrder(feed: FeedSavedSearch, newIndex: Int, global: Boolean = true) = withContext(NonCancellable) {
        mutex.withLock {
            val feeds = if (global) {
                feedSavedSearchRepository.getGlobal()
                    .toMutableList()
            } else {
                feedSavedSearchRepository.getBySourceId(feed.source)
                    .toMutableList()
            }

            val currentIndex = feeds.indexOfFirst { it.id == feed.id }
            if (currentIndex == -1) {
                return@withContext Result.Unchanged
            }

            try {
                feeds.add(newIndex, feeds.removeAt(currentIndex))

                feedSavedSearchRepository.updateAllOrders(
                    orderedIds = feeds.map { it.id },
                    global = global,
                    sourceId = feed.source,
                )
                Result.Success
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Result.InternalError(e)
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class InternalError(val error: Throwable) : Result
    }
}
