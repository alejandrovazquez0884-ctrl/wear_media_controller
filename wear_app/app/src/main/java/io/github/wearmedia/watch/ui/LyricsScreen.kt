package io.github.wearmedia.watch.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import io.github.wearmedia.watch.LyricLine
import io.github.wearmedia.watch.Lyrics
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.R
import kotlinx.coroutines.delay
import kotlin.math.abs

/** After the user scrolls by hand, wait this long before following the song again. */
private const val MANUAL_SCROLL_HOLD_MS = 3000L

@Composable
fun LyricsScreen(nowPlaying: NowPlaying, lyrics: Lyrics?, artwork: ImageBitmap?, link: PhoneLink, isActive: Boolean) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isActive, nowPlaying.mediaId) {
        if (isActive) link.requestLyrics(nowPlaying.mediaId)
    }

    Box(Modifier.fillMaxSize()) {
        val current = lyrics?.takeIf { it.mediaId == nowPlaying.mediaId }
        when (current) {
            is Lyrics.Synced -> SyncedLyrics(current.lines, nowPlaying, link, focusRequester, isActive)
            is Lyrics.Plain -> PlainLyrics(current.text, focusRequester, isActive)
            is Lyrics.None -> Message(stringResource(R.string.no_lyrics))
            is Lyrics.Loading, null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PlayingIndicator(Modifier.size(16.dp, 18.dp))
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    nowPlaying: NowPlaying,
    link: PhoneLink,
    focusRequester: FocusRequester,
    isActive: Boolean,
) {
    RequestFocusWhenActive(focusRequester, isActive)
    val position by produceState(nowPlaying.currentPositionMs(), nowPlaying) {
        while (true) {
            value = nowPlaying.currentPositionMs()
            if (!nowPlaying.isPlaying) break
            delay(100)
        }
    }
    val activeIndex = remember(lines, position) { lines.indexOfLast { it.startMs <= position } }

    // Start on the current line, so coming back to the screen doesn't replay the whole song.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = activeIndex.coerceAtLeast(0))
    ReportScroll(isActive) { listState.scrollThumb() }
    var manualScrollAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableLongStateOf(0L) }

    // A scroll we didn't start ourselves means the user is browsing.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && autoScrolling == 0L) manualScrollAt = SystemClock.elapsedRealtime()
        }
    }

    // Nothing scrolls while the screen is off; coming back jumps straight to the current line.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isVisible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var lastTarget by remember { mutableIntStateOf(-1) }

    LaunchedEffect(activeIndex, manualScrollAt, isVisible) {
        if (!isVisible) {
            lastTarget = -1
            return@LaunchedEffect
        }
        val sinceManual = SystemClock.elapsedRealtime() - manualScrollAt
        if (sinceManual < MANUAL_SCROLL_HOLD_MS) delay(MANUAL_SCROLL_HOLD_MS - sinceManual)
        val target = activeIndex.coerceAtLeast(0)
        // Animate only the usual line-to-line step; jump on seeks and when coming back.
        val jump = lastTarget < 0 || abs(target - lastTarget) > 2
        lastTarget = target
        autoScrolling = SystemClock.elapsedRealtime()
        try {
            if (jump) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        } finally {
            autoScrolling = 0L
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Padding puts the scrolled-to line near the middle of the round screen.
        val topPadding = maxHeight * 0.38f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = topPadding, bottom = maxHeight * 0.55f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            // Touch swipes go to the pager (back to the player); the bezel browses the lines.
            userScrollEnabled = false,
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
        ) {
            itemsIndexed(lines) { index, line ->
                val isActive = index == activeIndex
                val color by animateColorAsState(
                    when {
                        isActive -> Color.White
                        index < activeIndex -> Color(0x73FFFFFF)
                        else -> Color(0xA6FFFFFF)
                    },
                    label = "lyricColor",
                )
                BasicText(
                    text = line.text,
                    style = WearText.body.copy(
                        color = color,
                        fontSize = if (isActive) 19.sp else 16.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = if (isActive) 23.sp else 20.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp)
                        .clickable {
                            manualScrollAt = 0L
                            link.seekTo(line.startMs)
                        },
                )
            }
        }
    }
}

@Composable
private fun PlainLyrics(text: String, focusRequester: FocusRequester, isActive: Boolean) {
    RequestFocusWhenActive(focusRequester, isActive)
    val listState = rememberLazyListState()
    ReportScroll(isActive) { listState.scrollThumb() }
    val paragraphs = remember(text) { text.lines() }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 40.dp, bottom = 56.dp),
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
    ) {
        itemsIndexed(paragraphs) { _, line ->
            BasicText(
                text = line,
                style = WearText.body.copy(color = Color(0xE6FFFFFF), textAlign = TextAlign.Center, lineHeight = 21.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            )
        }
    }
}

@Composable
private fun Message(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(text, style = WearText.subtitle)
        Spacer(Modifier.height(4.dp))
    }
}
