package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import exh.md.utils.FollowStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class TrackChapter(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
) {

    suspend fun await(context: Context, mangaId: Long, chapterNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            val tracks = getTracks.await(mangaId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (
                    service == null ||
                    !service.isLoggedIn ||
                    chapterNumber <= track.lastChapterRead /* SY --> */ ||
                    (service is MdList && track.status == FollowStatus.UNFOLLOWED.long)/* SY <-- */
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val updatedTrack = service.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastChapterRead = chapterNumber)

                            val wasRereading = service.getRereadingStatus() != -1L && updatedTrack.status == service.getRereadingStatus()
                            val remoteTrack = service.update(updatedTrack.toDbTrack(), true)
                            val completedReread = wasRereading && remoteTrack.status == service.getCompletionStatus()

                            val domainTrack = remoteTrack.toDomainTrack(idRequired = true)
                            domainTrack?.let { insertTrack.await(it) }

                            if (completedReread && service.supportsRereadCount()) {
                                val newCount = remoteTrack.reread_count.toInt() + 1
                                runCatching {
                                    service.setRemoteRereadCount(remoteTrack, newCount)
                                }.onSuccess {
                                    domainTrack?.let { track ->
                                        insertTrack.await(track.copy(rereadCount = newCount.toLong()))
                                    }
                                }.onFailure {
                                    logcat(LogPriority.WARN, it) { "Failed to update reread count for tracker ${service.name}" }
                                }
                            }

                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, chapterNumber)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
