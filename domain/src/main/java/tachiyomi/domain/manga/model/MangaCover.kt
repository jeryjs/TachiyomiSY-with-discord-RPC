package tachiyomi.domain.manga.model

/**
 * Contains the required data for MangaCoverFetcher
 */
data class MangaCover(
    val mangaId: Long,
    val sourceId: Long,
    val isMangaFavorite: Boolean,
    // SY -->
    val ogUrl: String?,
    val customThumbnailUrl: String? = null,
    // SY <--
    val lastModified: Long,
) {
    // SY -->
    val url: String? = customThumbnailUrl ?: ogUrl
    // SY <--
}

fun Manga.asMangaCover(): MangaCover {
    return MangaCover(
        mangaId = id,
        sourceId = source,
        isMangaFavorite = favorite,
        ogUrl = thumbnailUrl,
        lastModified = coverLastModified,
    )
}
