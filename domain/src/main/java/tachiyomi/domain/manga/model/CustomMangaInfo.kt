package tachiyomi.domain.manga.model

/**
 * SY-specific file.
 *
 * The user-edited details of an entry are stored in the manga row; this only carries the incognito flag.
 */
data class CustomMangaInfo(
    val id: Long,
    val incognitoMode: Boolean? = null,
)
