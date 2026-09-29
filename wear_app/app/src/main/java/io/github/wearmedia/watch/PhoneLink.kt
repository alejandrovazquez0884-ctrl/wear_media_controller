package io.github.wearmedia.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NowPlaying(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val isFavourite: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** elapsedRealtime on the watch when [positionMs] was valid. */
    val positionAt: Long,
    val index: Int,
    val queueLength: Int,
    val queueVersion: Long,
    val volume: Int,
    val volumeMax: Int,
    val mediaId: String,
    val shuffle: Boolean,
    val repeatMode: Int,
    /** Wall-clock time the sleep timer fires; 0 = off, -1 = on but its end is unknown. */
    val sleepEndAt: Long,
    /** Where the audio goes: phone, bluetooth, wired or usb. */
    val outputType: String,
    /** Folder ids for "go to album/artist"; empty when the app doesn't provide them. */
    val albumId: String,
    val albumName: String,
    val artistId: String,
    val artistName: String,
    val capabilities: Capabilities,
) {
    fun currentPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val pos = if (isPlaying) positionMs + (now - positionAt) else positionMs
        return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
    }
}

/** What the phone app supports; the watch hides the rest. */
data class Capabilities(
    val queue: Boolean = true,
    val lyrics: Boolean = false,
    val library: Boolean = false,
    val search: Boolean = false,
    val favorite: Boolean = false,
    val shuffle: Boolean = true,
    val repeat: Boolean = true,
    val sleepTimer: Boolean = false,
    val playlists: Boolean = false,
)

data class QueueItem(val title: String, val artist: String, val artUri: String)

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val artUri: String,
    /** One of [Protocol.Kind]. */
    val kind: String,
    val artist: String = "",
    val durationMs: Long = 0,
) {
    val playable get() = kind == Protocol.Kind.SONG
    val browsable get() = !playable
    val isArtist get() = kind == Protocol.Kind.ARTIST
}

/** A home page section; without a title it's the quick picks at the top. */
data class HomeSection(val title: String?, val items: List<LibraryItem>)

/** One library folder; pages load as they're scrolled to. */
data class LibraryListing(
    val parentId: String,
    val total: Int = -1,
    val items: Map<Int, LibraryItem> = emptyMap(),
    /** The folder's own picture (album cover, artist photo...), if it has one. */
    val art: String = "",
) {
    val isLoaded get() = total >= 0
}

data class SearchResult(val query: String, val items: List<LibraryItem>? = null)

data class AudioOutput(val id: Int, val name: String, val kind: String, val active: Boolean)

data class AudioOutputs(val auto: Boolean, val devices: List<AudioOutput>)

data class QueueState(
    val version: Long = -1,
    val total: Int = 0,
    val current: Int = 0,
    val items: Map<Int, QueueItem> = emptyMap(),
)

data class LyricLine(val startMs: Long, val text: String)

sealed interface Lyrics {
    val mediaId: String

    data class Loading(override val mediaId: String) : Lyrics
    data class Synced(override val mediaId: String, val lines: List<LyricLine>) : Lyrics
    data class Plain(override val mediaId: String, val text: String) : Lyrics
    data class None(override val mediaId: String) : Lyrics
}

/** [APP_CLOSED]: the phone is there, but the music app didn't answer (closed or force-stopped). */
enum class LinkStatus { CONNECTING, CONNECTED, NO_PHONE, APP_CLOSED }

/** Talks to the music app on the phone (through the wear_media_controller plugin) over the Wearable Data Layer. */
class PhoneLink(context: Context) : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _status = MutableStateFlow(LinkStatus.CONNECTING)
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _artwork = MutableStateFlow<ImageBitmap?>(null)
    val artwork: StateFlow<ImageBitmap?> = _artwork.asStateFlow()

    /** Readable color taken from the artwork, for the artist name (like Spotify). */
    private val _artworkColor = MutableStateFlow<Color?>(null)
    val artworkColor: StateFlow<Color?> = _artworkColor.asStateFlow()

    private val _queue = MutableStateFlow(QueueState())
    val queue: StateFlow<QueueState> = _queue.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _library = MutableStateFlow<Map<String, LibraryListing>>(emptyMap())
    val library: StateFlow<Map<String, LibraryListing>> = _library.asStateFlow()

    private val _outputs = MutableStateFlow<AudioOutputs?>(null)
    val outputs: StateFlow<AudioOutputs?> = _outputs.asStateFlow()

    private val _home = MutableStateFlow<List<HomeSection>?>(null)
    val home: StateFlow<List<HomeSection>?> = _home.asStateFlow()

    private val _playlists = MutableStateFlow<List<LibraryItem>?>(null)
    val playlists: StateFlow<List<LibraryItem>?> = _playlists.asStateFlow()

    private val _search = MutableStateFlow<SearchResult?>(null)
    val search: StateFlow<SearchResult?> = _search.asStateFlow()

    /** Small artworks for lists, keyed by uri. Compose state, so rows redraw when theirs arrives. */
    val thumbnails = mutableStateMapOf<String, ImageBitmap?>()
    private val thumbnailOrder = ArrayDeque<String>()
    private val pendingThumbnails = mutableSetOf<String>()

    private var artId: String? = null

    /** When the phone app last sent its state; a request unanswered for a while means it's gone. */
    private var lastStateAt = 0L
    private var answerWatch: Job? = null

    /** Until then, the phone's reported volume is older than the watch's own changes and is ignored. */
    private var volumeHeldUntil = 0L
    private val pendingPages = mutableSetOf<Int>()
    private val pendingLibraryPages = mutableSetOf<String>()

    fun start() {
        dataClient.addListener(this)
        messageClient.addListener(this)
        scope.launch {
            // What the phone last published, if it's still believable: something that was playing
            // and wouldn't have ended yet. Anything else waits for the phone ("connecting").
            if (_nowPlaying.value == null) {
                try {
                    val items = dataClient.dataItems.await()
                    items.forEach { item ->
                        if (item.uri.path != Protocol.PATH_NOW_PLAYING) return@forEach
                        val map = DataMapItem.fromDataItem(item).dataMap
                        val age = System.currentTimeMillis() - map.getLong(Protocol.Key.STAMP)
                        val left = map.getLong(Protocol.Key.DURATION) - map.getLong(Protocol.Key.POSITION)
                        if (map.getBoolean(Protocol.Key.PLAYING) && age in 0 until left) applyNowPlaying(map, age)
                    }
                    items.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read cached state", e)
                }
            }
            requestState()
        }
    }

    /** Asks the phone app for its state; if it doesn't answer, it's closed. */
    fun requestState() {
        if (_status.value == LinkStatus.APP_CLOSED) _status.value = LinkStatus.CONNECTING
        send(Protocol.Cmd.REQUEST_STATE)
    }

    /**
     * After a command that always makes the phone app send its state back, expect it soon. No
     * answer means the app isn't running anymore (a force-stopped app can't say goodbye).
     */
    private fun expectAnswer() {
        val askedAt = SystemClock.elapsedRealtime()
        answerWatch?.cancel()
        answerWatch = scope.launch {
            delay(ANSWER_TIMEOUT_MS)
            if (lastStateAt < askedAt && _status.value != LinkStatus.NO_PHONE) appClosed()
        }
    }

    private fun appClosed() {
        _status.value = LinkStatus.APP_CLOSED
        _nowPlaying.value = null
    }

    fun stop() {
        dataClient.removeListener(this)
        messageClient.removeListener(this)
        pendingPages.clear()
        pendingLibraryPages.clear()
        pendingThumbnails.clear()
    }

    fun close() {
        stop()
        scope.cancel()
    }

    // ---- Commands ----

    fun togglePlay() {
        _nowPlaying.update { np ->
            np?.copy(isPlaying = !np.isPlaying, positionMs = np.currentPositionMs(), positionAt = SystemClock.elapsedRealtime())
        }
        send(Protocol.Cmd.TOGGLE)
    }

    fun next() = send(Protocol.Cmd.NEXT)
    fun previous() = send(Protocol.Cmd.PREVIOUS)
    fun output() = send(Protocol.Cmd.OUTPUT)

    fun toggleFavourite() {
        _nowPlaying.update { it?.copy(isFavourite = !it.isFavourite) }
        send(Protocol.Cmd.FAVOURITE)
    }

    fun changeVolume(up: Boolean) {
        volumeHeldUntil = SystemClock.elapsedRealtime() + VOLUME_HOLD_MS
        _nowPlaying.update { np ->
            np?.copy(volume = (np.volume + if (up) 1 else -1).coerceIn(0, np.volumeMax))
        }
        send(if (up) Protocol.Cmd.VOLUME_UP else Protocol.Cmd.VOLUME_DOWN)
    }

    fun playIndex(index: Int) {
        _queue.update { it.copy(current = index) }
        send(Protocol.Cmd.playIndex(index))
    }

    fun requestQueuePage(index: Int) {
        val pageStart = (index / QUEUE_PAGE_SIZE) * QUEUE_PAGE_SIZE
        if (!pendingPages.add(pageStart)) return
        scope.launch {
            val sent = sendTo(Protocol.PATH_QUEUE_REQUEST, "$pageStart,$QUEUE_PAGE_SIZE")
            if (!sent) pendingPages.remove(pageStart)
        }
    }

    fun seekTo(positionMs: Long) {
        _nowPlaying.update { it?.copy(positionMs = positionMs, positionAt = SystemClock.elapsedRealtime()) }
        send(Protocol.Cmd.seek(positionMs))
    }

    /** Fetches lyrics for [mediaId] unless they're already loaded or on their way. */
    fun requestLyrics(mediaId: String) {
        if (mediaId.isEmpty() || _lyrics.value?.mediaId == mediaId) return
        _lyrics.value = Lyrics.Loading(mediaId)
        scope.launch {
            if (!sendTo(Protocol.PATH_LYRICS_REQUEST, mediaId) && _lyrics.value == Lyrics.Loading(mediaId)) {
                _lyrics.value = null
            }
        }
    }

    fun toggleShuffle() {
        _nowPlaying.update { it?.copy(shuffle = !it.shuffle) }
        send(Protocol.Cmd.SHUFFLE)
    }

    fun cycleRepeat() {
        _nowPlaying.update { np ->
            val next = when (np?.repeatMode) {
                Protocol.Repeat.OFF -> Protocol.Repeat.ALL
                Protocol.Repeat.ALL -> Protocol.Repeat.ONE
                else -> Protocol.Repeat.OFF
            }
            np?.copy(repeatMode = next)
        }
        send(Protocol.Cmd.REPEAT)
    }

    fun setSleepTimer(minutes: Int) {
        val endAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
        _nowPlaying.update { it?.copy(sleepEndAt = endAt) }
        send(Protocol.Cmd.sleep(minutes))
    }

    fun requestOutputs() {
        scope.launch { sendTo(Protocol.PATH_OUTPUTS_REQUEST, "") }
    }

    /** null goes back to letting the phone pick. */
    fun selectOutput(deviceId: Int?) {
        _outputs.update { outputs ->
            outputs?.copy(
                auto = deviceId == null,
                devices = outputs.devices.map { it.copy(active = deviceId != null && it.id == deviceId) },
            )
        }
        send(if (deviceId == null) Protocol.Cmd.OUTPUT_AUTO else Protocol.Cmd.selectOutput(deviceId))
    }

    fun playLibraryItem(mediaId: String) = send(Protocol.Cmd.playItem(mediaId))

    /** Adds the playing song to a playlist. */
    fun addToPlaylist(playlistId: String) = send(Protocol.Cmd.addToPlaylist(playlistId))

    fun requestHome() {
        scope.launch { sendTo(Protocol.PATH_HOME_REQUEST, "") }
    }

    fun requestPlaylists() {
        scope.launch { sendTo(Protocol.PATH_PLAYLISTS_REQUEST, "") }
    }

    fun playAll(parentId: String, shuffle: Boolean) = send(Protocol.Cmd.playAll(parentId, shuffle))

    /** Loads the page of [parentId] holding [index]; a fresh open reloads the first page. */
    fun requestLibrary(parentId: String, index: Int = 0, refresh: Boolean = false) {
        val page = index / LIBRARY_PAGE_SIZE
        if (!refresh && _library.value[parentId]?.items?.containsKey(index) == true) return
        val key = "$page\n$parentId"
        if (!pendingLibraryPages.add(key)) return
        if (_library.value[parentId] == null) _library.update { it + (parentId to LibraryListing(parentId)) }
        scope.launch {
            if (!sendTo(Protocol.PATH_LIBRARY_REQUEST, key)) pendingLibraryPages.remove(key)
        }
    }

    fun search(query: String) {
        _search.value = SearchResult(query)
        scope.launch { sendTo(Protocol.PATH_SEARCH_REQUEST, query) }
    }

    fun requestThumbnail(uri: String) {
        if (uri.isEmpty() || thumbnails.containsKey(uri) || !pendingThumbnails.add(uri)) return
        scope.launch {
            if (!sendTo(Protocol.PATH_THUMB_REQUEST, uri)) pendingThumbnails.remove(uri)
        }
    }

    private fun send(cmd: String) {
        if (cmd in ANSWERED_COMMANDS) expectAnswer()
        scope.launch { sendTo(Protocol.PATH_CMD, cmd) }
    }

    private suspend fun sendTo(path: String, payload: String): Boolean {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            if (nodes.isEmpty()) {
                _status.value = LinkStatus.NO_PHONE
                return false
            }
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, path, payload.toByteArray(Charsets.UTF_8)).await()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed sending $path", e)
            false
        }
    }

    // ---- Incoming ----

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.dataItem.uri.path != Protocol.PATH_NOW_PLAYING) return@forEach
            when (event.type) {
                DataEvent.TYPE_CHANGED -> applyNowPlaying(DataMapItem.fromDataItem(event.dataItem).dataMap)
                // The phone app closed and cleared its state.
                DataEvent.TYPE_DELETED -> appClosed()
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Protocol.PATH_QUEUE_PAGE -> onQueuePage(event)
            Protocol.PATH_LYRICS -> onLyrics(event)
            Protocol.PATH_LIBRARY -> onLibrary(event)
            Protocol.PATH_SEARCH -> onSearch(event)
            Protocol.PATH_THUMB -> onThumbnail(event)
            Protocol.PATH_OUTPUTS -> onOutputs(event)
            Protocol.PATH_HOME -> onHome(event)
            Protocol.PATH_PLAYLISTS -> onPlaylists(event)
        }
    }

    private fun onHome(event: MessageEvent) {
        try {
            val arr = JSONObject(String(event.data, Charsets.UTF_8)).getJSONArray("sections")
            _home.value = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                HomeSection(
                    title = if (o.isNull("title")) null else o.optString("title"),
                    items = o.getJSONArray("items").toLibraryItems(),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad home", e)
        }
    }

    private fun onPlaylists(event: MessageEvent) {
        try {
            _playlists.value = JSONObject(String(event.data, Charsets.UTF_8)).getJSONArray("items").toLibraryItems()
        } catch (e: Exception) {
            Log.w(TAG, "Bad playlists", e)
        }
    }

    private fun onOutputs(event: MessageEvent) {
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val arr = json.getJSONArray("devices")
            _outputs.value = AudioOutputs(
                auto = json.optBoolean("auto", true),
                devices = List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    AudioOutput(o.getInt("id"), o.optString("name"), o.optString("kind"), o.optBoolean("active"))
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bad outputs", e)
        }
    }

    private fun JSONArray.toLibraryItems() = List(length()) { i ->
        val o = getJSONObject(i)
        LibraryItem(
            id = o.getString("id"),
            title = o.optString("t"),
            subtitle = o.optString("s"),
            artUri = o.optString("art"),
            kind = o.optString("k", Protocol.Kind.FOLDER),
            artist = o.optString("a"),
            durationMs = o.optLong("d"),
        )
    }

    private fun onLibrary(event: MessageEvent) {
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val parentId = json.getString("parent")
            val page = json.getInt("page")
            pendingLibraryPages.remove("$page\n$parentId")
            val items = json.getJSONArray("items").toLibraryItems()
            val start = page * LIBRARY_PAGE_SIZE
            _library.update { all ->
                val base = if (page == 0) emptyMap() else all[parentId]?.items.orEmpty()
                val merged = base + items.mapIndexed { i, item -> start + i to item }
                val art = if (page == 0) json.optString("art") else all[parentId]?.art.orEmpty()
                all + (parentId to LibraryListing(parentId, json.getInt("total"), merged, art))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad library page", e)
        }
    }

    private fun onSearch(event: MessageEvent) {
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val query = json.getString("query")
            if (_search.value?.query != query) return
            _search.value = SearchResult(query, json.getJSONArray("items").toLibraryItems())
        } catch (e: Exception) {
            Log.w(TAG, "Bad search result", e)
        }
    }

    private fun onThumbnail(event: MessageEvent) {
        val data = event.data
        val split = data.indexOf('\n'.code.toByte())
        if (split < 0) return
        val uri = String(data, 0, split, Charsets.UTF_8)
        pendingThumbnails.remove(uri)
        scope.launch {
            val bitmap = if (data.size > split + 1) {
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(data, split + 1, data.size - split - 1)?.asImageBitmap()
                }
            } else null
            thumbnails[uri] = bitmap
            thumbnailOrder.addLast(uri)
            while (thumbnailOrder.size > MAX_THUMBNAILS) thumbnails.remove(thumbnailOrder.removeFirst())
        }
    }

    private fun onLyrics(event: MessageEvent) {
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val id = json.getString("id")
            if (_lyrics.value?.mediaId != id) return
            val lines = json.optJSONArray("lines")
            _lyrics.value = when {
                lines != null && lines.length() > 0 -> Lyrics.Synced(
                    id,
                    List(lines.length()) { i ->
                        val line = lines.getJSONArray(i)
                        LyricLine(line.getLong(0), line.optString(1))
                    },
                )

                json.has("plain") -> Lyrics.Plain(id, json.getString("plain"))
                else -> Lyrics.None(id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad lyrics", e)
        }
    }

    private fun onQueuePage(event: MessageEvent) {
        try {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            val start = json.getInt("start")
            val version = json.getLong("version")
            val arr = json.getJSONArray("items")
            val page = HashMap<Int, QueueItem>(arr.length())
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONArray(i)
                page[start + i] = QueueItem(entry.optString(0), entry.optString(1), entry.optString(2))
            }
            pendingPages.remove(start)
            _queue.update { q ->
                val base = if (q.version == version) q.items else emptyMap()
                QueueState(version = version, total = json.getInt("total"), current = json.getInt("current"), items = base + page)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad queue page", e)
        }
    }

    /** [ageMs]: how long ago the phone published [map] (for a cached state; 0 when it just arrived). */
    private fun applyNowPlaying(map: DataMap, ageMs: Long = 0L) {
        _status.value = LinkStatus.CONNECTED
        lastStateAt = SystemClock.elapsedRealtime()
        val np = NowPlaying(
            title = map.getString(Protocol.Key.TITLE, ""),
            artist = map.getString(Protocol.Key.ARTIST, ""),
            isPlaying = map.getBoolean(Protocol.Key.PLAYING),
            isFavourite = map.getBoolean(Protocol.Key.FAVOURITE),
            durationMs = map.getLong(Protocol.Key.DURATION),
            positionMs = map.getLong(Protocol.Key.POSITION),
            positionAt = SystemClock.elapsedRealtime() - ageMs,
            index = map.getInt(Protocol.Key.INDEX),
            queueLength = map.getInt(Protocol.Key.QUEUE_LENGTH),
            queueVersion = map.getLong(Protocol.Key.QUEUE_VERSION),
            volume = map.getInt(Protocol.Key.VOLUME),
            volumeMax = map.getInt(Protocol.Key.VOLUME_MAX, 15),
            mediaId = map.getString(Protocol.Key.MEDIA_ID, ""),
            shuffle = map.getBoolean(Protocol.Key.SHUFFLE),
            repeatMode = map.getInt(Protocol.Key.REPEAT),
            sleepEndAt = map.getLong(Protocol.Key.SLEEP_END_AT),
            outputType = map.getString(Protocol.Key.OUTPUT_TYPE, "phone"),
            albumId = map.getString(Protocol.Key.ALBUM_ID, ""),
            albumName = map.getString(Protocol.Key.ALBUM_NAME, ""),
            artistId = map.getString(Protocol.Key.ARTIST_ID, ""),
            artistName = map.getString(Protocol.Key.ARTIST_NAME, ""),
            capabilities = map.capabilities(),
        )
        val previous = _nowPlaying.value
        _nowPlaying.value = if (previous != null && SystemClock.elapsedRealtime() < volumeHeldUntil) {
            np.copy(volume = previous.volume)
        } else {
            np
        }
        _queue.update { q ->
            if (q.version != np.queueVersion) {
                pendingPages.clear()
                QueueState(version = np.queueVersion, total = np.queueLength, current = np.index)
            } else {
                q.copy(total = np.queueLength, current = np.index)
            }
        }

        val newArtId = map.getString(Protocol.Key.ART_ID)
        if (newArtId != artId) {
            artId = newArtId
            val asset = map.getAsset(Protocol.Key.ART)
            if (asset == null) {
                _artwork.value = null
                _artworkColor.value = null
            } else {
                loadArtwork(asset, newArtId)
            }
        }
    }

    private fun DataMap.capabilities(): Capabilities {
        fun cap(name: String, default: Boolean) =
            if (containsKey(Protocol.Key.CAP_PREFIX + name)) getBoolean(Protocol.Key.CAP_PREFIX + name) else default
        val defaults = Capabilities()
        return Capabilities(
            queue = cap("queue", defaults.queue),
            lyrics = cap("lyrics", defaults.lyrics),
            library = cap("library", defaults.library),
            search = cap("search", defaults.search),
            favorite = cap("favorite", defaults.favorite),
            shuffle = cap("shuffle", defaults.shuffle),
            repeat = cap("repeat", defaults.repeat),
            sleepTimer = cap("sleepTimer", defaults.sleepTimer),
            playlists = cap("playlists", defaults.playlists),
        )
    }

    private fun loadArtwork(asset: Asset, forId: String?) {
        scope.launch {
            val bitmap = try {
                val fd = dataClient.getFdForAsset(asset).await()
                withContext(Dispatchers.IO) { fd.inputStream.use { BitmapFactory.decodeStream(it) } }
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading artwork", e)
                null
            }
            val color = bitmap?.let { withContext(Dispatchers.Default) { readableColorOf(it) } }
            if (artId == forId) {
                _artwork.value = bitmap?.asImageBitmap()
                _artworkColor.value = color
                // Forget a failed load so the next update from the phone tries again.
                if (bitmap == null) artId = null
            }
        }
    }

    /** The artwork's most vivid color, lightened until it reads well on the dark screen. */
    private fun readableColorOf(bitmap: Bitmap): Color? {
        val palette = Palette.from(bitmap).maximumColorCount(16).generate()
        val swatch = palette.lightVibrantSwatch ?: palette.vibrantSwatch ?: palette.lightMutedSwatch
            ?: palette.dominantSwatch ?: return null
        val hsl = swatch.hsl.copyOf()
        hsl[2] = hsl[2].coerceAtLeast(MIN_ARTIST_LIGHTNESS)
        return Color(ColorUtils.HSLToColor(hsl))
    }

    companion object {
        private const val TAG = "WearMediaWatch"
        private const val MIN_ARTIST_LIGHTNESS = 0.68f
        const val QUEUE_PAGE_SIZE = 40
        const val LIBRARY_PAGE_SIZE = 50
        private const val VOLUME_HOLD_MS = 1_500L
        private const val ANSWER_TIMEOUT_MS = 4_000L

        /** Commands the phone app always answers with a fresh state. */
        private val ANSWERED_COMMANDS = setOf(
            Protocol.Cmd.REQUEST_STATE, Protocol.Cmd.TOGGLE, Protocol.Cmd.NEXT, Protocol.Cmd.PREVIOUS,
        )
        private const val MAX_THUMBNAILS = 250
    }
}
