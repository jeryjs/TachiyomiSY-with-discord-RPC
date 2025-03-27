package eu.kanade.tachiyomi.data.track.anilist.dto

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.util.lang.htmlDecode
import java.text.SimpleDateFormat
import java.util.Locale

data class ALManga(
    val remoteId: Long,
    val title: String,
    val imageUrl: String,
    val description: String?,
    val format: String,
    val publishingStatus: String,
    val startDateFuzzy: Long,
    val totalVolumes: Long,
    val totalChapters: Long,
    val averageScore: Int,
    val staff: ALStaff,
) {
    fun toTrack() = TrackSearch.create(TrackerManager.ANILIST).apply {
        remote_id = remoteId
        title = this@ALManga.title
        total_volumes = totalVolumes
        total_chapters = totalChapters
        cover_url = imageUrl
        summary = description?.htmlDecode() ?: ""
        score = averageScore.toDouble()
        tracking_url = AnilistApi.mangaUrl(remote_id)
        publishing_status = publishingStatus
        publishing_type = format
        if (startDateFuzzy != 0L) {
            start_date = try {
                val outputDf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                outputDf.format(startDateFuzzy)
            } catch (e: IllegalArgumentException) {
                ""
            }
        }
        staff.edges.forEach {
            val name = it.node.name() ?: return@forEach
            if ("Story" in it.role) authors += name
            if ("Art" in it.role) artists += name
        }
    }
}

data class ALUserManga(
    val libraryId: Long,
    val listStatus: String,
    val scoreRaw: Int,
    val volumesRead: Int,
    val chaptersRead: Int,
    val startDateFuzzy: Long,
    val completedDateFuzzy: Long,
    val manga: ALManga,
    val private: Boolean,
) {
    fun toTrack() = Track.create(TrackerManager.ANILIST).apply {
        remote_id = manga.remoteId
        title = manga.title
        status = toTrackStatus()
        score = scoreRaw.toDouble()
        started_reading_date = startDateFuzzy
        finished_reading_date = completedDateFuzzy
        last_volume_read = volumesRead.toDouble()
        last_chapter_read = chaptersRead.toDouble()
        library_id = libraryId
        total_volumes = manga.totalVolumes
        total_chapters = manga.totalChapters
        private = this@ALUserManga.private
    }

    private fun toTrackStatus() = when (listStatus) {
        "CURRENT" -> Anilist.READING
        "COMPLETED" -> Anilist.COMPLETED
        "PAUSED" -> Anilist.ON_HOLD
        "DROPPED" -> Anilist.DROPPED
        "PLANNING" -> Anilist.PLAN_TO_READ
        "REPEATING" -> Anilist.REREADING
        else -> throw NotImplementedError("Unknown status: $listStatus")
    }
}
