package io.github.wearmedia.watch.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Visible part of a list: where it starts and how much of it shows, both 0..1. */
data class ScrollThumb(val start: Float, val size: Float)

/** Lets the page on screen tell the edge indicator how far its list is scrolled. */
class ScrollReporter {
    var thumb by mutableStateOf<(() -> ScrollThumb?)?>(null)
}

val LocalScrollReporter = staticCompositionLocalOf { ScrollReporter() }

/** Reports [thumb] while [isActive] (the page is the one on screen). */
@Composable
fun ReportScroll(isActive: Boolean, thumb: () -> ScrollThumb?) {
    val reporter = LocalScrollReporter.current
    val current by rememberUpdatedState(thumb)
    DisposableEffect(isActive, reporter) {
        val source = { current() }
        if (isActive) reporter.thumb = source
        onDispose { if (reporter.thumb === source) reporter.thumb = null }
    }
}

/**
 * Continuous position from pixels: the list's height is estimated from the average size of the
 * items on screen, so the thumb glides instead of jumping item by item.
 * [viewportTop] is where the visible area starts, in the same coordinates as [itemTop].
 */
private fun thumbOf(
    total: Int,
    firstIndex: Int,
    itemTop: Float,
    averageItem: Float,
    viewportTop: Float,
    viewportHeight: Float,
): ScrollThumb? {
    if (total <= 0 || averageItem <= 0f || viewportHeight <= 0f) return null
    val contentHeight = averageItem * total
    if (contentHeight <= viewportHeight) return null
    val scrolled = firstIndex * averageItem + (viewportTop - itemTop)
    val size = (viewportHeight / contentHeight).coerceIn(0f, 1f)
    val start = (scrolled / contentHeight).coerceIn(0f, 1f - size)
    return ScrollThumb(start, size)
}

fun LazyListState.scrollThumb(): ScrollThumb? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    val first = visible.first()
    return thumbOf(
        total = info.totalItemsCount,
        firstIndex = first.index,
        itemTop = first.offset.toFloat(),
        averageItem = visible.sumOf { it.size }.toFloat() / visible.size,
        viewportTop = info.viewportStartOffset.toFloat(),
        viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
    )
}

fun ScalingLazyListState.scrollThumb(): ScrollThumb? {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return null
    val first = visible.minBy { it.index }
    val height = info.viewportSize.height.toFloat()
    // Item offsets here are measured from the middle of the screen.
    return thumbOf(
        total = info.totalItemsCount,
        firstIndex = first.index,
        itemTop = first.offset.toFloat() - first.size / 2f,
        averageItem = visible.sumOf { it.size }.toFloat() / visible.size,
        viewportTop = -height / 2f,
        viewportHeight = height,
    )
}

private const val DOT_STEP_DEGREES = 5f
private const val TRACK_SWEEP_DEGREES = 42f

/**
 * Page dots along the right edge, like Spotify's: the current page's dot stretches into a curved
 * track showing where its list is scrolled, when it has a list to scroll.
 */
@Composable
fun PagerScrollIndicator(pageCount: Int, currentPage: Int, reporter: ScrollReporter, modifier: Modifier = Modifier) {
    val target = reporter.thumb?.invoke()
    // Glide between positions, and grow/shrink the track when switching pages.
    val start by animateFloatAsState(target?.start ?: 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "thumbStart")
    val length by animateFloatAsState(target?.size ?: 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "thumbSize")
    val growth by animateFloatAsState(if (target != null) 1f else 0f, label = "trackGrowth")
    Canvas(modifier.fillMaxSize()) {
        val thumb = if (growth > 0.01f) ScrollThumb(start, length) else null
        val radius = size.minDimension / 2 - 7.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        val dotSmall = 2.5.dp.toPx()
        val dotBig = 3.5.dp.toPx()
        val trackStroke = 4.dp.toPx()
        val grey = Color(0x66FFFFFF)

        fun point(degrees: Float): Offset {
            val rad = degrees * PI / 180
            return Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
        }

        // Total span, centered on 3 o'clock (0°); angles grow downward on screen.
        val slots = (0 until pageCount).map { page ->
            if (page == currentPage && thumb != null) TRACK_SWEEP_DEGREES * growth else 0f
        }
        val span = slots.sum() + DOT_STEP_DEGREES * (pageCount - 1)
        var angle = -span / 2
        val arcSize = Size(radius * 2, radius * 2)
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        for (page in 0 until pageCount) {
            val sweep = slots[page]
            if (sweep > 0f && thumb != null) {
                drawArc(grey, angle, sweep, false, arcTopLeft, arcSize, style = Stroke(trackStroke, cap = StrokeCap.Round))
                val thumbSweep = (sweep * thumb.size).coerceAtLeast(6f)
                val thumbStart = angle + (sweep - thumbSweep) * (thumb.start / (1f - thumb.size).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                drawArc(Color.White, thumbStart, thumbSweep, false, arcTopLeft, arcSize, style = Stroke(trackStroke, cap = StrokeCap.Round))
            } else {
                val selected = page == currentPage
                drawCircle(if (selected) Color.White else grey, if (selected) dotBig else dotSmall, point(angle))
            }
            angle += sweep + DOT_STEP_DEGREES
        }
    }
}
