package tachiyomi.domain.manga.repository

import tachiyomi.domain.manga.model.CustomMangaInfo

/**
 * SY-specific file.
 *
 * Only the per-manga incognito flag is left here. The details a user edits about an entry live in the manga
 * row itself, but incognito has no column to sit in, so it keeps a store of its own.
 */
interface CustomMangaRepository {

    fun get(mangaId: Long): CustomMangaInfo?

    fun set(mangaInfo: CustomMangaInfo)
}
