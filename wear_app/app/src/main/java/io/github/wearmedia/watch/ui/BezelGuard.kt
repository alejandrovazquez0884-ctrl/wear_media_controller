package io.github.wearmedia.watch.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Keeps the Galaxy Watch touch bezel (a finger sliding around the edge) from also dragging
 * the pages: a touch that starts near the edge and moves around the screen, rather than
 * toward its center, is swallowed before the pager sees it.
 */
fun Modifier.ignoreBezelSlides(): Modifier = pointerInput(Unit) {
    val edge = 26.dp.toPx()
    val decideAfter = 4.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val center = Offset(size.width / 2f, size.height / 2f)
        val fromCenter = down.position - center
        val radius = minOf(size.width, size.height) / 2f
        if (fromCenter.getDistance() < radius - edge) return@awaitEachGesture

        // Unit vector pointing out from the center; movement along it is a normal swipe.
        val length = fromCenter.getDistance().coerceAtLeast(1f)
        val outward = Offset(fromCenter.x / length, fromCenter.y / length)
        var moved = Offset.Zero
        var isBezel: Boolean? = null
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (isBezel == null) {
                moved += change.positionChange()
                if (sqrt(moved.x * moved.x + moved.y * moved.y) >= decideAfter) {
                    val radial = abs(moved.x * outward.x + moved.y * outward.y)
                    val tangential = abs(moved.x * outward.y - moved.y * outward.x)
                    isBezel = tangential > radial
                }
            }
            if (isBezel == true) change.consume()
        }
    }
}
