package io.github.wearmedia.watch.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import io.github.wearmedia.watch.PhoneLink
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import io.github.wearmedia.watch.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import java.util.Date

object WearColors {
    val accent = Color(0xFF4D8DF7)
    val textPrimary = Color.White
    val textSecondary = Color(0xFFB9BCC6)
    val textTertiary = Color(0xFF8E929C)
    val card = Color(0xFF262628)
    val divider = Color(0x1FFFFFFF)
    val buttonScrim = Color(0x59000000)
    val ringTrack = Color(0x33FFFFFF)
}

/** Figtree (SIL OFL, see wear/FIGTREE_OFL.txt): a free face close to Spotify's own. One variable file, every weight. */
@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: FontWeight) = Font(
    resId = R.font.figtree,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Figtree = FontFamily(
    figtree(FontWeight.Normal),
    figtree(FontWeight.Medium),
    figtree(FontWeight.SemiBold),
    figtree(FontWeight.Bold),
    figtree(FontWeight.ExtraBold),
    figtree(FontWeight.Black),
)

/** Rubik Black (SIL OFL, see wear/RUBIK_OFL.txt), only for the cover page's big clock. */
@OptIn(ExperimentalTextApi::class)
val RubikBlack = FontFamily(
    Font(
        resId = R.font.rubik,
        weight = FontWeight.Black,
        variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Black.weight)),
    ),
)

object WearText {
    val clock = TextStyle(
        color = WearColors.textPrimary,
        fontFamily = Figtree,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        shadow = Shadow(color = Color(0x99000000), blurRadius = 8f),
    )
    val title = TextStyle(color = WearColors.textPrimary, fontFamily = Figtree, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    val subtitle = TextStyle(color = WearColors.textSecondary, fontFamily = Figtree, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = WearColors.textPrimary, fontFamily = Figtree, fontSize = 15.sp)
    val header = TextStyle(color = WearColors.textPrimary, fontFamily = Figtree, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    val itemTitle = TextStyle(color = WearColors.textPrimary, fontFamily = Figtree, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    /** Big, heavy clock of the cover page, like Spotify's always-on screen. */
    val bigClock = TextStyle(
        color = Color(0xFFD6D6D6),
        fontFamily = RubikBlack,
        fontSize = 34.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (-0.5).sp,
        shadow = Shadow(color = Color(0x80000000), blurRadius = 10f),
    )
    val itemSubtitle = TextStyle(color = WearColors.textTertiary, fontFamily = Figtree, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun Icon(icon: ImageVector, size: Dp, tint: Color = Color.White, modifier: Modifier = Modifier) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    tint: Color = Color.White,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, iconSize, tint)
    }
}

/**
 * Gives [focusRequester] the focus (so the bezel/crown reaches it) whenever its page is shown,
 * including after the screen turns back on. Call it next to the node that uses the requester.
 */
@Composable
fun RequestFocusWhenActive(focusRequester: FocusRequester, isActive: Boolean) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(isActive, resumed) {
        if (isActive && resumed) {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Not attached yet; the next composition retries.
            }
        }
    }
}

/** Current time, refreshed on each minute change. */
@Composable
fun Clock(modifier: Modifier = Modifier, style: TextStyle = WearText.clock, digitsOnly: Boolean = false) {
    val context = LocalContext.current
    val format = remember(digitsOnly) {
        if (digitsOnly) {
            // Just the digits ("8:16"), no a.m./p.m., like a watch face.
            java.text.SimpleDateFormat(if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm", java.util.Locale.getDefault())
        } else {
            DateFormat.getTimeFormat(context)
        }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    BasicText(format.format(Date(now)), style = style, modifier = modifier)
}

/** Animated grid of dots marking the item that is currently playing. */
@Composable
fun PlayingIndicator(modifier: Modifier = Modifier, animate: Boolean = true, color: Color = WearColors.accent) {
    val transition = rememberInfiniteTransition(label = "playing")
    val phases = List(3) { column ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(420 + column * 130), RepeatMode.Reverse),
            label = "column$column",
        )
    }
    Canvas(modifier) {
        val rows = 4
        val columns = 3
        val radius = minOf(size.width / (columns * 2.6f), size.height / (rows * 2.6f))
        val xStep = size.width / columns
        val yStep = size.height / rows
        for (c in 0 until columns) {
            val level = if (animate) phases[c].value else 0.5f
            val lit = (level * rows).toInt().coerceIn(1, rows)
            for (r in 0 until lit) {
                val cy = size.height - yStep * (r + 0.5f)
                drawCircle(color, radius, Offset(xStep * (c + 0.5f), cy))
            }
        }
    }
}

/** Small square artwork for list rows; asks the phone for it the first time it's shown. */
@Composable
fun Thumbnail(
    link: PhoneLink,
    uri: String,
    fallback: ImageVector,
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(8.dp),
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(uri) { link.requestThumbnail(uri) }
    val bitmap = link.thumbnails[uri]
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(WearColors.card),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(fallback, size = size * 0.55f, tint = WearColors.textTertiary)
        }
    }
}

/** Vertical dots on the right edge showing which page of the pager is showing. */
@Composable
fun PageIndicator(pageCount: Int, currentPage: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(end = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(pageCount) { page ->
            val selected = page == currentPage
            Box(
                Modifier
                    .size(if (selected) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else Color(0x66FFFFFF)),
            )
        }
    }
}

/** "12 min" style countdown for the sleep timer, refreshed every few seconds. */
@Composable
fun sleepTimerText(endAt: Long): String? {
    if (endAt == 0L) return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }
    if (endAt < 0) return "•"
    val minutes = ((endAt - now).coerceAtLeast(0) + 59_999) / 60_000
    return "$minutes min"
}
