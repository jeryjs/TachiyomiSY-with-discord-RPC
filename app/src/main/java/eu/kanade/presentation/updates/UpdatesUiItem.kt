package eu.kanade.presentation.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.updates.UpdatesItem
import eu.kanade.tachiyomi.util.chapter.getSwipeAction
import eu.kanade.tachiyomi.util.chapter.swipeActionThreshold
import me.saket.swipe.SwipeableActionsBox
import tachiyomi.domain.library.model.ChapterSwipeAction
import tachiyomi.domain.updates.model.UpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.selectedBackground
import java.time.LocalDate

internal fun LazyListScope.updatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "updates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.updatesUiItems(
    uiModels: List<UpdatesUiModel>,
    expandedStates: Map<Pair<Long, LocalDate>, Boolean>,
    selectionMode: Boolean,
    // SY -->
    preserveReadingPosition: Boolean,
    // SY <--
    updateSwipeToStart: ChapterSwipeAction,
    updateSwipeToEnd: ChapterSwipeAction,
    onUpdateSelected: (UpdatesItem, Boolean, Boolean) -> Unit,
    onClickCover: (UpdatesItem) -> Unit,
    onClickUpdate: (UpdatesItem) -> Unit,
    onExpandClicked: (UpdatesItem) -> Unit,
    onDownloadChapter: (List<UpdatesItem>, ChapterDownloadAction) -> Unit,
    onUpdateSwipe: (UpdatesItem, ChapterSwipeAction) -> Unit,
) {


    items(
        items = uiModels,
        contentType = {
            when (it) {
                is UpdatesUiModel.Header -> "header"
                is UpdatesUiModel.Item -> "item"
            }
        },
        key = {
            when (it) {
                is UpdatesUiModel.Header -> "updatesHeader-${it.hashCode()}"
                is UpdatesUiModel.Item -> "updates-${it.item.update.mangaId}-${it.item.update.chapterId}"
            }
        },
    ) { groupedItem ->
        when (groupedItem) {
            is UpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItemFastScroll(),
                    text = relativeDateText(groupedItem.date),
                )
            }

            is UpdatesUiModel.Item -> {
                val updatesItem = groupedItem.item
                val mangaId = updatesItem.update.mangaId
                val groupDate = groupedItem.groupDate
                val isFirstInGroup = groupedItem.isFirstInGroup
                val hasSubsequentChapters = groupedItem.hasSubsequentItems
                val expanded = expandedStates[Pair(mangaId, groupDate)] == true

                AnimatedVisibility(
                    visible = isFirstInGroup || expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    UpdatesUiItem(
                        modifier = Modifier.animateItemFastScroll(),
                        update = updatesItem.update,
                        selected = updatesItem.selected,
                        readProgress = updatesItem.update.lastPageRead
                            .takeIf {
                                /* SY --> */(
                                !updatesItem.update.read ||
                                    (preserveReadingPosition && updatesItem.isEhBasedUpdate())
                                )/* SY <-- */ &&
                                it > 0L
                            }
                            ?.let {
                                stringResource(
                                    MR.strings.chapter_progress,
                                    it + 1,
                                )
                            },
                        updateSwipeToStart = updateSwipeToStart,
                        updateSwipeToEnd = updateSwipeToEnd,
                        onLongClick = {
                            onUpdateSelected(updatesItem, !updatesItem.selected, true)
                        },
                        onClick = {
                            when {
                                selectionMode -> onUpdateSelected(updatesItem, !updatesItem.selected, false)
                                else -> onClickUpdate(updatesItem)
                            }
                        },
                        onClickCover = { onClickCover(updatesItem) }.takeIf { isFirstInGroup && !selectionMode },
                        onDownloadChapter = { action: ChapterDownloadAction ->
                            onDownloadChapter(listOf(updatesItem), action)
                        }.takeIf { !selectionMode },
                        downloadStateProvider = updatesItem.downloadStateProvider,
                        downloadProgressProvider = updatesItem.downloadProgressProvider,
                        isFirstInGroup = isFirstInGroup,
                        expanded = expanded,
                        onExpandClick = { onExpandClicked(updatesItem) }.takeIf { isFirstInGroup && hasSubsequentChapters },
                        onUpdateSwipe = { onUpdateSwipe(updatesItem, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesUiItem(
    update: UpdatesWithRelations,
    selected: Boolean,
    readProgress: String?,
    updateSwipeToStart: ChapterSwipeAction,
    updateSwipeToEnd: ChapterSwipeAction,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    isFirstInGroup: Boolean,
    expanded: Boolean,
    onExpandClick: (() -> Unit)?,
    onUpdateSwipe: (ChapterSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f

    val start = getSwipeAction(
        action = updateSwipeToStart,
        read = update.read,
        bookmark = update.bookmark,
        downloadState = downloadStateProvider(),
        background = MaterialTheme.colorScheme.primaryContainer,
        onSwipe = { onUpdateSwipe(updateSwipeToStart) },
    )
    val end = getSwipeAction(
        action = updateSwipeToEnd,
        read = update.read,
        bookmark = update.bookmark,
        downloadState = downloadStateProvider(),
        background = MaterialTheme.colorScheme.primaryContainer,
        onSwipe = { onUpdateSwipe(updateSwipeToEnd) },
    )

    SwipeableActionsBox(
        modifier = Modifier.clipToBounds(),
        startActions = listOfNotNull(start),
        endActions = listOfNotNull(end),
        swipeThreshold = swipeActionThreshold,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = modifier
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        onLongClick()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
                .height(if (isFirstInGroup) 56.dp else 40.dp)   // shorter height for subsequent chapters
                .padding(horizontal = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Square(
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxHeight()
                    .alpha(if (isFirstInGroup) 1f else 0f), // Hide cover for subsequent items in group
                data = update.coverData,
                onClick = onClickCover,
            )

            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                if (isFirstInGroup) {
                    Text(
                        text = update.mangaTitle,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = textAlpha),
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    var textHeight by remember { mutableIntStateOf(0) }
                    if (!update.read) {
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = stringResource(MR.strings.unread),
                            modifier = Modifier
                                .height(8.dp)
                                .padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (update.bookmark) {
                        Icon(
                            imageVector = Icons.Filled.Bookmark,
                            contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                            modifier = Modifier
                                .sizeIn(maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp }),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Text(
                        text = update.chapterName,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = textAlpha),
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textHeight = it.size.height },
                        modifier = Modifier
                            .weight(weight = 1f, fill = false),
                    )
                    if (readProgress != null) {
                        DotSeparatorText()
                        Text(
                            text = readProgress,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (onExpandClick != null) {
                Icon(
                    painter = rememberAnimatedVectorPainter(
                        AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down),
                        !expanded,
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickableNoIndication { onExpandClick() }
                        .padding(start = 4.dp)
                        .fillMaxHeight(),
                )
            }

            ChapterDownloadIndicator(
                enabled = onDownloadChapter != null,
                modifier = Modifier.padding(start = 4.dp),
                downloadStateProvider = downloadStateProvider,
                downloadProgressProvider = downloadProgressProvider,
                onClick = { onDownloadChapter?.invoke(it) },
            )
        }
    }
}
