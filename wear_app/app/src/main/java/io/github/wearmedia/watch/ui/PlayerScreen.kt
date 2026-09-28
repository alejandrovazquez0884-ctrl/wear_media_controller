package io.github.wearmedia.watch.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SpeakerPhone
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.R
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val ROTARY_STEP_PX = 40f
private const val VOLUME_OVERLAY_MS = 2200L
private const val SEEK_BEZEL_STEP_MS = 5_000L
private const val SEEK_RING_SCALE = 0.58f
private const val VOLUME_ARC_START = 155f
private const val VOLUME_ARC_SWEEP = 50f

@Composable
fun PlayerScreen(
    nowPlaying: NowPlaying,
    artwork: ImageBitmap?,
    artworkColor: Color?,
    link: PhoneLink,
    isActive: Boolean,
    onOpenQueue: () -> Unit,
    onGoTo: (id: String, title: String, kind: String) -> Unit,
    onPlayed: () -> Unit,
    onMenuShown: (Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val currentNowPlaying by rememberUpdatedState(nowPlaying)
    LaunchedEffect(showMenu) { onMenuShown(showMenu) }
    val artistColor by animateColorAsState(artworkColor ?: WearColors.textSecondary, label = "artistColor")
    // Progress and volume share the artist's color; the app's blue when the song has no cover.
    val ringColor by animateColorAsState(artworkColor ?: WearColors.accent, label = "ringColor")
    val focusRequester = remember { FocusRequester() }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    var showVolume by remember { mutableStateOf(false) }
    var showOutputs by remember { mutableStateOf(false) }
    RequestFocusWhenActive(focusRequester, isActive && !showOutputs && !showMenu)
    var rotaryAccumulated by remember { mutableFloatStateOf(0f) }

    // Long-press the play button and drag around the ring to seek (like Spotify).
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var buttonOrigin by remember { mutableStateOf(Offset.Zero) }
    val durationMs = nowPlaying.durationMs
    val seekDeadZonePx = with(LocalDensity.current) { 40.dp.toPx() }

    fun fractionAt(localInButton: Offset, previous: Float): Float {
        val point = buttonOrigin - screenOrigin + localInButton
        val dx = point.x - screenSize.width / 2f
        val dy = point.y - screenSize.height / 2f
        // Near the middle the angle is meaningless: keep the position until the finger heads out.
        if (dx * dx + dy * dy < seekDeadZonePx * seekDeadZonePx) return previous
        // 0 at 12 o'clock, growing clockwise.
        var fraction = ((atan2(dx, -dy) / (2 * PI)).toFloat() + 1f) % 1f
        // Don't wrap from the end to the start (or back) when crossing 12 o'clock.
        if (previous > 0.75f && fraction < 0.25f) fraction = 1f
        if (previous < 0.25f && fraction > 0.75f) fraction = 0f
        return fraction
    }

    fun pokeVolumeOverlay() {
        volumeShownAt = SystemClock.elapsedRealtime()
        showVolume = true
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            showVolume = false
            showOutputs = false
            showMenu = false
        }
    }
    LaunchedEffect(volumeShownAt) {
        if (volumeShownAt == 0L) return@LaunchedEffect
        delay(VOLUME_OVERLAY_MS)
        showVolume = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Rotating the bezel/crown changes the phone's volume.
            .onGloballyPositioned {
                screenOrigin = it.positionInRoot()
                screenSize = it.size
            }

            .onRotaryScrollEvent { event ->
                rotaryAccumulated += event.verticalScrollPixels
                val seeking = seekFraction
                if (seeking != null && durationMs > 0) {
                    // While seeking, the bezel nudges the position instead of the volume.
                    if (abs(rotaryAccumulated) >= ROTARY_STEP_PX) {
                        val step = SEEK_BEZEL_STEP_MS.toFloat() / durationMs
                        seekFraction = (seeking + if (rotaryAccumulated > 0) step else -step).coerceIn(0f, 1f)
                        rotaryAccumulated = 0f
                    }
                } else if (abs(rotaryAccumulated) >= ROTARY_STEP_PX) {
                    link.changeVolume(up = rotaryAccumulated > 0)
                    rotaryAccumulated = 0f
                    pokeVolumeOverlay()
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        // Tapping the cover (anywhere but the buttons) opens the song's menu. It sits behind the
        // controls, so their taps never reach it.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures(onTap = { if (hasSongMenu(currentNowPlaying)) showMenu = true }) },
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            Clock()
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = nowPlaying.title,
                style = WearText.title,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 26.dp)
                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
            )
            BasicText(
                text = nowPlaying.artist,
                style = WearText.subtitle.copy(color = artistColor),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 40.dp),
            )

            Spacer(Modifier.weight(1f))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircleIconButton(Icons.Rounded.SkipPrevious, size = 52.dp, iconSize = 30.dp, onClick = link::previous, background = WearColors.buttonScrim)
                PlayPauseButton(
                    nowPlaying = nowPlaying,
                    ringColor = ringColor,
                    onClick = link::togglePlay,
                    onPositioned = { buttonOrigin = it },
                    onSeekStart = { local ->
                        if (durationMs > 0) {
                            val current = nowPlaying.currentPositionMs().toFloat() / durationMs
                            seekFraction = current
                        }
                    },
                    onSeekMove = { local -> seekFraction?.let { seekFraction = fractionAt(local, it) } },
                    onSeekEnd = {
                        seekFraction?.let { link.seekTo((it * durationMs).toLong()) }
                        seekFraction = null
                    },
                )
                CircleIconButton(Icons.Rounded.SkipNext, size = 52.dp, iconSize = 30.dp, onClick = link::next, background = WearColors.buttonScrim)
            }

            Spacer(Modifier.weight(1f))

            // Output device and favourite at the sides, the queue lower in the middle (the round screen's widest spot).
            Row(
                verticalAlignment = Alignment.Top,
                // Close enough to the middle that the side buttons clear the round edge.
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircleIconButton(outputIcon(nowPlaying.outputType), size = 46.dp, iconSize = 28.dp, onClick = { showOutputs = true })
                // Unsupported buttons leave their space empty, so the others keep their places.
                if (nowPlaying.capabilities.queue) {
                    CircleIconButton(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        size = 46.dp,
                        iconSize = 30.dp,
                        onClick = onOpenQueue,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                } else {
                    Spacer(Modifier.size(46.dp))
                }
                if (nowPlaying.capabilities.favorite) {
                    CircleIconButton(
                        icon = if (nowPlaying.isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        size = 46.dp,
                        iconSize = 28.dp,
                        onClick = link::toggleFavourite,
                        tint = if (nowPlaying.isFavourite) WearColors.accent else Color.White,
                    )
                } else {
                    Spacer(Modifier.size(46.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        seekFraction?.let { fraction -> SeekOverlay(fraction, durationMs, ringColor) }

        AnimatedVisibility(visible = showMenu, enter = fadeIn(), exit = fadeOut()) {
            SongMenu(
                nowPlaying = nowPlaying,
                artwork = artwork,
                link = link,
                onGoTo = { id, title, kind ->
                    showMenu = false
                    onGoTo(id, title, kind)
                },
                onPlayed = {
                    showMenu = false
                    onPlayed()
                },
                onDismiss = { showMenu = false },
            )
        }

        AnimatedVisibility(visible = showOutputs, enter = fadeIn(), exit = fadeOut()) {
            OutputPicker(link, onDismiss = { showOutputs = false })
        }

        AnimatedVisibility(visible = showVolume, enter = fadeIn(), exit = fadeOut()) {
            VolumeArc(volume = nowPlaying.volume, volumeMax = nowPlaying.volumeMax, color = ringColor)
        }
    }
}

/**
 * The artwork behind every page, fixed while the pages slide over it (like Spotify).
 * [dims] is how dark each page makes it; the darkness follows the swipe between pages.
 */
@Composable
internal fun FixedArtwork(artwork: ImageBitmap?, pagePosition: () -> Float, dims: FloatArray) {
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val position = pagePosition().coerceIn(0f, dims.lastIndex.toFloat())
                val page = position.toInt()
                val fraction = position - page
                val alpha = if (page >= dims.lastIndex) dims.last() else dims[page] + (dims[page + 1] - dims[page]) * fraction
                drawRect(Color.Black.copy(alpha = alpha.coerceIn(0f, 1f)))
            },
    ) {
        Crossfade(targetState = artwork, label = "artwork") { art ->
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Whether the song menu has anything to offer with what the phone app supports. */
private fun hasSongMenu(np: NowPlaying): Boolean {
    val caps = np.capabilities
    return caps.playlists || (caps.library && (np.albumId.isNotEmpty() || np.artistId.isNotEmpty()))
}

/** Big ring with the time in the middle, shown while seeking. */
@Composable
private fun SeekOverlay(fraction: Float, durationMs: Long, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .drawBehind {
                // Same proportions as Spotify's: the ring spans a bit over half the screen.
                val stroke = 5.dp.toPx()
                val diameter = size.minDimension * SEEK_RING_SCALE
                val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
                val topLeft = Offset(center.x - diameter / 2, center.y - diameter / 2)
                drawArc(Color(0xFFBDBDBD), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // Thumb at the end of the progress.
                val radius = arcSize.width / 2
                val angle = (fraction * 2 * PI - PI / 2).toFloat()
                val thumb = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
                drawCircle(Color.White, radius = stroke * 1.1f, center = thumb)
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            formatTime((fraction * durationMs).toLong()),
            style = WearText.header.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Tap toggles playback; a long press starts seeking, and the finger's positions (in this button's
 * coordinates) keep coming until it lifts, wherever it goes on the screen.
 */
@Composable
private fun PlayPauseButton(
    nowPlaying: NowPlaying,
    ringColor: Color,
    onClick: () -> Unit,
    onPositioned: (Offset) -> Unit,
    onSeekStart: (Offset) -> Unit,
    onSeekMove: (Offset) -> Unit,
    onSeekEnd: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnSeekStart by rememberUpdatedState(onSeekStart)
    val currentOnSeekMove by rememberUpdatedState(onSeekMove)
    val currentOnSeekEnd by rememberUpdatedState(onSeekEnd)
    val progress by produceState(0f, nowPlaying) {
        while (true) {
            value = if (nowPlaying.durationMs > 0) nowPlaying.currentPositionMs().toFloat() / nowPlaying.durationMs else 0f
            if (!nowPlaying.isPlaying) break
            delay(500)
        }
    }
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(WearColors.buttonScrim)
            .onGloballyPositioned { onPositioned(it.positionInRoot()) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val longPress = awaitLongPressOrCancellation(down.id)
                    if (longPress == null) {
                        // Lifted before the long press: a tap, if it ended on the button.
                        val up = currentEvent.changes.firstOrNull { it.id == down.id }
                        if (up != null && up.changedToUp() && !up.isConsumed &&
                            up.position.x in 0f..size.width.toFloat() && up.position.y in 0f..size.height.toFloat()
                        ) {
                            up.consume()
                            currentOnClick()
                        }
                        return@awaitEachGesture
                    }
                    currentOnSeekStart(longPress.position)
                    drag(longPress.id) { change ->
                        currentOnSeekMove(change.position)
                        change.consume()
                    }
                    currentEvent.changes.forEach { if (it.changedToUp()) it.consume() }
                    currentOnSeekEnd()
                }
            }
            .drawBehind {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2 + 1.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
                drawArc(WearColors.ringTrack, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (nowPlaying.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, size = 40.dp)
    }
}

/** Slim arc on the left edge that fills from the bottom with the volume, like Spotify's. */
@Composable
private fun VolumeArc(volume: Int, volumeMax: Int, color: Color) {
    val fraction by animateFloatAsState(
        if (volumeMax > 0) volume.toFloat() / volumeMax else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "volume",
    )
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 5.dp.toPx()
        val inset = 10.dp.toPx()
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        // From about 8 o'clock (bottom) up to 10 o'clock.
        drawArc(Color(0x66FFFFFF), VOLUME_ARC_START, VOLUME_ARC_SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (fraction > 0f) {
            drawArc(color, VOLUME_ARC_START, VOLUME_ARC_SWEEP * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}
