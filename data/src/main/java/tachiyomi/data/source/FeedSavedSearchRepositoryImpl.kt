package tachiyomi.data.source

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.source.model.FeedSavedSearch
import tachiyomi.domain.source.model.FeedWithSavedSearchName
import tachiyomi.domain.source.model.SavedSearch
import tachiyomi.domain.source.repository.FeedSavedSearchRepository

/**
 * SY-specific file
 */
class FeedSavedSearchRepositoryImpl(
    private val database: Database,
) : FeedSavedSearchRepository {

    override suspend fun getGlobal(): List<FeedSavedSearch> {
        return database.feed_saved_searchQueries
            .selectAllGlobal(FeedSavedSearchMapper::map)
            .awaitAsList()
    }

    override fun getGlobalAsFlow(): Flow<List<FeedSavedSearch>> {
        return database.feed_saved_searchQueries
            .selectAllGlobal(FeedSavedSearchMapper::map)
            .subscribeToList()
    }

    override suspend fun getGlobalFeedSavedSearch(): List<SavedSearch> {
        return database.feed_saved_searchQueries
            .selectGlobalFeedSavedSearch(SavedSearchMapper::map)
            .awaitAsList()
    }

    override suspend fun countGlobal(): Long {
        return database.feed_saved_searchQueries
            .countGlobal()
            .awaitAsOne()
    }

    override suspend fun getBySourceId(sourceId: Long): List<FeedSavedSearch> {
        return database.feed_saved_searchQueries
            .selectBySource(sourceId = sourceId, mapper = FeedSavedSearchMapper::map)
            .awaitAsList()
    }

    override fun getBySourceIdAsFlow(sourceId: Long): Flow<List<FeedSavedSearch>> {
        return database.feed_saved_searchQueries
            .selectBySource(sourceId = sourceId, mapper = FeedSavedSearchMapper::map)
            .subscribeToList()
    }

    override suspend fun getBySourceIdFeedSavedSearch(sourceId: Long): List<SavedSearch> {
        return database.feed_saved_searchQueries
            .selectSourceFeedSavedSearch(sourceId = sourceId, mapper = SavedSearchMapper::map)
            .awaitAsList()
    }

    override suspend fun countBySourceId(sourceId: Long): Long {
        return database.feed_saved_searchQueries
            .countSourceFeedSavedSearch(sourceId = sourceId)
            .awaitAsOne()
    }

    override suspend fun getAllFeedWithSavedSearchName(): List<FeedWithSavedSearchName> {
        return database.feed_saved_searchQueries
            .selectAllFeedWithSavedSearchName { source, global, name ->
                FeedWithSavedSearchName(source, global, name)
            }
            .awaitAsList()
    }

    override suspend fun delete(feedSavedSearchId: Long) {
        database.feed_saved_searchQueries
            .deleteById(id = feedSavedSearchId)
    }

    override suspend fun insert(feedSavedSearch: FeedSavedSearch): Long {
        return database.transactionWithResult {
            database.feed_saved_searchQueries.insertReturningId(
                source = feedSavedSearch.source,
                savedSearch = feedSavedSearch.savedSearch,
                global = feedSavedSearch.global,
            ).awaitAsOne()
        }
    }

    override suspend fun insertAll(feedSavedSearch: List<FeedSavedSearch>) {
        return database.transaction {
            feedSavedSearch.forEach {
                database.feed_saved_searchQueries.insert(
                    source = it.source,
                    savedSearch = it.savedSearch,
                    global = it.global,
                )
            }
        }
    }

    override suspend fun updateAllOrders(orderedIds: List<Long>, global: Boolean, sourceId: Long?) {
        database.transaction {
            val current = if (global) {
                database.feed_saved_searchQueries.getGlobalFeedIds().awaitAsList()
            } else {
                database.feed_saved_searchQueries.getSourceFeedIds(sourceId = requireNotNull(sourceId)).awaitAsList()
            }
            val ids = orderedIds.filter { it in current } + current.filterNot { it in orderedIds }
            ids.forEachIndexed { index, feedId ->
                database.feed_saved_searchQueries.updateOrder(order = index.toLong(), id = feedId)
            }
        }
    }
}
