package tachiyomi.domain.search.repository

import tachiyomi.domain.search.model.SavedSearch

/**
 * SY-specific file
 */
interface SavedSearchRepository {

    suspend fun getAll(): List<SavedSearch>

    suspend fun findId(source: Long, name: String): Long?

    suspend fun insert(search: SavedSearch)

    suspend fun insertReturningId(search: SavedSearch): Long

    suspend fun insertAll(searches: List<SavedSearch>)

    suspend fun deleteAll()
}
