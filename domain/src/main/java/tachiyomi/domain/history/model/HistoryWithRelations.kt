package tachiyomi.domain.history.model

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.MangaCover
import java.util.Date

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    // SY -->
    val ogTitle: String,
    val customMangaInfo: CustomMangaInfo? = null,
    val chapter: Chapter? = null,
    // SY <--
    val chapterNumber: Double,
    val readAt: Date?,
    val readDuration: Long,
    val coverData: MangaCover,
) {
    // SY -->
    val title: String = customMangaInfo?.title ?: ogTitle

    companion object {
        fun from(history: History, related: HistoryWithRelations, chapter: Chapter?): HistoryWithRelations {
            return HistoryWithRelations(
                id = history.id,
                chapterId = history.chapterId,
                mangaId = related.mangaId,
                ogTitle = related.ogTitle,
                customMangaInfo = related.customMangaInfo,
                chapter = chapter,
                chapterNumber = chapter?.chapterNumber ?: -1.0,
                readAt = history.readAt,
                readDuration = history.readDuration,
                coverData = related.coverData,
            )
        }
    }
    // SY <--
}
