package io.github.wearmedia.watch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import io.github.wearmedia.watch.LocalTouchScroll
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.Protocol
import io.github.wearmedia.watch.QueueItem
import io.github.wearmedia.watch.QueueState
import io.github.wearmedia.watch.R
import kotlinx.coroutines.flow.distinctUntilChanged

/** Number of non-track items at the top of the list (the header). */
private const val HEADER_ITEMS = 1

private val SLEEP_OPTIONS = listOf(5, 10, 15, 30, 45, 60, 90)

/** The playing track and what comes after it, like Spotify's "Fila de reproducción". */
@Composable
fun QueueScreen(
    nowPlaying: NowPlaying,
    queue: QueueState,
    link: PhoneLink,
    isActive: Boolean,
    onPlayed: () -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    var showSleepPicker by remember { mutableStateOf(false) }
    RequestFocusWhenActive(focusRequester, isActive && !showSleepPicker)

    // Rows start at the playing track.
    val first = queue.current.coerceIn(0, queue.total)
    val count = queue.total - first

    // Open at the header every time the queue page is shown.
    LaunchedEffect(isActive) {
        if (!isActive) {
            showSleepPicker = false
            return@LaunchedEffect
        }
        link.requestQueuePage(first)
        listState.scrollToItem(0)
    }

    // Fetch pages lazily as they scroll into view.
    LaunchedEffect(listState, queue) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { visible ->
                visible.forEach { listIndex ->
                    val i = first + listIndex - HEADER_ITEMS
                    if (listIndex >= HEADER_ITEMS && i < queue.total && i !in queue.items) link.requestQueuePage(i)
                }
            }
    }

    // Swiping down closes the queue (the list itself scrolls with the bezel).
    val closeDistance = with(LocalDensity.current) { 60.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, amount ->
                        dragged += amount
                        if (dragged > closeDistance) {
                            dragged = Float.NEGATIVE_INFINITY
                            onClose()
                        }
                    },
                )
            },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 48.dp),
            // Touch swipes go to the pager (back to the player); the bezel browses the queue.
            userScrollEnabled = LocalTouchScroll.current,
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
        ) {
            item(key = "header") {
                QueueHeader(
                    nowPlaying = nowPlaying,
                    position = "${queue.current + 1}/${queue.total}",
                    onShuffle = link::toggleShuffle,
                    onRepeat = link::cycleRepeat,
                    onSleepTimer = { showSleepPicker = true },
                )
            }
            items(count = count, key = { first + it }) { offset ->
                val i = first + offset
                QueueRow(
                    link = link,
                    item = queue.items[i],
                    isCurrent = i == queue.current,
                    onClick = {
                        link.playIndex(i)
                        onPlayed()
                    },
                )
            }
        }

        AnimatedVisibility(visible = showSleepPicker, enter = fadeIn(), exit = fadeOut()) {
            SleepTimerPicker(
                isActive = nowPlaying.sleepEndAt != 0L,
                onPick = { minutes ->
                    link.setSleepTimer(minutes)
                    showSleepPicker = false
                },
                onDismiss = { showSleepPicker = false },
            )
        }
    }
}

@Composable
private fun QueueHeader(
    nowPlaying: NowPlaying,
    position: String,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSleepTimer: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            stringResource(R.string.queue_title),
            style = WearText.header.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            modifier = Modifier.padding(horizontal = 30.dp),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(R.string.now_playing, nowPlaying.title),
            style = WearText.subtitle.copy(textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 26.dp),
        )
        BasicText(position, style = WearText.itemSubtitle)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
            val capabilities = nowPlaying.capabilities
            if (capabilities.shuffle) {
                ModeButton(
                    icon = Icons.Rounded.Shuffle,
                    active = nowPlaying.shuffle,
                    onClick = onShuffle,
                )
            }
            if (capabilities.repeat) {
                ModeButton(
                    icon = if (nowPlaying.repeatMode == Protocol.Repeat.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    active = nowPlaying.repeatMode != Protocol.Repeat.OFF,
                    onClick = onRepeat,
                )
            }
            if (capabilities.sleepTimer) ModeButton(
                icon = Icons.Rounded.Timer,
                active = nowPlaying.sleepEndAt != 0L,
                label = sleepTimerText(nowPlaying.sleepEndAt),
                onClick = onSleepTimer,
            )
        }
    }
}

@Composable
private fun ModeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit, label: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircleIconButton(
            icon = icon,
            size = 44.dp,
            iconSize = 28.dp,
            onClick = onClick,
            tint = if (active) WearColors.accent else Color.White,
        )
        // Keeps the row the same height whether or not a timer is running.
        BasicText(label ?: "", style = WearText.itemSubtitle.copy(fontSize = 11.sp, color = WearColors.accent))
    }
}

@Composable
private fun QueueRow(link: PhoneLink, item: QueueItem?, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = item != null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(link, item?.artUri.orEmpty(), fallback = Icons.Rounded.MusicNote, size = 42.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                text = item?.title ?: "…",
                style = WearText.itemTitle.copy(fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BasicText(
                text = item?.artist ?: "",
                style = WearText.itemSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Spacer(Modifier.width(6.dp))
            PlayingIndicator(Modifier.size(14.dp, 18.dp))
        }
    }
}

@Composable
private fun SleepTimerPicker(isActive: Boolean, onPick: (minutes: Int) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    RequestFocusWhenActive(focusRequester, true)
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 30.dp, end = 30.dp, top = 30.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000))
            .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
    ) {
        item {
            BasicText(
                stringResource(R.string.sleep_timer),
                style = WearText.header,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (isActive) {
            item { PickerChip(stringResource(R.string.sleep_timer_off), highlighted = true) { onPick(0) } }
        }
        SLEEP_OPTIONS.forEach { minutes ->
            item { PickerChip(stringResource(R.string.minutes, minutes)) { onPick(minutes) } }
        }
        item { PickerChip(stringResource(R.string.cancel), onClick = onDismiss) }
    }
}

@Composable
internal fun PickerChip(text: String, highlighted: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (highlighted) WearColors.accent else WearColors.card)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = WearText.body.copy(fontWeight = FontWeight.Medium))
    }
}
