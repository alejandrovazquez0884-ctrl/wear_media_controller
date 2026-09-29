package io.github.wearmedia.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.wearmedia.watch.LinkStatus
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.WearSettings
import io.github.wearmedia.watch.R
import kotlinx.coroutines.launch

/**
 * Pages top to bottom: cover, lyrics, player (where the app opens), home/library. Swiping down
 * from the player shows the lyrics, then the cover; swiping up shows the library. Lyrics and
 * library only exist when the phone app supports them. [dim] is how much the page darkens the
 * fixed artwork behind it.
 */
private enum class Page(val dim: Float) { ARTWORK(0f), LYRICS(0.72f), PLAYER(0.5f), LIBRARY(1f) }

@Composable
fun WearApp(link: PhoneLink, settings: WearSettings) {
    val status by link.status.collectAsStateWithLifecycle()
    val nowPlaying by link.nowPlaying.collectAsStateWithLifecycle()
    val artwork by link.artwork.collectAsStateWithLifecycle()
    val artworkColor by link.artworkColor.collectAsStateWithLifecycle()
    val queue by link.queue.collectAsStateWithLifecycle()
    val lyrics by link.lyrics.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val np = nowPlaying
        if (np == null) {
            WaitingScreen(status, onRetry = link::requestState)
            return@Box
        }

        val capabilities = np.capabilities
        val pages = remember(capabilities.lyrics, capabilities.library) {
            buildList {
                add(Page.ARTWORK)
                if (capabilities.lyrics) add(Page.LYRICS)
                add(Page.PLAYER)
                if (capabilities.library) add(Page.LIBRARY)
            }
        }
        // A new set of pages starts over on the player.
        val pager = key(pages) { rememberPagerState(initialPage = pages.indexOf(Page.PLAYER)) { pages.size } }
        val dims = remember(pages) { pages.map { it.dim }.toFloatArray() }
        val currentPage = pages[pager.currentPage.coerceIn(pages.indices)]
        val scope = rememberCoroutineScope()
        val scrollReporter = remember { ScrollReporter() }
        var showQueue by rememberSaveable { mutableStateOf(false) }
        var libraryRequest by remember { mutableStateOf<LibraryOpenRequest?>(null) }
        var songMenuShown by remember { mutableStateOf(false) }
        // Album/artist opened from the song menu, shown over everything; back returns to the player.
        var detail by remember { mutableStateOf<LibraryOpenRequest?>(null) }
        val toPlayer: () -> Unit = {
            showQueue = false
            scope.launch { pager.animateScrollToPage(pages.indexOf(Page.PLAYER)) }
        }
        FixedArtwork(
            artwork = artwork,
            pagePosition = { pager.currentPage + pager.currentPageOffsetFraction },
            dims = dims,
        )
        CompositionLocalProvider(LocalScrollReporter provides scrollReporter) {
            VerticalPager(state = pager, modifier = Modifier.fillMaxSize().ignoreBezelSlides(), beyondViewportPageCount = 1) { page ->
                when (pages[page]) {
                    Page.LIBRARY -> LibraryScreen(
                        link = link,
                        settings = settings,
                        nowPlaying = np,
                        isActive = !showQueue && detail == null && currentPage == Page.LIBRARY,
                        onPlayed = toPlayer,
                        openRequest = libraryRequest,
                    )

                    Page.PLAYER -> PlayerScreen(
                        nowPlaying = np,
                        artwork = artwork,
                        artworkColor = artworkColor,
                        link = link,
                        isActive = !showQueue && detail == null && currentPage == Page.PLAYER,
                        onOpenQueue = { showQueue = true },
                        onGoTo = { id, title, kind -> detail = LibraryOpenRequest(id, title, kind) },
                        onPlayed = toPlayer,
                        onMenuShown = { songMenuShown = it },
                    )

                    Page.LYRICS -> LyricsScreen(
                        nowPlaying = np,
                        lyrics = lyrics,
                        artwork = artwork,
                        link = link,
                        isActive = !showQueue && currentPage == Page.LYRICS,
                    )

                    Page.ARTWORK -> ArtworkScreen(
                        nowPlaying = np,
                        artwork = artwork,
                        artworkColor = artworkColor,
                        link = link,
                        settings = settings,
                        isActive = currentPage == Page.ARTWORK,
                    )
                }
            }
        }
        if (!songMenuShown && detail == null) {
            PagerScrollIndicator(
                pageCount = pages.size,
                currentPage = pager.currentPage,
                reporter = scrollReporter,
            )
        }

        AnimatedVisibility(
            visible = detail != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            // Keeps showing the last page while it slides away.
            var shown by remember { mutableStateOf(detail) }
            if (detail != null) shown = detail
            val open = shown ?: return@AnimatedVisibility
            BackHandler { detail = null }
            DetailScreen(
                link = link,
                nowPlaying = np,
                folderId = open.id,
                title = open.title,
                kind = open.kind,
                isActive = detail != null,
                onSwipeClose = { detail = null },
            )
        }

        // The queue opens from the player's button, over everything, like Spotify's.
        AnimatedVisibility(
            visible = showQueue,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            BackHandler { showQueue = false }
            QueueScreen(
                nowPlaying = np,
                queue = queue,
                link = link,
                isActive = showQueue,
                onPlayed = toPlayer,
                onClose = { showQueue = false },
            )
        }
    }
}

@Composable
private fun WaitingScreen(status: LinkStatus, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onRetry)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.PhoneAndroid, size = 36.dp, tint = WearColors.accent)
        Spacer(Modifier.height(10.dp))
        BasicText(
            text = stringResource(
                when (status) {
                    LinkStatus.CONNECTED -> R.string.nothing_playing
                    LinkStatus.CONNECTING -> R.string.connecting
                    else -> R.string.not_connected
                },
            ),
            style = WearText.body.copy(textAlign = TextAlign.Center),
        )
        if (status == LinkStatus.CONNECTING) {
            Spacer(Modifier.height(12.dp))
            PlayingIndicator(Modifier.size(16.dp, 18.dp))
        }
    }
}
