package io.github.wearmedia.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import io.github.wearmedia.watch.LocalTouchScroll
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.Protocol
import io.github.wearmedia.watch.R
import kotlinx.coroutines.delay

/**
 * Actions for the playing song, opened by tapping the player outside its buttons (like Spotify).
 * [onGoTo] opens a library folder (album or artist) by its id, title and kind.
 */
@Composable
fun SongMenu(
    nowPlaying: NowPlaying,
    artwork: ImageBitmap?,
    link: PhoneLink,
    onGoTo: (id: String, title: String, kind: String) -> Unit,
    onPlayed: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingPlaylist by remember { mutableStateOf(false) }
    var addedTo by remember { mutableStateOf<String?>(null) }
    BackHandler { if (pickingPlaylist) pickingPlaylist = false else onDismiss() }

    // Confirm for a moment, then close.
    LaunchedEffect(addedTo) {
        if (addedTo != null) {
            delay(1_400)
            onDismiss()
        }
    }

    val closeDistance = with(LocalDensity.current) { 60.dp.toPx() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // Swiping down closes the menu; the list scrolls with the bezel.
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onVerticalDrag = { _, amount ->
                        dragged += amount
                        if (dragged > closeDistance) {
                            dragged = Float.NEGATIVE_INFINITY
                            onDismiss()
                        }
                    },
                )
            },
    ) {
        // Blurred, darkened cover behind the menu.
        if (artwork != null) {
            Image(
                artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(40.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)))

        val added = addedTo
        when {
            added != null -> Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, size = 26.dp, tint = WearColors.accent)
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        stringResource(R.string.added_to, added),
                        style = WearText.itemTitle.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            pickingPlaylist -> PlaylistPicker(link) { id, name ->
                link.addToPlaylist(id)
                addedTo = name
            }

            else -> MenuList {
                item {
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
                        MenuCover(artwork)
                    }
                }
                val capabilities = nowPlaying.capabilities
                if (capabilities.playlists) {
                    item {
                        MenuRow(Icons.Outlined.AddCircleOutline, stringResource(R.string.add_to_playlist)) { pickingPlaylist = true }
                    }
                }
                if (capabilities.library && nowPlaying.albumId.isNotEmpty()) {
                    item {
                        MenuRow(Icons.Outlined.Album, stringResource(R.string.go_to_album)) {
                            onGoTo(nowPlaying.albumId, nowPlaying.albumName, Protocol.Kind.ALBUM)
                        }
                    }
                }
                if (capabilities.library && nowPlaying.artistId.isNotEmpty()) {
                    item {
                        MenuRow(Icons.Outlined.Person, stringResource(R.string.go_to_artist)) {
                            onGoTo(nowPlaying.artistId, nowPlaying.artistName, Protocol.Kind.ARTIST)
                        }
                    }
                    item {
                        MenuRow(Icons.Outlined.Sensors, stringResource(R.string.artist_radio)) {
                            link.playAll(nowPlaying.artistId, shuffle = true)
                            onPlayed()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuList(content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    RequestFocusWhenActive(focusRequester, true)
    ScalingLazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        autoCentering = null,
        userScrollEnabled = LocalTouchScroll.current,
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
        content = content,
    )
}

@Composable
private fun MenuCover(artwork: ImageBitmap?) {
    Box(
        Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(WearColors.card),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            Image(artwork, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.MusicNote, size = 28.dp, tint = WearColors.textTertiary)
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, size = 28.dp)
        Spacer(Modifier.width(14.dp))
        BasicText(
            label,
            style = WearText.itemTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaylistPicker(link: PhoneLink, onPick: (id: String, name: String) -> Unit) {
    val playlists by link.playlists.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { link.requestPlaylists() }

    MenuList {
        item {
            BasicText(
                stringResource(R.string.add_to_playlist),
                style = WearText.header.copy(fontSize = 17.sp, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        val items = playlists.orEmpty()
        when {
            playlists == null -> item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    PlayingIndicator(Modifier.size(16.dp, 18.dp))
                }
            }

            items.isEmpty() -> item {
                BasicText(
                    stringResource(R.string.no_playlists),
                    style = WearText.subtitle.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            else -> items.forEach { playlist ->
                val id = playlist.id
                item(key = playlist.id) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onPick(id, playlist.title) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Thumbnail(link, playlist.artUri, Icons.AutoMirrored.Outlined.QueueMusic, size = 46.dp)
                        Spacer(Modifier.width(12.dp))
                        BasicText(
                            playlist.title,
                            style = WearText.itemTitle.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
