package exh.ui.smartsearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.feature.migration.list.search.SmartSourceSearchEngine
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SmartSearchViewModel(
    sourceId: Long,
    private val config: SourcesScreen.SmartSearchConfig,
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    sourceManager: SourceManager = Injekt.get(),
) : ViewModel() {

    val state: StateFlow<SearchResults?>
        field = MutableStateFlow(null)

    companion object {
        val SOURCE_ID_KEY = CreationExtras.Key<Long>()
        val SMART_SEARCH_CONFIG_KEY = CreationExtras.Key<SourcesScreen.SmartSearchConfig>()

        val Factory = viewModelFactory {
            initializer {
                SmartSearchViewModel(
                    sourceId = get(SOURCE_ID_KEY)!!,
                    config = get(SMART_SEARCH_CONFIG_KEY)!!,
                )
            }
        }
    }
    private val smartSearchEngine = SmartSourceSearchEngine(null)

    val source = sourceManager.getOrStub(sourceId)

    init {
        viewModelScope.launchIO {
            val result = try {
                val resultManga = smartSearchEngine.deepSearch(source, config.origTitle)
                if (resultManga != null) {
                    val localManga = networkToLocalManga(resultManga)
                    SearchResults.Found(localManga)
                } else {
                    SearchResults.NotFound
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    throw e
                } else {
                    SearchResults.Error
                }
            }

            state.value = result
        }
    }

    sealed class SearchResults {
        data class Found(val manga: Manga) : SearchResults()
        data object NotFound : SearchResults()
        data object Error : SearchResults()
    }
}
