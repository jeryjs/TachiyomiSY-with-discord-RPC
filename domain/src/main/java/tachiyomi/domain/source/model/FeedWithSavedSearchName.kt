package tachiyomi.domain.source.model

/**
 * SY-specific file
 */
data class FeedWithSavedSearchName(
    val source: Long,
    val global: Boolean,
    val savedSearchName: String?,
)
