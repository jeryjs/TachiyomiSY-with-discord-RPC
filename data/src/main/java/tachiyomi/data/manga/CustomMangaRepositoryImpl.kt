package tachiyomi.data.manga

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaRepository
import java.io.File
import kotlin.concurrent.thread

/**
 * SY-specific file.
 *
 * Keeps the per-manga incognito flag. It used to share `edits.json` with the user-edited details, but those
 * live in the manga row now and upstream's migration deletes that file once it has read them, so the flag
 * keeps a file of its own. The old file's flags are moved over by CustomIncognitoRescueMigration before that
 * migration runs.
 */
class CustomMangaRepositoryImpl(context: Context) : CustomMangaRepository {
    private val incognitoJson by lazy { File(context.getExternalFilesDir(null), "incognito.json") }

    // Read off the main thread as soon as the repository is created, since screens ask for the flag while drawing
    private val customMangaMap by lazy { fetchCustomData() }

    init {
        thread(name = "CustomMangaInfoPreload") { customMangaMap }
    }

    override fun get(mangaId: Long) = customMangaMap[mangaId]

    private fun fetchCustomData(): MutableMap<Long, CustomMangaInfo> {
        if (!incognitoJson.exists() || !incognitoJson.isFile) return mutableMapOf()

        val json = try {
            Json.decodeFromString<MangaList>(
                incognitoJson.bufferedReader().use { it.readText() },
            )
        } catch (e: Exception) {
            null
        } ?: return mutableMapOf()

        val mangasJson = json.mangas ?: return mutableMapOf()
        return mangasJson
            .mapNotNull { mangaJson ->
                val id = mangaJson.id ?: return@mapNotNull null
                id to mangaJson.toManga()
            }
            .toMap()
            .toMutableMap()
    }

    override fun set(mangaInfo: CustomMangaInfo) {
        if (mangaInfo.incognitoMode != true) {
            customMangaMap.remove(mangaInfo.id)
        } else {
            customMangaMap[mangaInfo.id] = mangaInfo
        }
        saveCustomInfo()
    }

    private fun saveCustomInfo() {
        val jsonElements = customMangaMap.values.map { it.toJson() }
        if (jsonElements.isNotEmpty()) {
            incognitoJson.delete()
            incognitoJson.writeText(Json.encodeToString(MangaList(jsonElements)))
        } else {
            incognitoJson.delete()
        }
    }

    @Serializable
    data class MangaList(
        val mangas: List<MangaJson>? = null,
    )

    @Serializable
    data class MangaJson(
        var id: Long? = null,
        val incognitoMode: Boolean? = null,
    ) {
        fun toManga() = CustomMangaInfo(
            id = this@MangaJson.id!!,
            incognitoMode = this@MangaJson.incognitoMode,
        )
    }

    fun CustomMangaInfo.toJson(): MangaJson {
        return MangaJson(
            id,
            incognitoMode,
        )
    }
}
