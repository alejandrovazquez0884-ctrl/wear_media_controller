package io.github.wearmedia.watch.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import io.github.wearmedia.watch.LocalTouchScroll
import io.github.wearmedia.watch.LibraryItem
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.Protocol
import io.github.wearmedia.watch.R
import kotlinx.coroutines.flow.distinctUntilChanged

/** Rows above the first song: cover, title row, shuffle button, section header. */
private const val DETAIL_HEADER_ITEMS = 4

/** Whether a song item is the playing track: same id, or the id ends with ":<mediaId>". */
private fun isPlayingItem(id: String, mediaId: String) = id == mediaId || id.substringAfterLast(':') == mediaId

/**
 * An album, artist, playlist or genre, laid out like Spotify's pages: cover on a gradient of its
 * color, a big play button, "shuffle" and the songs, with the playing one highlighted.
 * [onSwipeClose] makes a downward swipe close it (when it's shown over the player).
 */
@Composable
fun DetailScreen(
    link: PhoneLink,
    nowPlaying: NowPlaying,
    folderId: String,
    title: String,
    kind: String,
    isActive: Boolean,
    onSwipeClose: (() -> Unit)? = null,
) {
    val library by link.library.collectAsStateWithLifecycle()
    LaunchedEffect(folderId) { link.requestLibrary(folderId, refresh = true) }
    val listing = library[folderId]
    val total = listing?.total ?: -1
    val songs = listing?.items.orEmpty()
    val isArtist = kind == Protocol.Kind.ARTIST
    val art = listing?.art.orEmpty()

    // Colors from the picture: a dark one for the backdrop, a vivid one for the play button.
    LaunchedEffect(art) { link.requestThumbnail(art) }
    val picture = link.thumbnails[art]
    val palette = remember(picture) { picture?.let { Palette.from(it.asAndroidBitmap()).generate() } }
    val backdrop = palette?.let { (it.darkVibrantSwatch ?: it.darkMutedSwatch ?: it.dominantSwatch)?.rgb }
        ?.let { Color(it) } ?: Color(0xFF3A3A3A)
    val buttonColor = palette?.let { (it.vibrantSwatch ?: it.lightVibrantSwatch ?: it.lightMutedSwatch)?.rgb }
        ?.let { Color(it) } ?: WearColors.accent

    val playingHere = songs.values.any { it.playable && isPlayingItem(it.id, nowPlaying.mediaId) }
    val totalMinutes = (songs.values.sumOf { it.durationMs } / 60_000).toInt()

    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    RequestFocusWhenActive(focusRequester, isActive)
    ReportScroll(isActive) { listState.scrollThumb() }
    val currentListing by rememberUpdatedState(listing)
    LaunchedEffect(listState, folderId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { visible ->
                val loaded = currentListing ?: return@collect
                visible.forEach { listIndex ->
                    val i = listIndex - DETAIL_HEADER_ITEMS
                    if (i in 0 until loaded.total && !loaded.items.containsKey(i)) link.requestLibrary(folderId, i)
                }
            }
    }

    val closeDistance = with(LocalDensity.current) { 60.dp.toPx() }
    val swipeModifier = if (onSwipeClose != null) {
        Modifier.pointerInput(onSwipeClose) {
            var dragged = 0f
            detectVerticalDragGestures(
                onDragStart = { dragged = 0f },
                onVerticalDrag = { _, amount ->
                    dragged += amount
                    if (dragged > closeDistance) {
                        dragged = Float.NEGATIVE_INFINITY
                        onSwipeClose()
                    }
                },
            )
        }
    } else Modifier

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to backdrop, 0.5f to Color.Black, 1f to Color.Black))
            .then(swipeModifier),
    ) {
        ScalingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            autoCentering = null,
            userScrollEnabled = LocalTouchScroll.current,
            modifier = Modifier
                .fillMaxSize()
                .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Thumbnail(
                        link,
                        art,
                        fallback = if (isArtist) Icons.Outlined.Person else Icons.Outlined.Album,
                        size = 66.dp,
                        shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val showPause = playingHere && nowPlaying.isPlaying
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(buttonColor)
                            .clickable {
                                if (playingHere) link.togglePlay() else link.playAll(folderId, shuffle = false)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (showPause) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, size = 32.dp, tint = Color.Black)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(
                            title,
                            style = WearText.itemTitle.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (total > 0) {
                            BasicText(
                                if (isArtist) {
                                    stringResource(R.string.song_count, total)
                                } else {
                                    stringResource(R.string.song_count_minutes, total, totalMinutes.coerceAtLeast(1))
                                },
                                style = WearText.itemSubtitle,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable { link.playAll(folderId, shuffle = true) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Shuffle, size = 22.dp, tint = WearColors.textSecondary)
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        stringResource(R.string.shuffle_mode),
                        style = WearText.itemTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WearColors.textSecondary),
                    )
                }
            }
            item {
                BasicText(
                    stringResource(R.string.songs),
                    style = WearText.header.copy(fontSize = 17.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                )
            }
            when {
                total < 0 -> item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        PlayingIndicator(Modifier.size(16.dp, 18.dp))
                    }
                }

                else -> items(count = total, key = { it }) { i ->
                    TrackRow(
                        item = songs[i],
                        isCurrent = songs[i]?.let { isPlayingItem(it.id, nowPlaying.mediaId) } == true,
                        highlight = buttonColor,
                    ) { songs[i]?.let { link.playLibraryItem(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(item: LibraryItem?, isCurrent: Boolean, highlight: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = item != null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicText(
            item?.title ?: "…",
            style = WearText.itemTitle.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (isCurrent) highlight else Color.White,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val artist = item?.artist
        if (!artist.isNullOrEmpty()) {
            BasicText(artist, style = WearText.itemSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
