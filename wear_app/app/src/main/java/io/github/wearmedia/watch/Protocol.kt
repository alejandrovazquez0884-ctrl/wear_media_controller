package io.github.wearmedia.watch

/**
 * Paths and keys shared with the phone plugin
 * (android/.../WearMediaControllerPlugin.kt). See PROTOCOL.md.
 */
object Protocol {
    /** DataItem published by the phone with the playback state. */
    const val PATH_NOW_PLAYING = "/wear_media/state"

    /** Watch -> phone command message. Payload is a UTF-8 command string (see [Cmd]). */
    const val PATH_CMD = "/wear_media/cmd"

    /** "start,count" -> JSON {"start","total","current","version","items":[[title,artist,artUri],...]}. */
    const val PATH_QUEUE_REQUEST = "/wear_media/queue/request"
    const val PATH_QUEUE_PAGE = "/wear_media/queue"

    /** media id -> JSON {"id", "lines":[[startMs, text],...]} or {"id", "plain"}; neither means none. */
    const val PATH_LYRICS_REQUEST = "/wear_media/lyrics/request"
    const val PATH_LYRICS = "/wear_media/lyrics"

    /** "<page>\n<folderId>" -> JSON {"parent","page","total","art","items":[item,...]}. */
    const val PATH_LIBRARY_REQUEST = "/wear_media/browse/request"
    const val PATH_LIBRARY = "/wear_media/browse"

    /** (empty) -> JSON {"sections":[{"title": String or null, "items":[item,...]}]}. */
    const val PATH_HOME_REQUEST = "/wear_media/home/request"
    const val PATH_HOME = "/wear_media/home"

    /** query -> JSON {"query","items":[item,...]}. */
    const val PATH_SEARCH_REQUEST = "/wear_media/search/request"
    const val PATH_SEARCH = "/wear_media/search"

    /** (empty) -> JSON {"items":[item,...]}, the playlists the playing song can be added to. */
    const val PATH_PLAYLISTS_REQUEST = "/wear_media/playlists/request"
    const val PATH_PLAYLISTS = "/wear_media/playlists"

    /** artwork uri -> "<uri>\n" followed by JPEG bytes (none when there's no artwork). */
    const val PATH_THUMB_REQUEST = "/wear_media/thumb/request"
    const val PATH_THUMB = "/wear_media/thumb"

    /** (empty) -> JSON {"auto", "devices":[{"id","name","kind","active"},...]}; kind is phone|bluetooth|wired|usb. */
    const val PATH_OUTPUTS_REQUEST = "/wear_media/outputs/request"
    const val PATH_OUTPUTS = "/wear_media/outputs"

    /** Library folder ids with a meaning of their own. */
    object Library {
        const val ROOT = "root"
    }

    /** Kinds of library items ("k" in item JSON). */
    object Kind {
        const val SONG = "song"
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val PLAYLIST = "playlist"
        const val GENRE = "genre"
        const val FOLDER = "folder"
    }

    object Key {
        const val TITLE = "title"
        const val ARTIST = "artist"
        const val PLAYING = "playing"
        const val FAVOURITE = "favourite"
        const val DURATION = "duration"
        const val POSITION = "position"
        const val INDEX = "index"
        const val QUEUE_LENGTH = "queueLength"
        const val QUEUE_VERSION = "queueVersion"
        const val VOLUME = "volume"
        const val VOLUME_MAX = "volumeMax"
        const val SHUFFLE = "shuffle"
        const val REPEAT = "repeat"
        const val SLEEP_END_AT = "sleepEndAt"
        const val OUTPUT_TYPE = "outputType"
        const val ART = "art"
        const val ART_ID = "artId"
        const val MEDIA_ID = "mediaId"
        const val ALBUM_ID = "albumId"
        const val ALBUM_NAME = "albumTitle"
        const val ARTIST_ID = "artistId"
        const val ARTIST_NAME = "artistName"
        const val STAMP = "stamp"

        /** Capabilities are "cap_<name>" booleans. */
        const val CAP_PREFIX = "cap_"
    }

    object Cmd {
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val FAVOURITE = "favourite"
        const val SHUFFLE = "shuffle"
        const val REPEAT = "repeat"
        const val VOLUME_UP = "volume_up"
        const val VOLUME_DOWN = "volume_down"
        const val OUTPUT = "output"
        const val OUTPUT_AUTO = "output_auto"
        const val REQUEST_STATE = "request_state"
        fun playIndex(index: Int) = "play:$index"
        fun seek(positionMs: Long) = "seek:$positionMs"
        /** 0 cancels the timer. */
        fun sleep(minutes: Int) = "sleep:$minutes"
        fun selectOutput(deviceId: Int) = "output:$deviceId"
        fun addToPlaylist(playlistId: String) = "add_to_playlist:$playlistId"
        fun playItem(mediaId: String) = "play_item:$mediaId"
        fun playAll(parentId: String, shuffle: Boolean) = "play_all:${if (shuffle) 1 else 0}:$parentId"
    }

    /** WearRepeatMode indexes: off, one, all. */
    object Repeat {
        const val OFF = 0
        const val ONE = 1
        const val ALL = 2
    }
}
