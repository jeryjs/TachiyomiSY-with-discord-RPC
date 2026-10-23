package tachiyomi.domain.history.model

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.MangaCover
import kotlin.time.Instant

data class HistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val mangaId: Long,
    // SY -->
    val ogTitle: String,
    val userTitle: String? = null,
    // fork: kept so a history row can be named and resumed
    val chapter: Chapter? = null,
    // SY <--
    val chapterNumber: Double,
    val readAt: Instant?,
    val readDuration: Long,
    val coverData: MangaCover,
) {
    // SY -->
    val title: String = userTitle ?: ogTitle

    companion object {
        fun from(history: History, related: HistoryWithRelations, chapter: Chapter?): HistoryWithRelations {
            return HistoryWithRelations(
                id = history.id,
                chapterId = history.chapterId,
                mangaId = related.mangaId,
                ogTitle = related.ogTitle,
                userTitle = related.userTitle,
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
