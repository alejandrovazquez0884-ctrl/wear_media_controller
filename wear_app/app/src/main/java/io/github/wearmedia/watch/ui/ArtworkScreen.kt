package io.github.wearmedia.watch.ui

import androidx.compose.animation.Crossfade
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.WearSettings
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.R

private val textShadow = Shadow(color = Color(0x99000000), blurRadius = 8f)

/** Just the artwork, full screen, like Spotify's cover page. */
@Composable
fun ArtworkScreen(
    nowPlaying: NowPlaying,
    artwork: ImageBitmap?,
    artworkColor: Color?,
    link: PhoneLink,
    settings: WearSettings,
    isActive: Boolean,
) {
    val artistColor by animateColorAsState(artworkColor ?: WearColors.textSecondary, label = "artistColor")
    // Icon flashed after a tap (play/pause) or a double tap (next), like Spotify's cover page.
    var flash by remember { mutableStateOf<ImageVector?>(null) }
    var flashAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(flashAt) {
        if (flashAt == 0L) return@LaunchedEffect
        delay(FLASH_MS)
        flash = null
    }
    val paused = !nowPlaying.isPlaying
    // The hint shows only once ever: the first time this page is on screen while paused. It's
    // saved as seen right away, so leaving the page early doesn't bring it back.
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(paused, isActive) {
        if (paused && isActive && !settings.skipHintSeen.value) {
            settings.markSkipHintSeen()
            showHint = true
            delay(HINT_MS)
        }
        showHint = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        val willPlay = !link.nowPlaying.value?.isPlaying.orFalse()
                        link.togglePlay()
                        flash = if (willPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause
                        flashAt = SystemClock.elapsedRealtime()
                    },
                    // Right half skips ahead; left half goes back (restart first, like the previous button).
                    onDoubleTap = { offset ->
                        showHint = false
                        if (offset.x >= size.width / 2f) {
                            link.next()
                            flash = Icons.Rounded.SkipNext
                        } else {
                            link.previous()
                            flash = Icons.Rounded.SkipPrevious
                        }
                        flashAt = SystemClock.elapsedRealtime()
                    },
                )
            },
    ) {
        // The artwork itself is the fixed layer behind the pager (see FixedArtwork).
        if (artwork == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, size = 72.dp, tint = WearColors.textTertiary)
            }
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            Clock(style = WearText.bigClock.copy(color = artistColor), digitsOnly = true)
            AnimatedVisibility(visible = showHint, enter = fadeIn(), exit = fadeOut()) {
                BasicText(
                    stringResource(R.string.double_tap_to_skip),
                    style = WearText.subtitle.copy(color = Color.White, fontWeight = FontWeight.SemiBold, shadow = textShadow),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            BasicText(
                nowPlaying.title,
                style = WearText.title.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, shadow = textShadow),
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 34.dp)
                    .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1500),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            ) {
                Image(
                    painterResource(R.drawable.ic_app_logo),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                BasicText(
                    nowPlaying.artist,
                    style = WearText.subtitle.copy(color = artistColor, shadow = textShadow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(34.dp))
        }

        // Big white button: stays while paused, flashes briefly after a tap otherwise.
        val icon = flash ?: if (paused) Icons.Rounded.PlayArrow else null
        AnimatedVisibility(
            visible = icon != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            var shown by remember { mutableStateOf(icon) }
            if (icon != null) shown = icon
            Box(
                Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                shown?.let { Icon(it, size = 44.dp, tint = Color(0xFF1C1C1E)) }
            }
        }
    }
}

private const val FLASH_MS = 700L
private const val HINT_MS = 4_000L

private fun Boolean?.orFalse() = this == true
