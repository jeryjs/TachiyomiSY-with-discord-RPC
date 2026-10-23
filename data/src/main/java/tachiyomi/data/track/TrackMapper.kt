package tachiyomi.data.track

import tachiyomi.domain.track.model.Track
import kotlin.time.Instant

object TrackMapper {
    fun mapTrack(
        id: Long,
        mangaId: Long,
        trackerId: Long,
        remoteId: Long,
        remoteUrl: String,
        libraryId: Long?,
        title: String,
        lastVolumeRead: Double,
        totalVolumes: Long,
        lastChapterRead: Double,
        totalChapters: Long,
        status: Long,
        score: Double,
        startDate: Instant?,
        finishDate: Instant?,
        private: Boolean,
        rereadCount: Long,
    ): Track = Track(
        id = id,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = remoteId,
        libraryId = libraryId,
        title = title,
        lastVolumeRead = lastVolumeRead,
        totalVolumes = totalVolumes,
        lastChapterRead = lastChapterRead,
        totalChapters = totalChapters,
        status = status,
        score = score,
        remoteUrl = remoteUrl,
        startDate = startDate,
        finishDate = finishDate,
        private = private,
        rereadCount = rereadCount,
    )
}
