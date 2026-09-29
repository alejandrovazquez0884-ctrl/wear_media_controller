package io.github.wearmedia.wear_media_controller

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Phone side of the watch controller: publishes what the Dart side reports and turns the
 * watch's requests into calls to the app's `WearMediaHandler`. See PROTOCOL.md.
 */
class WearMediaControllerPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var context: Context
    private lateinit var channel: MethodChannel
    private lateinit var audioManager: AudioManager
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var state: Map<*, *>? = null
    private var publishJob: Job? = null

    /** Artwork key currently in [artAsset]; set only once it really loaded. */
    private var artKey: String? = null
    private var artAsset: Asset? = null

    /** Watch that last asked for the outputs, told again when devices come and go. */
    private var outputsNode: String? = null
    private var preferredDeviceId: Int? = null

    // Kept private so apps don't need the Wearable library on their own classpath.
    private val messageListener = MessageClient.OnMessageReceivedListener { onMessageReceived(it) }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onDevicesChanged()
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        channel = MethodChannel(binding.binaryMessenger, "wear_media_controller")
        channel.setMethodCallHandler(this)
        Wearable.getMessageClient(context).addListener(messageListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // The app is going away: tell the watch nothing is playing here anymore.
        Wearable.getDataClient(context).deleteDataItems(
            Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_STATE).build()
        )
        Wearable.getMessageClient(context).removeListener(messageListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        channel.setMethodCallHandler(null)
        scope.cancel()
    }

    // ---- Dart -> watch ----

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "updateState" -> {
                state = call.arguments as? Map<*, *>
                schedulePublish()
                result.success(null)
            }

            "clearState" -> {
                state = null
                scope.launch { clearPublished() }
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun schedulePublish() {
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            publish()
        }
    }

    private suspend fun publish() {
        val s = state ?: return
        val key = s.string("artworkUri")
        if (key != artKey) {
            artAsset = null
            artKey = null
            val bytes = key.takeIf { it.isNotEmpty() }?.let { loadArtwork(it, ART_SIZE) }
            if (bytes != null) {
                artAsset = Asset.createFromBytes(bytes)
                artKey = key
            }
        }

        val request = PutDataMapRequest.create(PATH_STATE)
        request.dataMap.apply {
            putString("mediaId", s.string("mediaId"))
            putString("title", s.string("title"))
            putString("artist", s.string("artist"))
            putBoolean("playing", s["isPlaying"] == true)
            putBoolean("favourite", s["isFavorite"] == true)
            putLong("duration", s.long("durationMs"))
            putLong("position", s.long("positionMs"))
            putInt("index", s.long("queueIndex").toInt())
            putInt("queueLength", s.long("queueLength").toInt())
            putLong("queueVersion", s.long("queueVersion"))
            putBoolean("shuffle", s["shuffle"] == true)
            putInt("repeat", s.long("repeatMode").toInt())
            putLong("sleepEndAt", s.long("sleepTimerEndsAt"))
            putString("albumId", s.string("albumId"))
            putString("albumTitle", s.string("albumTitle"))
            putString("artistId", s.string("artistId"))
            putString("artistName", s.string("artistName"))
            (s["capabilities"] as? Map<*, *>)?.forEach { (name, enabled) ->
                putBoolean("cap_$name", enabled == true)
            }
            putInt("volume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            putInt("volumeMax", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            putString("outputType", activeOutput()?.let(::outputKind) ?: "phone")
            artAsset?.let { putAsset("art", it) }
            putString("artId", artKey ?: "")
            // Always differs, so the watch gets a fresh position even when nothing else changed.
            putLong("stamp", System.currentTimeMillis())
        }
        try {
            Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()).await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed publishing state", e)
        }
    }

    private suspend fun clearPublished() {
        try {
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(PATH_STATE).build()
            Wearable.getDataClient(context).deleteDataItems(uri).await()
        } catch (e: Exception) {
            Log.w(TAG, "Failed clearing state", e)
        }
    }

    // ---- Watch -> Dart ----

    private fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data, Charsets.UTF_8)
        val node = event.sourceNodeId
        when (event.path) {
            PATH_CMD -> onCommand(payload)
            "$PATH_QUEUE/request" -> onQueueRequest(node, payload)
            "$PATH_LYRICS/request" -> onLyricsRequest(node, payload)
            "$PATH_HOME/request" -> onHomeRequest(node)
            "$PATH_BROWSE/request" -> onBrowseRequest(node, payload)
            "$PATH_SEARCH/request" -> onSearchRequest(node, payload)
            "$PATH_PLAYLISTS/request" -> onPlaylistsRequest(node)
            "$PATH_THUMB/request" -> onThumbRequest(node, payload)
            "$PATH_OUTPUTS/request" -> {
                outputsNode = node
                sendOutputs(node)
            }
        }
    }

    private fun onCommand(cmd: String) {
        scope.launch {
            when (cmd) {
                "toggle" -> callDart("togglePlayPause")
                "next" -> callDart("skipToNext")
                "previous" -> callDart("skipToPrevious")
                "favourite" -> callDart("toggleFavorite")
                "shuffle" -> callDart("toggleShuffle")
                "repeat" -> callDart("cycleRepeatMode")
                "request_state" -> callDart("requestState")
                "volume_up", "volume_down" -> {
                    val direction = if (cmd == "volume_up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                    schedulePublish()
                }

                "output" -> openOutputSwitcher()
                "output_auto" -> selectOutput(null)
                else -> {
                    val name = cmd.substringBefore(':')
                    val arg = cmd.substringAfter(':', "")
                    when (name) {
                        "play" -> arg.toIntOrNull()?.let { callDart("skipToQueueItem", mapOf("index" to it)) }
                        "seek" -> arg.toLongOrNull()?.let { callDart("seekTo", mapOf("positionMs" to it)) }
                        "sleep" -> arg.toIntOrNull()?.let { callDart("setSleepTimer", mapOf("minutes" to it)) }
                        "output" -> selectOutput(arg.toIntOrNull())
                        "add_to_playlist" -> callDart("addToPlaylist", mapOf("id" to arg))
                        "play_item" -> callDart("playItem", mapOf("id" to arg))
                        // play_all:<0|1>:<folderId>
                        "play_all" -> callDart(
                            "playFolder",
                            mapOf("id" to arg.substringAfter(':'), "shuffle" to (arg.substringBefore(':') == "1")),
                        )
                    }
                }
            }
        }
    }

    private fun onQueueRequest(node: String, payload: String) {
        val start = payload.substringBefore(',').toIntOrNull() ?: return
        val count = payload.substringAfter(',').toIntOrNull() ?: 40
        scope.launch {
            val items = callDart("getQueueItems", mapOf("start" to start, "count" to count)) as? List<*>
            val arr = JSONArray()
            items.orEmpty().forEach { entry ->
                val m = entry as? Map<*, *> ?: return@forEach
                arr.put(JSONArray().put(m.string("title")).put(m.string("artist")).put(m.string("artworkUri")))
            }
            val s = state
            val json = JSONObject()
                .put("start", start)
                .put("total", s?.long("queueLength") ?: 0L)
                .put("current", s?.long("queueIndex") ?: 0L)
                .put("version", s?.long("queueVersion") ?: 0L)
                .put("items", arr)
            reply(node, PATH_QUEUE, json.toString())
        }
    }

    private fun onLyricsRequest(node: String, mediaId: String) {
        scope.launch {
            val lyrics = callDart("getLyrics", mapOf("mediaId" to mediaId)) as? Map<*, *>
            val json = JSONObject().put("id", mediaId)
            (lyrics?.get("lines") as? List<*>)?.let { lines ->
                val arr = JSONArray()
                lines.forEach { line ->
                    val l = line as? List<*> ?: return@forEach
                    arr.put(JSONArray().put((l[0] as Number).toLong()).put(l[1]?.toString().orEmpty()))
                }
                json.put("lines", arr)
            }
            (lyrics?.get("plain") as? String)?.let { json.put("plain", it) }
            reply(node, PATH_LYRICS, json.toString())
        }
    }

    private fun onHomeRequest(node: String) {
        scope.launch {
            val sections = callDart("getHome") as? List<*>
            val arr = JSONArray()
            sections.orEmpty().forEach { section ->
                val m = section as? Map<*, *> ?: return@forEach
                arr.put(
                    JSONObject()
                        .put("title", m["title"]?.toString() ?: JSONObject.NULL)
                        .put("items", itemsJson(m["items"] as? List<*>))
                )
            }
            reply(node, PATH_HOME, JSONObject().put("sections", arr).toString())
        }
    }

    /** Payload: "<page>\n<folderId>". */
    private fun onBrowseRequest(node: String, payload: String) {
        val page = payload.substringBefore('\n').toIntOrNull() ?: 0
        val folderId = payload.substringAfter('\n')
        scope.launch {
            val result = callDart("browse", mapOf("id" to folderId, "page" to page, "pageSize" to PAGE_SIZE)) as? Map<*, *>
            val json = JSONObject()
                .put("parent", folderId)
                .put("page", page)
                .put("total", result?.long("total") ?: 0L)
                .put("art", result?.string("artworkUri") ?: "")
                .put("items", itemsJson(result?.get("items") as? List<*>))
            reply(node, PATH_BROWSE, json.toString())
        }
    }

    private fun onSearchRequest(node: String, query: String) {
        scope.launch {
            val items = callDart("search", mapOf("query" to query)) as? List<*>
            reply(node, PATH_SEARCH, JSONObject().put("query", query).put("items", itemsJson(items)).toString())
        }
    }

    private fun onPlaylistsRequest(node: String) {
        scope.launch {
            val items = callDart("getPlaylists") as? List<*>
            reply(node, PATH_PLAYLISTS, JSONObject().put("items", itemsJson(items)).toString())
        }
    }

    /** Answers with "<uri>\n" followed by a small JPEG (nothing after it when there's none). */
    private fun onThumbRequest(node: String, uri: String) {
        scope.launch {
            val jpeg = loadArtwork(uri, THUMB_SIZE) ?: ByteArray(0)
            val bytes = (uri + "\n").toByteArray(Charsets.UTF_8) + jpeg
            try {
                Wearable.getMessageClient(context).sendMessage(node, PATH_THUMB, bytes).await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending thumbnail", e)
            }
        }
    }

    private fun itemsJson(items: List<*>?): JSONArray {
        val arr = JSONArray()
        items.orEmpty().forEach { entry ->
            val m = entry as? Map<*, *> ?: return@forEach
            arr.put(
                JSONObject()
                    .put("id", m.string("id"))
                    .put("t", m.string("title"))
                    .put("s", m.string("subtitle"))
                    .put("a", m.string("artist"))
                    .put("art", m.string("artworkUri"))
                    .put("k", m.string("kind"))
                    .put("d", m.long("durationMs"))
            )
        }
        return arr
    }

    // ---- Audio outputs ----

    /** Outputs worth offering, one per device (the system lists some twice, e.g. per profile). */
    private fun outputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { outputKind(it) != null }
            .distinctBy { outputKind(it) + "|" + it.productName }

    /** The device playback goes to right now. */
    private fun activeOutput(): AudioDeviceInfo? {
        preferredDeviceId?.let { id -> outputDevices().firstOrNull { it.id == id }?.let { return it } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            return audioManager.getAudioDevicesForAttributes(attributes).firstOrNull()?.let { routed ->
                outputDevices().firstOrNull { it.type == routed.type && it.address == routed.address }
                    ?: outputDevices().firstOrNull { it.type == routed.type }
            }
        }
        return null
    }

    private fun outputKind(device: AudioDeviceInfo): String? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "phone"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> "bluetooth"

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"

        else -> null
    }

    private suspend fun selectOutput(deviceId: Int?) {
        val handled = callDart("selectAudioDevice", mapOf("deviceId" to deviceId)) == true
        if (!handled) {
            openOutputSwitcher()
            return
        }
        preferredDeviceId = deviceId
        // The route change lands a moment later.
        delay(OUTPUT_SETTLE_MS)
        outputsNode?.let(::sendOutputs)
        publish()
    }

    private fun onDevicesChanged() {
        if (preferredDeviceId != null && outputDevices().none { it.id == preferredDeviceId }) {
            // The picked device went away (e.g. headphones off); back to automatic.
            preferredDeviceId = null
            scope.launch { callDart("selectAudioDevice", mapOf("deviceId" to null)) }
        }
        outputsNode?.let(::sendOutputs)
        schedulePublish()
    }

    private fun sendOutputs(node: String) {
        val active = activeOutput()
        val devices = JSONArray()
        outputDevices().forEach { device ->
            devices.put(
                JSONObject()
                    .put("id", device.id)
                    .put("name", device.productName?.toString().orEmpty())
                    .put("kind", outputKind(device))
                    .put("active", device.id == active?.id)
            )
        }
        reply(node, PATH_OUTPUTS, JSONObject().put("auto", preferredDeviceId == null).put("devices", devices).toString())
    }

    private fun openOutputSwitcher() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                MediaRouter2.getInstance(context).showSystemOutputSwitcher()
            ) return
            context.sendBroadcast(
                Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", context.packageName)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed opening output switcher", e)
        }
    }

    // ---- Helpers ----

    /** Calls the Dart handler and waits for its answer (null on error or when unhandled). */
    private suspend fun callDart(method: String, args: Any? = null): Any? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            channel.invokeMethod(method, args, object : MethodChannel.Result {
                override fun success(result: Any?) = cont.resume(result)
                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    Log.w(TAG, "$method failed: $errorCode $errorMessage")
                    cont.resume(null)
                }

                override fun notImplemented() = cont.resume(null)
            })
        }
    }

    /** The app's artwork for [key], center-cropped to a [size] square JPEG. */
    private suspend fun loadArtwork(key: String, size: Int): ByteArray? {
        val raw = callDart("loadArtwork", mapOf("uri" to key)) as? ByteArray ?: return null
        return withContext(Dispatchers.Default) {
            try {
                val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return@withContext null
                val side = minOf(bitmap.width, bitmap.height)
                val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
                val scaled = if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, if (size > THUMB_SIZE) 82 else 75, out)
                out.toByteArray()
            } catch (e: Exception) {
                Log.w(TAG, "Bad artwork for $key", e)
                null
            }
        }
    }

    private fun reply(node: String, path: String, json: String) {
        scope.launch {
            try {
                Wearable.getMessageClient(context).sendMessage(node, path, json.toByteArray(Charsets.UTF_8)).await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed sending $path", e)
            }
        }
    }

    private fun Map<*, *>.string(key: String) = this[key]?.toString().orEmpty()

    private fun Map<*, *>.long(key: String) = (this[key] as? Number)?.toLong() ?: 0L

    companion object {
        private const val TAG = "WearMediaController"
        private const val PATH_STATE = "/wear_media/state"
        private const val PATH_CMD = "/wear_media/cmd"
        private const val PATH_QUEUE = "/wear_media/queue"
        private const val PATH_LYRICS = "/wear_media/lyrics"
        private const val PATH_HOME = "/wear_media/home"
        private const val PATH_BROWSE = "/wear_media/browse"
        private const val PATH_SEARCH = "/wear_media/search"
        private const val PATH_PLAYLISTS = "/wear_media/playlists"
        private const val PATH_THUMB = "/wear_media/thumb"
        private const val PATH_OUTPUTS = "/wear_media/outputs"
        private const val PUBLISH_DEBOUNCE_MS = 150L
        private const val OUTPUT_SETTLE_MS = 600L
        private const val ART_SIZE = 360
        private const val THUMB_SIZE = 96
        private const val PAGE_SIZE = 50
    }
}
