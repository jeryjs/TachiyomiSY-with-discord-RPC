package tachiyomi.data

import app.cash.sqldelight.ExecutableQuery
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import exh.source.MERGED_SOURCE_ID
import tachiyomi.view.UpdatesView
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private val mapper = { cursor: SqlCursor ->
    UpdatesView(
        cursor.getLong(0)!!,
        cursor.getString(1)!!,
        cursor.getLong(2)!!,
        cursor.getString(3)!!,
        cursor.getString(4),
        cursor.getString(5)!!,
        cursor.getLong(6)!! == 1L,
        cursor.getLong(7)!! == 1L,
        cursor.getLong(8)!!,
        cursor.getLong(9)!!,
        cursor.getLong(10)!! == 1L,
        cursor.getString(11),
        cursor.getLong(12)!!,
        cursor.getLong(13)!!,
        cursor.getLong(14)!!,
        cursor.getString(15),
    )
}

fun getUpdatesQuery(
    after: Long,
    limit: Long,
    read: Boolean?,
    started: Long?,
    bookmarked: Boolean?,
    hideExcludedScanlators: Long,
    includedEmpty: Boolean,
    includedCategories: Collection<Long>,
    excludedEmpty: Boolean,
    excludedCategories: Collection<Long>,
): UpdatesQuery {
    return UpdatesQuery(
        driver = Injekt.get<SqlDriver>(),
        after = after,
        limit = limit,
        read = read,
        started = started,
        bookmarked = bookmarked,
        hideExcludedScanlators = hideExcludedScanlators,
        includedEmpty = includedEmpty,
        includedCategories = includedCategories,
        excludedEmpty = excludedEmpty,
        excludedCategories = excludedCategories,
    )
}

class UpdatesQuery(
    val driver: SqlDriver,
    val after: Long,
    val limit: Long,
    val read: Boolean?,
    val started: Long?,
    val bookmarked: Boolean?,
    val hideExcludedScanlators: Long,
    val includedEmpty: Boolean,
    val includedCategories: Collection<Long>,
    val excludedEmpty: Boolean,
    val excludedCategories: Collection<Long>,
) : ExecutableQuery<UpdatesView>(mapper) {
    private fun createArguments(count: Int): String {
        if (count == 0) return "()"

        return buildString(count * 2 + 1) {
            append("(?")
            repeat(count - 1) {
                append(",?")
            }
            append(')')
        }
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> {
        val includedCategoriesIndexes = createArguments(count = includedCategories.size)
        val excludedCategoriesIndexes = createArguments(count = excludedCategories.size)
        return driver.executeQuery(
            null,
            // language=sqldelight
            """
                SELECT *
                FROM (
                    -- Normal source
                    SELECT
                        mangas._id AS mangaId,
                        mangas.title AS mangaTitle,
                        chapters._id AS chapterId,
                        chapters.name AS chapterName,
                        chapters.scanlator,
                        chapters.url AS chapterUrl,
                        chapters.read,
                        chapters.bookmark,
                        chapters.last_page_read,
                        mangas.source,
                        mangas.favorite,
                        mangas.thumbnail_url AS thumbnailUrl,
                        mangas.cover_last_modified AS coverLastModified,
                        chapters.date_upload AS dateUpload,
                        chapters.date_fetch AS datefetch,
                        excluded_scanlators.scanlator AS excludedScanlator
                    FROM mangas
                    JOIN chapters
                        ON mangas._id = chapters.manga_id
                    LEFT JOIN excluded_scanlators
                        ON mangas._id = excluded_scanlators.manga_id
                        AND chapters.scanlator = excluded_scanlators.scanlator
                    WHERE mangas.source <> $MERGED_SOURCE_ID
                    AND date_fetch > date_added

                    UNION ALL

                    -- Merged source
                    SELECT
                        mangas._id AS mangaId,
                        mangas.title AS mangaTitle,
                        chapters._id AS chapterId,
                        chapters.name AS chapterName,
                        chapters.scanlator,
                        chapters.url AS chapterUrl,
                        chapters.read,
                        chapters.bookmark,
                        chapters.last_page_read,
                        mangas.source,
                        mangas.favorite,
                        mangas.thumbnail_url AS thumbnailUrl,
                        mangas.cover_last_modified AS coverLastModified,
                        chapters.date_upload AS dateUpload,
                        chapters.date_fetch AS datefetch,
                        excluded_scanlators.scanlator AS excludedScanlator
                    FROM mangas
                    LEFT JOIN (
                        SELECT merged.manga_id, merged.merge_id
                        FROM merged
                        GROUP BY merged.merge_id
                    ) AS ME
                        ON ME.merge_id = mangas._id
                    JOIN chapters
                        ON ME.manga_id = chapters.manga_id
                    LEFT JOIN excluded_scanlators
                        ON ME.merge_id = excluded_scanlators.manga_id
                        AND chapters.scanlator = excluded_scanlators.scanlator
                    WHERE mangas.source = $MERGED_SOURCE_ID
                    AND date_fetch > date_added
                ) AS combined
                WHERE
                    favorite = 1
                    AND dateUpload > :after
                    AND (:read IS NULL OR read = :read)
                    AND (
                        :started IS NULL
                        OR (:started = 1 AND last_page_read > 0 AND read = 0)
                        OR (:started = 0 AND last_page_read = 0 AND read = 0)
                    )
                    AND (:bookmarked IS NULL OR bookmark = :bookmarked)
                    AND (
                        excludedScanlator IS NULL OR :hideExcludedScanlators = 0
                    )
                    AND (
                        -- includedEmpty being true expresses "don't care" state and bypasses the membership filter
                        :includedEmpty
                        OR EXISTS (
                            SELECT 1 FROM mangas_categories
                            WHERE mangas_categories.manga_id = mangaId
                            AND COALESCE(mangas_categories.category_id, 0) IN $includedCategoriesIndexes
                        )
                        OR (0 IN $includedCategoriesIndexes AND NOT EXISTS (
                            SELECT 1 FROM mangas_categories mc WHERE mc.manga_id = mangaId)
                        )
                    )
                    AND (
                        -- excludedEmpty being true expresses "don't care" state and bypasses the membership filter
                        :excludedEmpty
                        OR (
                            NOT EXISTS (
                                SELECT 1 FROM mangas_categories
                                WHERE mangas_categories.manga_id = mangaId
                                AND COALESCE(mangas_categories.category_id, 0) IN $excludedCategoriesIndexes
                            )
                            AND (
                                0 NOT IN $excludedCategoriesIndexes
                                OR EXISTS (SELECT 1 FROM mangas_categories WHERE mangas_categories.manga_id = mangaId)
                            )
                        )
                    )
                ORDER BY datefetch DESC
            LIMIT :limit;
            """.trimIndent(),
            mapper,
            8 + includedCategories.size + includedCategories.size + excludedCategories.size + excludedCategories.size,
            binders = {
                var parameterIndex = 0
                bindLong(parameterIndex++, after)
                bindBoolean(parameterIndex++, read)
                bindLong(parameterIndex++, started)
                bindBoolean(parameterIndex++, bookmarked)
                bindLong(parameterIndex++, hideExcludedScanlators)
                bindBoolean(parameterIndex++, includedEmpty)
                includedCategories.forEach { includedCategories_ ->
                    bindLong(parameterIndex++, includedCategories_)
                }
                includedCategories.forEach { includedCategories__ ->
                    bindLong(parameterIndex++, includedCategories__)
                }
                bindBoolean(parameterIndex++, excludedEmpty)
                excludedCategories.forEach { excludedCategories_ ->
                    bindLong(parameterIndex++, excludedCategories_)
                }
                excludedCategories.forEach { excludedCategories__ ->
                    bindLong(parameterIndex++, excludedCategories__)
                }
                bindLong(parameterIndex++, limit)
            },
        )
    }

    override fun toString(): String = "LibraryQuery.sq:get"
}
