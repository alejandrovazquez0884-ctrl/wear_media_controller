package io.github.wearmedia.watch

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The watch app's own settings, kept on the watch. */
class WearSettings(context: Context) {
    private val prefs = context.getSharedPreferences("wear_settings", Context.MODE_PRIVATE)

    private val _touchScroll = MutableStateFlow(prefs.getBoolean(KEY_TOUCH_SCROLL, false))

    /** Lists also scroll with the finger (not only the bezel); the lyrics never do. */
    val touchScroll: StateFlow<Boolean> = _touchScroll.asStateFlow()

    fun setTouchScroll(enabled: Boolean) {
        _touchScroll.value = enabled
        prefs.edit().putBoolean(KEY_TOUCH_SCROLL, enabled).apply()
    }

    private val _skipHintSeen = MutableStateFlow(prefs.getBoolean(KEY_SKIP_HINT_SEEN, false))

    /** The cover page's "double tap to skip" hint was already shown once. */
    val skipHintSeen: StateFlow<Boolean> = _skipHintSeen.asStateFlow()

    fun markSkipHintSeen() {
        if (_skipHintSeen.value) return
        _skipHintSeen.value = true
        prefs.edit().putBoolean(KEY_SKIP_HINT_SEEN, true).apply()
    }

    private companion object {
        const val KEY_TOUCH_SCROLL = "touch_scroll"
        const val KEY_SKIP_HINT_SEEN = "skip_hint_seen"
    }
}

/** Whether lists scroll by touch, from [WearSettings.touchScroll]. */
val LocalTouchScroll = staticCompositionLocalOf { false }
