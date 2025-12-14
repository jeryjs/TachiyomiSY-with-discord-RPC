package tachiyomi.domain.updates.model

import kotlinx.coroutines.runBlocking
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import uy.kohesive.injekt.injectLazy

data class UpdatesWithRelations(
    val mangaId: Long,
    // SY -->
    val ogMangaTitle: String,
    // SY <--
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val chapterUrl: String,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val sourceId: Long,
    val dateFetch: Long,
    val coverData: MangaCover,
) {
    // SY -->
    val mangaTitle: String = getCustomMangaInfo.get(mangaId)?.title ?: ogMangaTitle
    val chapter by lazy { runBlocking { getChapter.await(chapterId) } }

    companion object {
        private val getCustomMangaInfo: GetCustomMangaInfo by injectLazy()
        private val getChapter: GetChapter by injectLazy()
    }
    // SY <--
}
