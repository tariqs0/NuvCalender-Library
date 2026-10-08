package com.nuvio.app.features.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.PendingTrackingMembershipRemoval
import com.nuvio.app.features.library.TrackingMembershipRemovalConfirmationHost
import com.nuvio.app.features.library.executeTrackingMembershipOperation
import com.nuvio.app.features.library.showTrackingMembershipRewriteFeedback
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.tracking.TrackingMembershipApplyResult
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.watchedItemKeys
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_close
import nuvio.composeapp.generated.resources.calendar_mark_episode_unwatched
import nuvio.composeapp.generated.resources.calendar_mark_episode_watched
import nuvio.composeapp.generated.resources.calendar_mark_episodes_watched
import nuvio.composeapp.generated.resources.calendar_released
import nuvio.composeapp.generated.resources.calendar_source
import nuvio.composeapp.generated.resources.calendar_upcoming
import nuvio.composeapp.generated.resources.hero_add_to_library
import nuvio.composeapp.generated.resources.hero_mark_unwatched
import nuvio.composeapp.generated.resources.hero_mark_watched
import nuvio.composeapp.generated.resources.hero_remove_from_library
import nuvio.composeapp.generated.resources.home_view_details
import nuvio.composeapp.generated.resources.tracking_lists_update_failed
import org.jetbrains.compose.resources.stringResource

/**
 * Resolves watched state for entries: the specific episodes for episodic entries, else the whole
 * title. Collected once per screen so a month grid of chips shares a single subscription.
 */
@Composable
internal fun rememberCalendarWatchedResolver(): (CalendarEntry) -> Boolean {
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val fullyWatchedSeriesKeys by WatchedRepository.fullyWatchedSeriesKeys.collectAsStateWithLifecycle()
    val watchedKeys = watchedUiState.watchedKeys
    return remember(watchedKeys, fullyWatchedSeriesKeys) {
        { entry ->
            if (entry.isEpisodic) {
                entry.episodes.all { episode ->
                    watchedItemKeys(
                        type = entry.preview.type,
                        id = entry.preview.id,
                        season = episode.season,
                        episode = episode.episode,
                    ).any(watchedKeys::contains)
                }
            } else {
                WatchingState.isPosterWatched(
                    watchedKeys = watchedKeys,
                    item = entry.preview,
                    fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
                )
            }
        }
    }
}

@Composable
internal fun CalendarEntryDialog(
    entry: CalendarEntry,
    today: CalendarDay,
    onDismiss: () -> Unit,
    onViewDetails: (MetaPreview) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    val preview = entry.preview
    val isWatched = rememberCalendarWatchedResolver()(entry)
    val libraryUiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val isSaved = remember(libraryUiState, preview.id, preview.type) {
        LibraryRepository.isSaved(preview.id, preview.type)
    }
    var pendingTrackingRemoval by remember(entry.key) { mutableStateOf<PendingTrackingMembershipRemoval?>(null) }
    val trackingFailedMessage = stringResource(Res.string.tracking_lists_update_failed)

    val onToggleSaved: () -> Unit = {
        scope.launch {
            val libraryItem = preview.toLibraryItem(savedAtEpochMs = 0L)
            val toggle: suspend (Set<TrackingProviderId>) -> TrackingMembershipApplyResult = { confirmed ->
                LibraryRepository.toggleSaved(item = libraryItem, confirmedRemovalProviders = confirmed)
            }
            val showFailure: suspend (Throwable) -> Unit = { error ->
                NuvioToastController.show(error.message ?: trackingFailedMessage)
            }
            executeTrackingMembershipOperation(
                operation = { toggle(emptySet()) },
                onSuccess = { result ->
                    if (result.requiresRemovalConfirmation) {
                        pendingTrackingRemoval = PendingTrackingMembershipRemoval(
                            itemTitle = libraryItem.name,
                            confirmations = result.requiredRemovalConfirmations,
                            retry = toggle,
                            onApplied = { applied -> showTrackingMembershipRewriteFeedback(applied) },
                            onFailure = showFailure,
                        )
                    } else {
                        showTrackingMembershipRewriteFeedback(result)
                    }
                },
                onFailure = showFailure,
            )
        }
    }

    val onToggleWatched: () -> Unit = {
        if (entry.isEpisodic) {
            val items = entry.episodes.map { episode ->
                WatchedItem(
                    id = preview.id,
                    type = preview.type,
                    name = episode.title?.takeIf(String::isNotBlank) ?: preview.name,
                    poster = entry.thumbnail ?: preview.banner ?: preview.poster,
                    releaseInfo = preview.releaseInfo,
                    season = episode.season,
                    episode = episode.episode,
                    videoId = episode.videoId,
                    markedAtEpochMs = 0L,
                )
            }
            if (isWatched) WatchedRepository.unmarkWatched(items) else WatchedRepository.markWatched(items)
        } else {
            scope.launch { WatchingActions.togglePosterWatched(preview) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(NuvioTokens.Space.s16)
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
            color = tokens.colors.surfaceDialog,
            contentColor = tokens.colors.textPrimary,
            shape = tokens.shapes.dialog,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(tokens.colors.surfaceCard),
                ) {
                    NuvioAsyncImage(
                        model = entry.thumbnail ?: preview.banner ?: preview.poster,
                        contentDescription = preview.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.45f to Color.Transparent,
                                    1f to tokens.colors.surfaceDialog,
                                ),
                            ),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(NuvioTokens.Space.s8),
                    ) {
                        Surface(color = Color.Black.copy(alpha = 0.45f), shape = tokens.shapes.avatar) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(Res.string.calendar_close),
                                tint = Color.White,
                                modifier = Modifier.padding(NuvioTokens.Space.s6).size(NuvioTokens.Icon.md),
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.padding(
                        start = NuvioTokens.Space.s20,
                        end = NuvioTokens.Space.s20,
                        bottom = NuvioTokens.Space.s20,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CalendarKindBadge(kind = entry.kind)
                        Text(
                            text = stringResource(
                                if (entry.day > today) Res.string.calendar_upcoming else Res.string.calendar_released,
                            ) + " · " + formatCalendarDayLong(entry.day),
                            style = MaterialTheme.typography.labelLarge,
                            color = tokens.colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = preview.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    calendarEpisodeLine(entry)?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.colors.textSecondary,
                        )
                    }
                    preview.description?.takeIf(String::isNotBlank)?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    entry.sourceLabel?.takeIf(String::isNotBlank)?.let { source ->
                        Text(
                            text = stringResource(Res.string.calendar_source, source),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textMuted,
                        )
                    }

                    NuvioPrimaryButton(
                        text = stringResource(Res.string.home_view_details),
                        modifier = Modifier.padding(top = NuvioTokens.Space.s6),
                        onClick = {
                            onDismiss()
                            onViewDetails(preview)
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                        CalendarDialogAction(
                            label = stringResource(
                                if (isSaved) Res.string.hero_remove_from_library else Res.string.hero_add_to_library,
                            ),
                            icon = if (isSaved) Icons.Default.Check else Icons.Default.Add,
                            active = isSaved,
                            onClick = onToggleSaved,
                            modifier = Modifier.weight(1f),
                        )
                        CalendarDialogAction(
                            label = stringResource(watchedLabel(entry, isWatched)),
                            icon = if (isWatched) Icons.Default.CheckCircle else Icons.Default.CheckCircleOutline,
                            active = isWatched,
                            onClick = onToggleWatched,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    pendingTrackingRemoval?.let { pending ->
        TrackingMembershipRemovalConfirmationHost(
            pending = pending,
            onPendingChange = { pendingTrackingRemoval = it },
        )
    }
}

private fun watchedLabel(entry: CalendarEntry, isWatched: Boolean) = when {
    !entry.isEpisodic -> if (isWatched) Res.string.hero_mark_unwatched else Res.string.hero_mark_watched
    isWatched -> Res.string.calendar_mark_episode_unwatched
    entry.episodes.size > 1 -> Res.string.calendar_mark_episodes_watched
    else -> Res.string.calendar_mark_episode_watched
}

@Composable
private fun CalendarDialogAction(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(NuvioTokens.Space.s48),
        shape = tokens.shapes.button,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) tokens.colors.overlaySelected else Color.Transparent,
            contentColor = tokens.colors.textPrimary,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(NuvioTokens.Icon.sm),
            tint = if (active) tokens.colors.accent else tokens.colors.textPrimary,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = NuvioTokens.Space.s8),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
