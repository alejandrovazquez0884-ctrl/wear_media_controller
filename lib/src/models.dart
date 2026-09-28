/// Repeat mode shown on (and cycled from) the watch.
enum WearRepeatMode { off, one, all }

/// What a [WearMediaItem] is. The watch uses it for its look: artists get round pictures and
/// their own page layout, songs play when tapped, the rest open as folders.
enum WearItemKind { song, album, artist, playlist, genre, folder }

/// The features the watch should offer. Anything disabled is hidden on the watch.
class WearCapabilities {
  const WearCapabilities({
    this.queue = true,
    this.lyrics = false,
    this.library = false,
    this.search = false,
    this.favorite = false,
    this.shuffle = true,
    this.repeat = true,
    this.sleepTimer = false,
    this.playlists = false,
  });

  final bool queue;
  final bool lyrics;
  final bool library;
  final bool search;
  final bool favorite;
  final bool shuffle;
  final bool repeat;
  final bool sleepTimer;
  final bool playlists;

  Map<String, Object?> toMap() => {
        'queue': queue,
        'lyrics': lyrics,
        'library': library,
        'search': search,
        'favorite': favorite,
        'shuffle': shuffle,
        'repeat': repeat,
        'sleepTimer': sleepTimer,
        'playlists': playlists,
      };
}

/// Everything the watch shows about what's playing. Send a new one whenever something changes
/// (track, play/pause, a seek, the queue...); the watch interpolates the position on its own.
class WearPlaybackState {
  const WearPlaybackState({
    required this.mediaId,
    required this.title,
    this.artist = '',
    this.artworkUri,
    this.isPlaying = false,
    this.isFavorite = false,
    this.duration = Duration.zero,
    this.position = Duration.zero,
    this.queueIndex = 0,
    this.queueLength = 0,
    this.queueVersion = 0,
    this.shuffle = false,
    this.repeatMode = WearRepeatMode.off,
    this.sleepTimerEndsAt,
    this.albumId,
    this.albumTitle,
    this.artistId,
    this.artistName,
  });

  /// Identifies the track; also what [WearMediaHandler.getLyrics] receives.
  final String mediaId;
  final String title;
  final String artist;

  /// Opaque key for the artwork, handed back to [WearMediaHandler.loadArtwork]. Keep it stable
  /// for the same picture: the watch only fetches it again when it changes.
  final String? artworkUri;
  final bool isPlaying;
  final bool isFavorite;
  final Duration duration;
  final Duration position;

  /// Position of the playing track in the queue, and the queue's size.
  final int queueIndex;
  final int queueLength;

  /// Bump it whenever the queue's contents change, so the watch drops its cached pages.
  final int queueVersion;
  final bool shuffle;
  final WearRepeatMode repeatMode;
  final DateTime? sleepTimerEndsAt;

  /// Folder ids (as understood by [WearMediaHandler.browse]) of the track's album and artist,
  /// for the watch's "go to album/artist" actions. Leave null to hide them.
  final String? albumId;
  final String? albumTitle;
  final String? artistId;
  final String? artistName;

  Map<String, Object?> toMap() => {
        'mediaId': mediaId,
        'title': title,
        'artist': artist,
        'artworkUri': artworkUri,
        'isPlaying': isPlaying,
        'isFavorite': isFavorite,
        'durationMs': duration.inMilliseconds,
        'positionMs': position.inMilliseconds,
        'queueIndex': queueIndex,
        'queueLength': queueLength,
        'queueVersion': queueVersion,
        'shuffle': shuffle,
        'repeatMode': repeatMode.index,
        'sleepTimerEndsAt': sleepTimerEndsAt?.millisecondsSinceEpoch ?? 0,
        'albumId': albumId,
        'albumTitle': albumTitle,
        'artistId': artistId,
        'artistName': artistName,
      };
}

/// An entry of the queue list on the watch.
class WearQueueItem {
  const WearQueueItem({required this.title, this.artist = '', this.artworkUri});

  final String title;
  final String artist;
  final String? artworkUri;

  Map<String, Object?> toMap() => {'title': title, 'artist': artist, 'artworkUri': artworkUri};
}

/// Something in the library: a song to play or a folder to open.
class WearMediaItem {
  const WearMediaItem({
    required this.id,
    required this.title,
    required this.kind,
    this.subtitle = '',
    this.artist = '',
    this.artworkUri,
    this.duration = Duration.zero,
  });

  /// Passed back to [WearMediaHandler.playItem] (songs) or [WearMediaHandler.browse] (folders).
  final String id;
  final String title;
  final WearItemKind kind;
  final String subtitle;

  /// Shown under a song's title in album/artist pages.
  final String artist;
  final String? artworkUri;
  final Duration duration;

  bool get isPlayable => kind == WearItemKind.song;

  Map<String, Object?> toMap() => {
        'id': id,
        'title': title,
        'kind': kind.name,
        'subtitle': subtitle,
        'artist': artist,
        'artworkUri': artworkUri,
        'durationMs': duration.inMilliseconds,
      };
}

/// A page of a folder's contents.
class WearFolderPage {
  const WearFolderPage({required this.items, required this.total, this.artworkUri});

  final List<WearMediaItem> items;

  /// How many items the whole folder has (the watch pages through it).
  final int total;

  /// The folder's own picture (album cover, artist photo...), for its page header.
  final String? artworkUri;

  Map<String, Object?> toMap() => {
        'items': [for (final item in items) item.toMap()],
        'total': total,
        'artworkUri': artworkUri,
      };
}

/// A section of the watch's home page. A section without a title shows first, as quick picks.
class WearHomeSection {
  const WearHomeSection({this.title, required this.items});

  final String? title;
  final List<WearMediaItem> items;

  Map<String, Object?> toMap() => {
        'title': title,
        'items': [for (final item in items) item.toMap()],
      };
}

/// One timed line of synced lyrics.
class WearLyricLine {
  const WearLyricLine(this.start, this.text);

  /// When the line starts, in track position.
  final Duration start;
  final String text;
}

/// Lyrics for a track: timed lines, or plain text when they aren't synced.
class WearLyrics {
  const WearLyrics.synced(List<WearLyricLine> this.lines) : plain = null;

  const WearLyrics.plain(String this.plain) : lines = null;

  final List<WearLyricLine>? lines;
  final String? plain;

  Map<String, Object?> toMap() => {
        if (lines != null) 'lines': [for (final l in lines!) [l.start.inMilliseconds, l.text]],
        if (plain != null) 'plain': plain,
      };
}
