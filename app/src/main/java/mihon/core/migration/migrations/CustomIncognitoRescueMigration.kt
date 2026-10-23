package mihon.core.migration.migrations

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaRepository
import java.io.File

/**
 * SY-specific file
 *
 * The per-manga incognito flag used to be written into edits.json alongside everything else a user can edit
 * about an entry. Upstream's CustomInfoJsonToDbMigration reads that file into the manga row and then deletes
 * it, and it has no field for the flag, so the flags are copied into this fork's own store first.
 *
 * That migration also decodes the file with a strict reader that lists only its own fields, so the fork's
 * extra flag would make it throw and lose the edited details on the way out. The file is therefore written
 * back without that field, which leaves it readable for the migration that runs after this one.
 *
 * Runs always, which sorts it before every versioned migration, so both happen before the file is removed.
 */
@Inject
@ContributesIntoSet(AppScope::class)
class CustomIncognitoRescueMigration(
    private val context: Context,
    private val customMangaRepository: CustomMangaRepository,
) : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withContext(Dispatchers.IO) {
        val editJson = File(context.getExternalFilesDir(null), "edits.json")
        if (!editJson.exists() || !editJson.isFile) return@withContext true

        val json = try {
            Json { ignoreUnknownKeys = true }.decodeFromString<MangaList>(
                editJson.bufferedReader().use { it.readText() },
            )
        } catch (e: Exception) {
            null
        } ?: return@withContext true

        val mangas = json.mangas ?: return@withContext true

        mangas.forEach { mangaJson ->
            val id = mangaJson.id
            if (id != null && mangaJson.incognitoMode == true) {
                customMangaRepository.set(CustomMangaInfo(id, incognitoMode = true))
            }
        }

        // Rewrite the file without the flag so the migration that reads it next can still parse it. A failure
        // here leaves the file as it was, which is the state it would have been in anyway.
        try {
            val readable = ReadableList(
                mangas = mangas.map { it.toReadable() },
            )
            editJson.writeText(Json.encodeToString(readable))
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not clean up edits.json" }
        }

        true
    }

    @Serializable
    private data class MangaList(
        val mangas: List<MangaJson>? = null,
    )

    @Serializable
    private data class MangaJson(
        val id: Long? = null,
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val thumbnailUrl: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Long? = null,
        val incognitoMode: Boolean? = null,
    ) {
        fun toReadable() = ReadableManga(
            id = id,
            title = title,
            author = author,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            description = description,
            genre = genre,
            status = status,
        )
    }

    @Serializable
    private data class ReadableManga(
        val id: Long? = null,
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val thumbnailUrl: String? = null,
        val description: String? = null,
        val genre: List<String>? = null,
        val status: Long? = null,
    )

    @Serializable
    private data class ReadableList(
        val mangas: List<ReadableManga>? = null,
    )
}
