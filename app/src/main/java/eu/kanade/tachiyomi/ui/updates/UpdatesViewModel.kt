package eu.kanade.tachiyomi.ui.updates

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import eu.kanade.core.preference.asState
import eu.kanade.core.util.addOrRemove
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.lang.toLocalDate
import exh.source.EH_SOURCE_ID
import exh.source.EXH_SOURCE_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import logcat.LogPriority
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.library.model.ChapterSwipeAction
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class UpdatesViewModel(
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val getUpdates: GetUpdates = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    // SY -->
    readerPreferences: ReaderPreferences = Injekt.get(),
    // SY <--
) : ViewModel() {

    private val _events: Channel<Event> = Channel(Int.MAX_VALUE)
    val events: Flow<Event> = _events.receiveAsFlow()

    val lastUpdated by libraryPreferences.lastUpdatedTimestamp.asState(viewModelScope)

    // SY -->
    val preserveReadingPosition by readerPreferences.preserveReadingPosition.asState(viewModelScope)
    // SY <--

    // First and last selected index in list
    private val selectedPositions: Array<Int> = arrayOf(-1, -1)
    private val selectedChapterIds = MutableStateFlow(emptySet<Long>())

    private var incompleteDownloadRefreshJob: Job? = null
    private var incompleteDownloadsByChapterId: Map<Long, Boolean> = emptyMap()

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val downloadStates = MutableStateFlow(emptyMap<Long, DownloadProgress>())

    private val incompleteDownloadsRefreshTrigger = MutableStateFlow(0)

    private val expandedStates = MutableStateFlow<Map<Pair<Long, LocalDate>, Boolean>>(emptyMap())
    private var expandedStatesInitialized = false

    // Swipe actions for update Items.
    val chapterSwipeStartAction by libraryPreferences.swipeToEndAction.asState(viewModelScope)
    val chapterSwipeEndAction by libraryPreferences.swipeToStartAction.asState(viewModelScope)

    init {
        viewModelScope.launchIO {
            merge(downloadManager.statusFlow(), downloadManager.progressFlow())
                .catch { logcat(LogPriority.ERROR, it) }
                .collect(this@UpdatesViewModel::updateDownloadState)
        }
    }

    private fun updateDownloadState(download: Download) {
        val chapterId = download.chapter.id
        downloadStates.update {
            // Terminal states are derived by the queried item itself, so drop the override instead
            // of letting it outlive reality, e.g. showing a since deleted chapter as downloaded.
            if (download.status == Download.State.NOT_DOWNLOADED || download.status == Download.State.DOWNLOADED) {
                it - chapterId
            } else {
                it + (chapterId to DownloadProgress(download.status, download.progress))
            }
        }
    }

    private val hasActiveFilters = getUpdatesItemPreferenceFlow()
        .map { prefs ->
            listOf(
                prefs.filterUnread,
                prefs.filterDownloaded,
                prefs.filterStarted,
                prefs.filterBookmarked,
            )
                .any { it != TriState.DISABLED } ||
                prefs.filterExcludedScanlators ||
                listOf(
                    prefs.filterIncludedCategories,
                    prefs.filterExcludedCategories,
                )
                    .any { it.isNotEmpty() }
        }
        .distinctUntilChanged()

    private val updateItems = combine(
        // needed for SQL filters (unread, started, bookmarked, etc)
        getUpdatesItemPreferenceFlow()
            .distinctUntilChanged()
            .flatMapLatest {
                getUpdates.subscribe(
                    Clock.System.now().minus(3, DateTimeUnit.MONTH, TimeZone.currentSystemDefault()),
                    unread = it.filterUnread.toBooleanOrNull(),
                    started = it.filterStarted.toBooleanOrNull(),
                    bookmarked = it.filterBookmarked.toBooleanOrNull(),
                    hideExcludedScanlators = it.filterExcludedScanlators,
                    includedCategories = it.filterIncludedCategories,
                    excludedCategories = it.filterExcludedCategories,
                ).distinctUntilChanged()
            },
        downloadCache.changes,
        downloadManager.queueState,
        // needed for Kotlin filters (downloaded)
        getUpdatesItemPreferenceFlow().distinctUntilChanged { old, new ->
            old.filterDownloaded == new.filterDownloaded
        },
    ) { updates, _, _, itemPreferences ->
        updates
            .toUpdateItems()
            .applyFilters(itemPreferences)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(
        updateItems,
        selectedChapterIds,
        downloadStates,
        combine(dialog, hasActiveFilters) { dialog, hasActiveFilters -> dialog to hasActiveFilters },
        combine(expandedStates, incompleteDownloadsRefreshTrigger) { expanded, trigger -> expanded to trigger },
    ) { items, selectedIds, downloads, (dialog, hasActiveFilters), (expandedStateMap, refreshTrigger) ->
        @Suppress("UNUSED_EXPRESSION")
        refreshTrigger // re-group the items once the incomplete-download scan has finished
        val groupedItems = items.orEmpty()
        val resolvedExpandedStates = if (!expandedStatesInitialized && groupedItems.isNotEmpty()) {
            expandedStatesInitialized = true
            initializeExpandedStates(groupedItems).also { expandedStates.value = it }
        } else {
            expandedStateMap
        }
        State(
            isLoading = items == null,
            hasActiveFilters = hasActiveFilters,
            items = groupedItems.map { item ->
                val download = downloads[item.update.chapterId]
                item.copy(
                    selected = item.update.chapterId in selectedIds,
                    downloadStateProvider = if (download != null) {
                        { download.status }
                    } else {
                        item.downloadStateProvider
                    },
                    downloadProgressProvider = if (download != null) {
                        { download.progress }
                    } else {
                        item.downloadProgressProvider
                    },
                )
            },
            dialog = dialog,
            expandedStates = resolvedExpandedStates,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    private fun List<UpdatesItem>.applyFilters(
        preferences: ItemPreferences,
    ): List<UpdatesItem> {
        val filterDownloaded = preferences.filterDownloaded

        val filterFnDownloaded: (UpdatesItem) -> Boolean = {
            applyFilter(filterDownloaded) {
                it.downloadStateProvider() == Download.State.DOWNLOADED
            }
        }

        return fastFilter {
            filterFnDownloaded(it)
        }
    }

    private fun List<UpdatesWithRelations>.toUpdateItems(): List<UpdatesItem> {
        return this
            .map { update ->
                val activeDownload = downloadManager.getQueuedDownloadOrNull(update.chapterId)
                val downloaded = downloadManager.isChapterDownloaded(
                    update.chapterName,
                    update.scanlator,
                    update.chapterUrl,
                    // SY -->
                    update.ogMangaTitle,
                    // SY <--
                    update.sourceId,
                )
                val hasIncompleteDownload = !downloaded && activeDownload == null &&
                    incompleteDownloadsByChapterId[update.chapterId] == true
                val downloadState = when {
                    activeDownload != null -> activeDownload.status
                    downloaded -> Download.State.DOWNLOADED
                    hasIncompleteDownload -> Download.State.ERROR
                    else -> Download.State.NOT_DOWNLOADED
                }
                UpdatesItem(
                    update = update,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { activeDownload?.progress ?: 0 },
                )
            }
    }

    private fun scheduleIncompleteDownloadRefresh(expectedIds: List<Long>) {
        if (expectedIds.isEmpty()) return
        if (expectedIds.all { it in incompleteDownloadsByChapterId }) return

        incompleteDownloadRefreshJob?.cancel()
        incompleteDownloadRefreshJob = viewModelScope.launchIO {
            delay(250)

            val missingChapterIds = expectedIds.filterNot { it in incompleteDownloadsByChapterId }.toSet()
            if (missingChapterIds.isEmpty()) {
                return@launchIO
            }

            val updates = (state.value.items)
                .map { it.update }
                .filter { it.chapterId in missingChapterIds }

            if (updates.isEmpty()) return@launchIO

            val tmpArtifactsByManga = mutableMapOf<Pair<Long, String>, Set<String>>()
            val computed = updates.associate { update ->
                val hasIncompleteDownload = downloadManager.hasIncompleteChapterDownload(
                    update.chapterName,
                    update.scanlator,
                    update.chapterUrl,
                    update.ogMangaTitle,
                    update.sourceId,
                    tmpArtifactsByManga.getOrPut(update.sourceId to update.ogMangaTitle) {
                        downloadManager.getMangaChapterTmpArtifactNames(update.ogMangaTitle, update.sourceId)
                    },
                )
                update.chapterId to hasIncompleteDownload
            }

            incompleteDownloadsByChapterId += computed

            incompleteDownloadsRefreshTrigger.update { it + 1 }
        }
    }

    private fun initializeExpandedStates(items: List<UpdatesItem>): Map<Pair<Long, LocalDate>, Boolean> {
        val desired = mutableMapOf<Pair<Long, LocalDate>, Boolean>()
        val groupedUiModels = State(items = items).uiModels // Use a temporary State to group
        groupedUiModels.forEach { groupedItem ->
            if (groupedItem is UpdatesUiModel.Item && groupedItem.isFirstInGroup && groupedItem.hasUnreadItems) {
                desired[Pair(groupedItem.item.update.mangaId, groupedItem.item.update.dateFetch.toLocalDate())] = true
            }
        }
        return desired
    }

    fun updateLibrary(): Boolean {
        val started = LibraryUpdateJob.startNow(Injekt.get<Application>())
        viewModelScope.launch {
            _events.send(Event.LibraryUpdateTriggered(started))
        }
        return started
    }

    /**
     * Toggles the expanded state of an Item with subsequent chapters.
     *
     * @param item The update item whose group expanded state is to be toggled.
     */
    fun toggleGroupExpanded(item: UpdatesItem) {
        val mangaId = item.update.mangaId
        val date = item.update.dateFetch.toLocalDate()
        expandedStates.update {
            it.toMutableMap().apply {
                merge(Pair(mangaId, date), true, Boolean::xor)
            }
        }
    }

    fun downloadChapters(items: List<UpdatesItem>, action: ChapterDownloadAction) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            when (action) {
                ChapterDownloadAction.START -> {
                    downloadChapters(items)
                    if (items.any { it.downloadStateProvider() == Download.State.ERROR }) {
                        downloadManager.startDownloads()
                    }
                }
                ChapterDownloadAction.START_NOW -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    startDownloadingNow(chapterId)
                }
                ChapterDownloadAction.CANCEL -> {
                    val chapterId = items.singleOrNull()?.update?.chapterId ?: return@launch
                    cancelDownload(chapterId)
                }
                ChapterDownloadAction.DELETE -> {
                    deleteChapters(items)
                }
            }
            toggleAllSelection(false)
        }
    }

    private fun startDownloadingNow(chapterId: Long) {
        downloadManager.startDownloadNow(chapterId)
    }

    private fun cancelDownload(chapterId: Long) {
        val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return
        downloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
    }

    /**
     * Mark the selected updates list as read/unread.
     * @param updates the list of selected updates.
     * @param read whether to mark chapters as read or unread.
     */
    fun markUpdatesRead(updates: List<UpdatesItem>, read: Boolean) {
        viewModelScope.launchIO {
            setReadStatus.await(
                read = read,
                chapters = updates
                    .mapNotNull { getChapter.await(it.update.chapterId) }
                    .toTypedArray(),
            )
        }
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param updates the list of chapters to bookmark.
     */
    fun bookmarkUpdates(updates: List<UpdatesItem>, bookmark: Boolean) {
        viewModelScope.launchIO {
            updates
                .filterNot { it.update.bookmark == bookmark }
                .map { ChapterUpdate(id = it.update.chapterId, bookmark = bookmark) }
                .let { updateChapter.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param updatesItem the list of chapters to download.
     */
    private fun downloadChapters(updatesItem: List<UpdatesItem>) {
        viewModelScope.launchNonCancellable {
            val groupedUpdates = updatesItem.groupBy { it.update.mangaId }.values
            for (updates in groupedUpdates) {
                val mangaId = updates.first().update.mangaId
                val manga = getManga.await(mangaId) ?: continue
                // Don't download if source isn't available
                sourceManager.get(manga.source) ?: continue
                val chapters = updates.mapNotNull { getChapter.await(it.update.chapterId) }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Delete selected chapters
     *
     * @param updatesItem list of chapters
     */
    fun deleteChapters(updatesItem: List<UpdatesItem>) {
        viewModelScope.launchNonCancellable {
            updatesItem
                .groupBy { it.update.mangaId }
                .entries
                .forEach { (mangaId, updates) ->
                    val manga = getManga.await(mangaId) ?: return@forEach
                    val source = sourceManager.get(manga.source) ?: return@forEach
                    val chapters = updates.mapNotNull { getChapter.await(it.update.chapterId) }
                    downloadManager.deleteChapters(chapters, manga, source)
                }
        }
        toggleAllSelection(false)
    }

    fun showConfirmDeleteChapters(updatesItem: List<UpdatesItem>) {
        setDialog(Dialog.DeleteConfirmation(updatesItem))
    }

    fun toggleSelection(
        item: UpdatesItem,
        selected: Boolean,
        fromLongPress: Boolean = false,
    ) {
        val items = state.value.items
        val selectedIndex = items.indexOfFirst { it.update.chapterId == item.update.chapterId }
        if (selectedIndex < 0) return

        // Read selection from its own flow, not the derived items, which lag behind it.
        val currentSelection = selectedChapterIds.value
        if ((item.update.chapterId in currentSelection) == selected) return

        // Off the visible items, not the id set, which can retain ids filtered out of the list
        val firstSelection = items.none { it.selected }
        val newSelection = currentSelection.toHashSet()
        newSelection.addOrRemove(item.update.chapterId, selected)

        if (selected && fromLongPress) {
            if (firstSelection) {
                selectedPositions[0] = selectedIndex
                selectedPositions[1] = selectedIndex
            } else {
                // Try to select the items in-between when possible
                val range: IntRange
                if (selectedIndex < selectedPositions[0]) {
                    range = selectedIndex + 1..<selectedPositions[0]
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    range = (selectedPositions[1] + 1)..<selectedIndex
                    selectedPositions[1] = selectedIndex
                } else {
                    // Just select itself
                    range = IntRange.EMPTY
                }

                range.forEach { newSelection.add(items[it].update.chapterId) }
            }
        } else if (!fromLongPress) {
            if (!selected) {
                if (selectedIndex == selectedPositions[0]) {
                    selectedPositions[0] = items.indexOfFirst { it.update.chapterId in newSelection }
                } else if (selectedIndex == selectedPositions[1]) {
                    selectedPositions[1] = items.indexOfLast { it.update.chapterId in newSelection }
                }
            } else {
                if (selectedIndex < selectedPositions[0]) {
                    selectedPositions[0] = selectedIndex
                } else if (selectedIndex > selectedPositions[1]) {
                    selectedPositions[1] = selectedIndex
                }
            }
        }

        selectedChapterIds.update { newSelection }
    }

    fun toggleAllSelection(selected: Boolean) {
        val ids = if (selected) state.value.items.map { it.update.chapterId }.toSet() else emptySet()
        selectedChapterIds.update { ids }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun invertSelection() {
        val current = selectedChapterIds.value
        val ids = state.value.items
            .map { it.update.chapterId }
            .filterNot { it in current }
            .toSet()
        selectedChapterIds.update { ids }

        selectedPositions[0] = -1
        selectedPositions[1] = -1
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.update { dialog }
    }

    fun resetNewUpdatesCount() {
        libraryPreferences.newUpdatesCount.set(0)
    }

    fun updateSwipe(update: UpdatesItem, action: ChapterSwipeAction) {
        viewModelScope.launch {
            val item = update.update
            when(action) {
                ChapterSwipeAction.ToggleRead -> {
                    markUpdatesRead(listOf(update), !item.read)
                }
                ChapterSwipeAction.ToggleBookmark -> {
                    bookmarkUpdates(listOf(update), !item.bookmark)
                }
                ChapterSwipeAction.Download -> {
                    val downloadAction = when (update.downloadStateProvider()) {
                        Download.State.NOT_DOWNLOADED, Download.State.ERROR -> ChapterDownloadAction.START
                        Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
                        Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
                    }
                    downloadChapters(listOf(update), downloadAction)
                }
                ChapterSwipeAction.Disabled -> throw IllegalStateException()
            }
        }
    }

    private fun getUpdatesItemPreferenceFlow(): Flow<ItemPreferences> {
        return combine(
            updatesPreferences.filterDownloaded.changes(),
            updatesPreferences.filterUnread.changes(),
            updatesPreferences.filterStarted.changes(),
            updatesPreferences.filterBookmarked.changes(),
            updatesPreferences.filterExcludedScanlators.changes(),
            updatesPreferences.filterIncludedCategories.changes(),
            updatesPreferences.filterExcludedCategories.changes(),
        ) {
            @Suppress("UNCHECKED_CAST")
            ItemPreferences(
                filterDownloaded = it[0] as TriState,
                filterUnread = it[1] as TriState,
                filterStarted = it[2] as TriState,
                filterBookmarked = it[3] as TriState,
                filterExcludedScanlators = it[4] as Boolean,
                filterIncludedCategories = it[5] as List<Long>,
                filterExcludedCategories = it[6] as List<Long>,
            )
        }
    }

    fun showFilterDialog() {
        dialog.update { Dialog.FilterSheet }
    }

    @Immutable
    private data class ItemPreferences(
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterExcludedScanlators: Boolean,
        val filterIncludedCategories: List<Long>,
        val filterExcludedCategories: List<Long>,
    )

    private data class DownloadProgress(val status: Download.State, val progress: Int)

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val hasActiveFilters: Boolean = false,
        val items: List<UpdatesItem> = listOf(),
        val dialog: Dialog? = null,
        // SY -->
        val expandedStates: Map<Pair<Long, LocalDate>, Boolean> = emptyMap(),
        // SY <--
    ) {
        val selected = items.filter { it.selected }
        val selectionMode = selected.isNotEmpty()

        val uiModels by derivedStateOf {    // use derivedStateOf to avoid recomputing on every recomposition
            val uiModels = mutableListOf<UpdatesUiModel>()
            var currentMangaId: Long? = null
            var currentDate: LocalDate? = null

            for (i in items.indices) {
                val item = items[i]
                val mangaId = item.update.mangaId
                val itemDate = item.update.dateFetch.toLocalDate()

                if (itemDate != currentDate) {
                    uiModels.add(UpdatesUiModel.Header(itemDate))
                    currentDate = itemDate
                    currentMangaId = null // Reset manga group for new date
                }

                val isFirstInGroup = mangaId != currentMangaId
                var hasSubsequentItems = false
                var hasUnreadItemsInGroup = !item.update.read

                for (j in (i + 1) until items.size) {
                    val nextItem = items[j]
                    if (nextItem.update.mangaId == mangaId && nextItem.update.dateFetch.toLocalDate() == currentDate) {
                        hasSubsequentItems = true
                        if (!nextItem.update.read) hasUnreadItemsInGroup = true
                    } else break
                }

                uiModels.add(
                    UpdatesUiModel.Item(
                        item = item,
                        isFirstInGroup = isFirstInGroup,
                        hasSubsequentItems = hasSubsequentItems,
                        hasUnreadItems = hasUnreadItemsInGroup,
                        groupDate = itemDate,
                    ),
                )
                if (isFirstInGroup) currentMangaId = mangaId
            }
            uiModels
        }
    }

    sealed interface Dialog {
        data class DeleteConfirmation(val toDelete: List<UpdatesItem>) : Dialog
        data object FilterSheet : Dialog
    }

    sealed interface Event {
        data object InternalError : Event
        data class LibraryUpdateTriggered(val started: Boolean) : Event
    }
}

private fun TriState.toBooleanOrNull(): Boolean? {
    return when (this) {
        TriState.DISABLED -> null
        TriState.ENABLED_IS -> true
        TriState.ENABLED_NOT -> false
    }
}

@Immutable
data class UpdatesItem(
    val update: UpdatesWithRelations,
    val downloadStateProvider: () -> Download.State,
    val downloadProgressProvider: () -> Int,
    val selected: Boolean = false,
) {
    // SY -->
    fun isEhBasedUpdate(): Boolean {
        return update.sourceId == EH_SOURCE_ID || update.sourceId == EXH_SOURCE_ID
    }
    // SY <--
}
