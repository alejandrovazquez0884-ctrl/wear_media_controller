package io.github.wearmedia.watch.ui

import android.app.RemoteInput
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.input.RemoteInputIntentHelper
import io.github.wearmedia.watch.LocalTouchScroll
import io.github.wearmedia.watch.Capabilities
import io.github.wearmedia.watch.LibraryItem
import io.github.wearmedia.watch.NowPlaying
import io.github.wearmedia.watch.PhoneLink
import io.github.wearmedia.watch.Protocol
import io.github.wearmedia.watch.R
import io.github.wearmedia.watch.WearSettings
import kotlinx.coroutines.flow.distinctUntilChanged

private const val SEARCH_KEY = "query"


/** Opens a library folder from elsewhere (e.g. "go to album"); [nonce] makes repeats count. */
data class LibraryOpenRequest(
    val id: String,
    val title: String,
    val kind: String,
    val nonce: Long = System.nanoTime(),
)

private sealed interface Level {
    data object Home : Level
    data object Library : Level
    data object Settings : Level
    data class Folder(val id: String, val title: String, val kind: String) : Level
    data class Search(val query: String) : Level
}

/** Home, library and search, like Spotify's. Opens by swiping up from the player. */
@Composable
fun LibraryScreen(
    link: PhoneLink,
    settings: WearSettings,
    nowPlaying: NowPlaying,
    isActive: Boolean,
    onPlayed: () -> Unit,
    openRequest: LibraryOpenRequest? = null,
) {
    var stack by remember { mutableStateOf(listOf<Level>(Level.Home)) }
    LaunchedEffect(openRequest) {
        if (openRequest != null) stack = listOf(Level.Home, Level.Folder(openRequest.id, openRequest.title, openRequest.kind))
    }
    fun push(level: Level) {
        stack = stack + level
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = isActive && stack.size > 1) { pop() }

    val searchLabel = stringResource(R.string.search_prompt)
    val searchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@rememberLauncherForActivityResult
        val query = RemoteInput.getResultsFromIntent(data)?.getCharSequence(SEARCH_KEY)?.toString()?.trim()
        if (!query.isNullOrEmpty()) {
            link.search(query)
            push(Level.Search(query))
        }
    }
    val openSearch = {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(SEARCH_KEY).setLabel(searchLabel).build()),
        )
        searchLauncher.launch(intent)
    }

    val onItem: (LibraryItem) -> Unit = { item ->
        when {
            item.browsable -> push(Level.Folder(item.id, item.title, item.kind))
            item.playable -> {
                link.playLibraryItem(item.id)
                onPlayed()
            }
        }
    }

    // Each level gets its own list (and scroll position) and takes the bezel when it's on top.
    key(stack.size, stack.last()) {
        when (val level = stack.last()) {
            Level.Home -> HomeLevel(
                link,
                nowPlaying.capabilities,
                isActive,
                openSearch,
                onOpenLibrary = { push(Level.Library) },
                onOpenSettings = { push(Level.Settings) },
                onItem,
            )
            Level.Settings -> SettingsLevel(settings, isActive, onBack = ::pop)
            Level.Library -> LibraryLevel(link, isActive, onBack = ::pop, onOpen = onItem)
            is Level.Folder -> if (hasDetailPage(level.kind)) {
                DetailScreen(link, nowPlaying, level.id, level.title, level.kind, isActive)
            } else {
                FolderLevel(link, level, isActive, onBack = ::pop, onItem = onItem, onPlayed = onPlayed)
            }
            is Level.Search -> SearchLevel(link, level, isActive, onBack = ::pop, onSearchAgain = openSearch, onItem = onItem)
        }
    }
}

/** Wear-style list: rows shrink and fade toward the round edges; the bezel scrolls it. */
@Composable
private fun LevelList(
    isActive: Boolean,
    onVisible: ((List<Int>) -> Unit)? = null,
    content: ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focusRequester = remember { FocusRequester() }
    RequestFocusWhenActive(focusRequester, isActive)
    ReportScroll(isActive) { listState.scrollThumb() }
    val currentOnVisible by rememberUpdatedState(onVisible)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { currentOnVisible?.invoke(it) }
    }
    ScalingLazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        autoCentering = null,
        // Touch swipes go to the pager (down to the player); the bezel browses.
        userScrollEnabled = LocalTouchScroll.current,
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(RotaryScrollableDefaults.behavior(listState), focusRequester),
        content = content,
    )
}

@Composable
private fun HomeLevel(
    link: PhoneLink,
    capabilities: Capabilities,
    isActive: Boolean,
    onSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onItem: (LibraryItem) -> Unit,
) {
    val home by link.home.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { link.requestHome() }
    val sections = home

    LevelList(isActive) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Home, size = 26.dp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (capabilities.search) PillButton(Icons.Rounded.Search, onClick = onSearch)
                    PillButton(Icons.Outlined.LibraryMusic, onClick = onOpenLibrary)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        when {
            sections == null -> item { LoadingRow() }
            sections.all { it.items.isEmpty() } -> item { EmptyRow(stringResource(R.string.nothing_recent)) }
            else -> sections.forEachIndexed { sectionIndex, section ->
                if (section.items.isEmpty()) return@forEachIndexed
                section.title?.let { title -> item(key = "header|$sectionIndex") { SectionHeader(title) } }
                // The same album or artist can show up in more than one section, so key by position too.
                section.items.forEachIndexed { index, item ->
                    item(key = "$sectionIndex|$index|${item.id}") {
                        MediaRow(link, item, showSubtitle = false) { onItem(item) }
                    }
                }
            }
        }
        item {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                OutlinedPill(stringResource(R.string.settings), onClick = onOpenSettings)
            }
        }
    }
}

/** Spotify's bottom-of-home "Configuración" button: an outlined pill. */
@Composable
private fun OutlinedPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, Color(0x80FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = WearText.itemTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(WearColors.card)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = WearText.itemTitle.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            BasicText(summary, style = WearText.itemSubtitle.copy(fontSize = 12.sp, lineHeight = 15.sp))
        }
        Spacer(Modifier.width(10.dp))
        Toggle(checked)
    }
}

/** Small on/off switch drawn in the app's accent. */
@Composable
private fun Toggle(checked: Boolean) {
    val offset by animateDpAsState(if (checked) 16.dp else 0.dp, label = "toggle")
    Box(
        Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) WearColors.accent else Color(0xFF5A5A5E)),
    ) {
        Box(
            Modifier
                .padding(start = 3.dp + offset, top = 3.dp)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** App settings kept on the watch. */
@Composable
private fun SettingsLevel(settings: WearSettings, isActive: Boolean, onBack: () -> Unit) {
    val touchScroll by settings.touchScroll.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    LevelList(isActive) {
        item { TitleHeader(stringResource(R.string.settings), onClick = onBack) }
        item {
            SwitchRow(
                title = stringResource(R.string.setting_touch_scroll),
                summary = stringResource(R.string.setting_touch_scroll_summary),
                checked = touchScroll,
                onCheckedChange = settings::setTouchScroll,
            )
        }
        item {
            BasicText(
                stringResource(R.string.app_version, version),
                style = WearText.itemSubtitle.copy(textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    BasicText(
        title,
        style = WearText.header.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp, start = 20.dp, end = 20.dp),
    )
}

@Composable
private fun LibraryLevel(link: PhoneLink, isActive: Boolean, onBack: () -> Unit, onOpen: (LibraryItem) -> Unit) {
    val library by link.library.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { link.requestLibrary(Protocol.Library.ROOT, refresh = true) }
    val root = library[Protocol.Library.ROOT]

    LevelList(isActive) {
        item { TitleHeader(stringResource(R.string.your_library), onClick = onBack) }
        if (root?.isLoaded != true) {
            item { LoadingRow() }
        } else {
            root.items.values.forEach { item ->
                item(key = item.id) { CategoryRow(itemIcon(item), item.title) { onOpen(item) } }
            }
        }
    }
}

/** Icon for a library entry without artwork: by its kind, or for plain folders a guess from its name. */
private fun itemIcon(item: LibraryItem): ImageVector {
    val hint = (item.id + " " + item.title).lowercase()
    return when (item.kind) {
        Protocol.Kind.SONG -> Icons.Outlined.MusicNote
        Protocol.Kind.ALBUM -> Icons.Outlined.Album
        Protocol.Kind.ARTIST -> Icons.Outlined.Person
        Protocol.Kind.PLAYLIST -> Icons.AutoMirrored.Outlined.QueueMusic
        Protocol.Kind.GENRE -> Icons.Outlined.Category
        else -> when {
            "fav" in hint || "like" in hint || "gusta" in hint -> Icons.Outlined.FavoriteBorder
            "album" in hint || "álbum" in hint -> Icons.Outlined.Album
            "artist" in hint -> Icons.Outlined.Person
            "playlist" in hint || "lista" in hint -> Icons.AutoMirrored.Outlined.QueueMusic
            "genre" in hint || "género" in hint -> Icons.Outlined.Category
            "recent" in hint || "history" in hint || "historial" in hint -> Icons.Outlined.History
            "top" in hint || "most" in hint -> Icons.AutoMirrored.Outlined.TrendingUp
            "new" in hint || "added" in hint || "añadid" in hint -> Icons.Outlined.NewReleases
            "song" in hint || "track" in hint || "canci" in hint -> Icons.Outlined.MusicNote
            else -> Icons.Outlined.Folder
        }
    }
}

/** Albums, artists, playlists and genres get the Spotify-style page; plain folders stay lists. */
private fun hasDetailPage(kind: String) = kind == Protocol.Kind.ALBUM || kind == Protocol.Kind.ARTIST ||
    kind == Protocol.Kind.PLAYLIST || kind == Protocol.Kind.GENRE

@Composable
private fun FolderLevel(
    link: PhoneLink,
    level: Level.Folder,
    isActive: Boolean,
    onBack: () -> Unit,
    onItem: (LibraryItem) -> Unit,
    onPlayed: () -> Unit,
) {
    val library by link.library.collectAsStateWithLifecycle()
    LaunchedEffect(level.id) { link.requestLibrary(level.id, refresh = true) }
    val listing = library[level.id]
    val total = listing?.total ?: -1
    val hasPlayable = listing?.items?.values?.any { it.playable } == true
    val isArtistList = listing?.items?.values?.firstOrNull()?.isArtist == true

    LevelList(
        isActive,
        onVisible = { visible ->
            visible.forEach { listIndex ->
                val i = listIndex - 1
                if (i in 0 until total && listing?.items?.containsKey(i) != true) link.requestLibrary(level.id, i)
            }
        },
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconHeader(
                    itemIcon(LibraryItem(level.id, level.title, "", "", level.kind)),
                    level.title,
                    onClick = onBack,
                )
                if (hasPlayable) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(Icons.Rounded.PlayArrow, onClick = {
                            link.playAll(level.id, shuffle = false)
                            onPlayed()
                        })
                        PillButton(Icons.Rounded.Shuffle, onClick = {
                            link.playAll(level.id, shuffle = true)
                            onPlayed()
                        })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        when {
            total < 0 -> item { LoadingRow() }
            total == 0 -> item { EmptyRow() }
            else -> items(count = total, key = { it }) { i ->
                val item = listing?.items?.get(i)
                if (item == null) {
                    PlaceholderRow()
                } else {
                    MediaRow(link, item, showSubtitle = !isArtistList) { onItem(item) }
                }
            }
        }
    }
}

@Composable
private fun SearchLevel(
    link: PhoneLink,
    level: Level.Search,
    isActive: Boolean,
    onBack: () -> Unit,
    onSearchAgain: () -> Unit,
    onItem: (LibraryItem) -> Unit,
) {
    val search by link.search.collectAsStateWithLifecycle()
    val result = search?.takeIf { it.query == level.query }
    LaunchedEffect(level.query) { if (result == null) link.search(level.query) }

    LevelList(isActive) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                IconHeader(Icons.Rounded.Search, "“${level.query}”", onClick = onBack)
                Spacer(Modifier.height(6.dp))
                PillButton(Icons.Rounded.Search, onClick = onSearchAgain)
                Spacer(Modifier.height(6.dp))
            }
        }
        val items = result?.items
        when {
            items == null -> item { LoadingRow() }
            items.isEmpty() -> item { EmptyRow(stringResource(R.string.no_results)) }
            else -> items.forEach { item ->
                item(key = item.id) { MediaRow(link, item, showSubtitle = true) { onItem(item) } }
            }
        }
    }
}

/** Category icon over its name; tapping it goes back. */
@Composable
private fun IconHeader(icon: ImageVector, title: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, size = 24.dp)
        Spacer(Modifier.height(6.dp))
        BasicText(
            title,
            style = WearText.header.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TitleHeader(title: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentAlignment = Alignment.Center) {
        BasicText(
            title,
            style = WearText.header.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = WearColors.textSecondary),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PillButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(WearColors.card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, size = 26.dp, tint = WearColors.textSecondary)
    }
}

/** "Tu biblioteca" rows: a plain outline icon and a big label. */
@Composable
private fun CategoryRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, size = 30.dp)
        Spacer(Modifier.width(22.dp))
        BasicText(
            title,
            style = WearText.itemTitle.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MediaRow(link: PhoneLink, item: LibraryItem, showSubtitle: Boolean, onClick: () -> Unit) {
    val round = item.isArtist
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            link = link,
            uri = item.artUri,
            fallback = itemIcon(item),
            size = 58.dp,
            shape = if (round) CircleShape else RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                item.title,
                style = WearText.itemTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (showSubtitle && item.subtitle.isNotEmpty()) {
                BasicText(item.subtitle, style = WearText.itemSubtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Artwork(link: PhoneLink, uri: String, fallback: ImageVector, size: Dp, shape: Shape) {
    if (uri.isNotEmpty()) {
        Thumbnail(link, uri, fallback, size = size, shape = shape)
    } else {
        Box(
            Modifier
                .size(size)
                .clip(shape)
                .background(WearColors.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fallback, size = size * 0.5f, tint = WearColors.textTertiary)
        }
    }
}

@Composable
private fun PlaceholderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(10.dp)).background(WearColors.card))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(width = 96.dp, height = 14.dp).clip(RoundedCornerShape(7.dp)).background(WearColors.card))
    }
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        PlayingIndicator(Modifier.size(16.dp, 18.dp))
    }
}

@Composable
private fun EmptyRow(text: String = stringResource(R.string.empty_folder)) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        BasicText(text, style = WearText.subtitle.copy(textAlign = TextAlign.Center))
    }
}
