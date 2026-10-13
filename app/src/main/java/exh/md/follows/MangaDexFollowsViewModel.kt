package exh.md.follows

import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.source.BaseSourcePagingSource
import tachiyomi.domain.manga.model.Manga

class MangaDexFollowsViewModel(sourceId: Long) : BrowseSourceViewModel(sourceId, null) {
    companion object {
        val SOURCE_ID_KEY = CreationExtras.Key<Long>()

        val Factory = viewModelFactory {
            initializer {
                MangaDexFollowsViewModel(
                    sourceId = get(SOURCE_ID_KEY)!!,
                )
            }
        }
    }

    override fun createSourcePagingSource(query: String, filters: FilterList): BaseSourcePagingSource {
        return MangaDexFollowsPagingSource(source.getMainSource() as MangaDex)
    }

    override fun Flow<Manga>.combineMetadata(metadata: RaisedSearchMetadata?): Flow<Pair<Manga, RaisedSearchMetadata?>> {
        return map { it to metadata }
    }

    init {
        update { it.copy(filterable = false) }
    }
}
