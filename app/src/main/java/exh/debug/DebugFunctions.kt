package exh.debug

import android.app.Application
import com.elvishew.xlog.XLog
import com.pushtorefresh.storio.sqlite.queries.RawQuery
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.tables.MangaTable
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import exh.EH_SOURCE_ID
import exh.EXHMigrations
import exh.EXH_SOURCE_ID
import exh.eh.EHentaiThrottleManager
import exh.eh.EHentaiUpdateWorker
import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.base.getFlatMetadataForManga
import exh.metadata.metadata.base.insertFlatMetadata
import exh.savedsearches.JsonSavedSearch
import exh.util.await
import exh.util.cancellable
import exh.util.jobScheduler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.injectLazy
import java.lang.RuntimeException

@OptIn(FlowPreview::class)
object DebugFunctions {
    val app: Application by injectLazy()
    val db: DatabaseHelper by injectLazy()
    val prefs: PreferencesHelper by injectLazy()
    val sourceManager: SourceManager by injectLazy()

    fun forceUpgradeMigration() {
        prefs.eh_lastVersionCode().set(1)
        EXHMigrations.upgrade(prefs)
    }

    fun forceSetupJobs() {
        prefs.eh_lastVersionCode().set(0)
        EXHMigrations.upgrade(prefs)
    }

    fun resetAgedFlagInEXHManga() {
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    null
                } else manga
            }.toList()

            allManga.forEach { manga ->
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>() ?: return@forEach
                // remove age flag
                meta.aged = false
                db.insertFlatMetadata(meta.flatten()).await()
            }
        }
    }
    private val throttleManager = EHentaiThrottleManager()

    fun getDelegatedSourceList(): String = SourceManager.currentDelegatedSources.map { it.value.sourceName + " : " + it.value.sourceId + " : " + it.value.factory }.joinToString(separator = "\n")

    fun resetEHGalleriesForUpdater() {
        throttleManager.resetThrottle()
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    null
                } else manga
            }.toList()
            val eh = sourceManager.get(EH_SOURCE_ID)
            val ex = sourceManager.get(EXH_SOURCE_ID)

            allManga.forEach { manga ->
                throttleManager.throttle()
                (
                    when (manga.source) {
                        EH_SOURCE_ID -> eh
                        EXH_SOURCE_ID -> ex
                        else -> return@forEach
                    }
                    )?.fetchMangaDetails(manga)?.map { networkManga ->
                    manga.copyFrom(networkManga)
                    manga.initialized = true
                    db.insertManga(manga).executeAsBlocking()
                }
            }
        }
    }

    fun getEHMangaListWithAgedFlagInfo(): String {
        val galleries = mutableListOf(String())
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    null
                } else manga
            }.toList()

            allManga.forEach { manga ->
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>() ?: return@forEach
                galleries += "Aged: ${meta.aged}\t Title: ${manga.title}"
            }
        }
        return galleries.joinToString(",\n")
    }

    fun countAgedFlagInEXHManga(): Int {
        var agedAmount = 0
        runBlocking {
            val metadataManga = db.getFavoriteMangaWithMetadata().await()

            val allManga = metadataManga.asFlow().cancellable().mapNotNull { manga ->
                if (manga.source != EH_SOURCE_ID && manga.source != EXH_SOURCE_ID) {
                    null
                } else manga
            }.toList()

            allManga.forEach { manga ->
                val meta = db.getFlatMetadataForManga(manga.id!!).await()?.raise<EHentaiSearchMetadata>() ?: return@forEach
                if (meta.aged) {
                    // remove age flag
                    agedAmount++
                }
            }
        }
        return agedAmount
    }

    fun addAllMangaInDatabaseToLibrary() {
        db.inTransaction {
            db.lowLevel().executeSQL(
                RawQuery.builder()
                    .query(
                        """
                        UPDATE ${MangaTable.TABLE}
                            SET ${MangaTable.COL_FAVORITE} = 1
                        """.trimIndent()
                    )
                    .affectsTables(MangaTable.TABLE)
                    .build()
            )
        }
    }

    fun countMangaInDatabaseInLibrary() = db.getMangas().executeAsBlocking().count { it.favorite }

    fun countMangaInDatabaseNotInLibrary() = db.getMangas().executeAsBlocking().count { !it.favorite }

    fun countMangaInDatabase() = db.getMangas().executeAsBlocking().size

    fun countMetadataInDatabase() = db.getSearchMetadata().executeAsBlocking().size

    fun countMangaInLibraryWithMissingMetadata() = db.getMangas().executeAsBlocking().count {
        it.favorite && db.getSearchMetadataForManga(it.id!!).executeAsBlocking() == null
    }

    fun clearSavedSearches() = prefs.eh_savedSearches().set(emptySet())

    fun listAllSources() = sourceManager.getCatalogueSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun listAllSourcesClassName() = sourceManager.getCatalogueSources().joinToString("\n") {
        "${it::class.qualifiedName}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun listVisibleSources() = sourceManager.getVisibleCatalogueSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun listAllHttpSources() = sourceManager.getOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }
    fun listVisibleHttpSources() = sourceManager.getVisibleOnlineSources().joinToString("\n") {
        "${it.id}: ${it.name} (${it.lang.toUpperCase()})"
    }

    fun convertAllEhentaiGalleriesToExhentai() = convertSources(EH_SOURCE_ID, EXH_SOURCE_ID)

    fun convertAllExhentaiGalleriesToEhentai() = convertSources(EXH_SOURCE_ID, EH_SOURCE_ID)

    fun testLaunchEhentaiBackgroundUpdater(): String {
        return EHentaiUpdateWorker.launchBackgroundTest(app)
    }

    fun rescheduleEhentaiBackgroundUpdater() {
        EHentaiUpdateWorker.scheduleBackground(app)
    }

    fun listScheduledJobs() = app.jobScheduler.allPendingJobs.map { j ->
        """
        {
            info: ${j.id},
            isPeriod: ${j.isPeriodic},
            isPersisted: ${j.isPersisted},
            intervalMillis: ${j.intervalMillis},
        }
        """.trimIndent()
    }.joinToString(",\n")

    fun cancelAllScheduledJobs() = app.jobScheduler.cancelAll()

    private fun convertSources(from: Long, to: Long) {
        db.lowLevel().executeSQL(
            RawQuery.builder()
                .query(
                    """
                    UPDATE ${MangaTable.TABLE}
                        SET ${MangaTable.COL_SOURCE} = $to
                        WHERE ${MangaTable.COL_SOURCE} = $from
                    """.trimIndent()
                )
                .affectsTables(MangaTable.TABLE)
                .build()
        )
    }

    fun copyEHentaiSavedSearchesToExhentai() {
        runBlocking {
            val source = sourceManager.get(EH_SOURCE_ID) as? CatalogueSource ?: return@runBlocking
            val newSource = sourceManager.get(EXH_SOURCE_ID) as? CatalogueSource ?: return@runBlocking
            val savedSearches = prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != source.id) return@mapNotNull null
                    Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                } catch (t: RuntimeException) {
                    // Load failed
                    XLog.e("Failed to load saved search!", t)
                    t.printStackTrace()
                    null
                }
            }.toMutableList()
            savedSearches += prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != newSource.id) return@mapNotNull null
                    Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                } catch (t: RuntimeException) {
                    // Load failed
                    XLog.e("Failed to load saved search!", t)
                    t.printStackTrace()
                    null
                }
            }.filterNot { newSavedSearch -> savedSearches.any { it.name == newSavedSearch.name } }

            val otherSerialized = prefs.eh_savedSearches().get().filter {
                !it.startsWith("${newSource.id}:")
            }
            val newSerialized = savedSearches.map {
                "${newSource.id}:" + Json.encodeToString(it)
            }
            prefs.eh_savedSearches().set((otherSerialized + newSerialized).toSet())
        }
    }

    fun copyExhentaiSavedSearchesToEHentai() {
        runBlocking {
            val source = sourceManager.get(EXH_SOURCE_ID) as? CatalogueSource ?: return@runBlocking
            val newSource = sourceManager.get(EH_SOURCE_ID) as? CatalogueSource ?: return@runBlocking
            val savedSearches = prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != source.id) return@mapNotNull null
                    Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                } catch (t: RuntimeException) {
                    // Load failed
                    XLog.e("Failed to load saved search!", t)
                    t.printStackTrace()
                    null
                }
            }.toMutableList()
            savedSearches += prefs.eh_savedSearches().get().mapNotNull {
                try {
                    val id = it.substringBefore(':').toLong()
                    if (id != newSource.id) return@mapNotNull null
                    Json.decodeFromString<JsonSavedSearch>(it.substringAfter(':'))
                } catch (t: RuntimeException) {
                    // Load failed
                    XLog.e("Failed to load saved search!", t)
                    t.printStackTrace()
                    null
                }
            }.filterNot { newSavedSearch -> savedSearches.any { it.name == newSavedSearch.name } }

            val otherSerialized = prefs.eh_savedSearches().get().filter {
                !it.startsWith("${newSource.id}:")
            }
            val newSerialized = savedSearches.map {
                "${newSource.id}:" + Json.encodeToString(it)
            }
            prefs.eh_savedSearches().set((otherSerialized + newSerialized).toSet())
        }
    }
}
