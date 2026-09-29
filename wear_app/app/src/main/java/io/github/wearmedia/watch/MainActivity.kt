package io.github.wearmedia.watch

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wearmedia.watch.ui.WearApp

/**
 * The link lives as long as the process, not the screen: leaving the app and coming back shows
 * what's playing right away (with its artwork and lyrics) instead of asking the phone again.
 */
class LinkViewModel(app: Application) : AndroidViewModel(app) {
    val link = sharedLink(app)
    val settings = WearSettings(app)

    private companion object {
        private var link: PhoneLink? = null

        fun sharedLink(app: Application): PhoneLink = link ?: PhoneLink(app).also { link = it }
    }
}

class MainActivity : ComponentActivity() {
    private val link get() = viewModel.link
    private val viewModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val touchScroll by viewModel.settings.touchScroll.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalTouchScroll provides touchScroll) {
                WearApp(link, viewModel.settings)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        link.start()
    }

    override fun onStop() {
        link.stop()
        super.onStop()
    }
}
