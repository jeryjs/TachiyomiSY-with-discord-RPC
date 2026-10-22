package eu.kanade.presentation.history.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.R
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Favorite
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun HistoryItem(
    history: HistoryWithRelations,
    isPreviousHistory: Boolean = false,
    expanded: Boolean = false,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickExpand: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClickResume)
            .height(if (isPreviousHistory) 60.dp else 96.dp)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier
                .alpha(if (isPreviousHistory) 0f else 1f)
                .fillMaxHeight(),
            data = history.coverData,
            onClick = onClickCover,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            val textStyle = MaterialTheme.typography.bodyMedium
            val formattedReadAtString = remember {
                history.readAt?.let {
                    SimpleDateFormat("EEE, d MMM yyyy 'at' h:mm a", Locale.getDefault()).format(it)
                } ?: ""
            }

            if (!isPreviousHistory) {
                Text(
                    text = history.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = textStyle,
                )
            }
            Text(
                text = history.chapter?.name ?: stringResource(
                    MR.strings.display_mode_chapter,
                    formatChapterNumber(history.chapterNumber),
                ),
                style = textStyle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            AnimatedVisibility(visible = !expanded) {
                Text(
                    text = stringResource(MR.strings.label_read_chapters) + " " +
                        if (isPreviousHistory) formattedReadAtString
                        else relativeTimeSpanString(history.readAt?.time ?: 0),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = textStyle,
                )
            }
        }

        if (!isPreviousHistory) {
            Icon(
                painter = rememberAnimatedVectorPainter(
                    AnimatedImageVector.animatedVectorResource(R.drawable.animated_caret_flip),
                    !expanded,
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickableNoIndication { onClickExpand() }
                    .padding(start = 4.dp)
                    .fillMaxHeight(),
            )

            if (!history.coverData.isMangaFavorite) {
                IconButton(onClick = onClickFavorite) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Favorite,
                        contentDescription = stringResource(MR.strings.add_to_library),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            IconButton(onClick = onClickDelete) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = stringResource(MR.strings.action_delete),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun HistoryItemPreviews(
    @PreviewParameter(HistoryWithRelationsProvider::class)
    historyWithRelations: HistoryWithRelations,
) {
    TachiyomiPreviewTheme {
        Surface {
            HistoryItem(
                history = historyWithRelations,
                onClickCover = {},
                onClickResume = {},
                onClickExpand = {},
                onClickDelete = {},
                onClickFavorite = {},
            )
        }
    }
}


