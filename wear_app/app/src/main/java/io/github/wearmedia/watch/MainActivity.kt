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

/** Keeps the link (and its cached state, like lyrics) alive when the activity is recreated. */
class LinkViewModel(app: Application) : AndroidViewModel(app) {
    val link = PhoneLink(app)
    val settings = WearSettings(app)

    override fun onCleared() {
        link.close()
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
