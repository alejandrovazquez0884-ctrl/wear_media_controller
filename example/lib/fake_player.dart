import 'dart:async';
import 'dart:math';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:wear_media_controller/wear_media_controller.dart';

/// A made-up song for the demo.
class DemoSong {
  const DemoSong(this.id, this.title, this.artist, this.album, this.duration, this.color);

  final String id;
  final String title;
  final String artist;
  final String album;
  final Duration duration;
  final Color color;

  String get albumId => 'album:$album';
  String get artistId => 'artist:$artist';
}

const _songs = [
  DemoSong('1', 'Morning Light', 'Aurora Fields', 'Daybreak', Duration(minutes: 3, seconds: 12), Color(0xFFE57373)),
  DemoSong('2', 'Paper Planes', 'Aurora Fields', 'Daybreak', Duration(minutes: 2, seconds: 48), Color(0xFFE57373)),
  DemoSong('3', 'Low Tide', 'The Harbor', 'Salt', Duration(minutes: 4, seconds: 5), Color(0xFF4FC3F7)),
  DemoSong('4', 'Lighthouse', 'The Harbor', 'Salt', Duration(minutes: 3, seconds: 33), Color(0xFF4FC3F7)),
  DemoSong('5', 'Neon Rain', 'Night Shift', 'After Hours', Duration(minutes: 3, seconds: 58), Color(0xFFBA68C8)),
  DemoSong('6', 'Last Train', 'Night Shift', 'After Hours', Duration(minutes: 4, seconds: 21), Color(0xFFBA68C8)),
  DemoSong('7', 'Green Room', 'Moss', 'Canopy', Duration(minutes: 2, seconds: 59), Color(0xFF81C784)),
  DemoSong('8', 'Fern', 'Moss', 'Canopy', Duration(minutes: 3, seconds: 40), Color(0xFF81C784)),
];

/// Pretends to play [_songs] (no audio) and answers everything the watch can ask for, so the
/// whole watch app can be tried out. A real app wires these to its own player and library.
class FakePlayer extends WearMediaHandler with ChangeNotifier {
  FakePlayer() {
    _ticker = Timer.periodic(const Duration(milliseconds: 500), (_) => _tick());
  }

  late final Timer _ticker;
  List<DemoSong> _queue = List.of(_songs);
  int _index = 0;
  int _queueVersion = 0;
  bool _playing = false;
  Duration _position = Duration.zero;
  bool _shuffle = false;
  WearRepeatMode _repeat = WearRepeatMode.off;
  DateTime? _sleepAt;
  final _favorites = <String>{};
  final _playlist = <String>[];

  DemoSong get current => _queue[_index];
  bool get isPlaying => _playing;
  Duration get position => _position;

  WearPlaybackState get state => WearPlaybackState(
        mediaId: current.id,
        title: current.title,
        artist: current.artist,
        artworkUri: current.albumId,
        isPlaying: _playing,
        isFavorite: _favorites.contains(current.id),
        duration: current.duration,
        position: _position,
        queueIndex: _index,
        queueLength: _queue.length,
        queueVersion: _queueVersion,
        shuffle: _shuffle,
        repeatMode: _repeat,
        sleepTimerEndsAt: _sleepAt,
        albumId: current.albumId,
        albumTitle: current.album,
        artistId: current.artistId,
        artistName: current.artist,
      );

  void _tick() {
    if (_sleepAt != null && DateTime.now().isAfter(_sleepAt!)) {
      _sleepAt = null;
      _playing = false;
      _changed();
    }
    if (!_playing) return;
    _position += const Duration(milliseconds: 500);
    if (_position >= current.duration) {
      _advance();
    } else {
      notifyListeners();
    }
  }

  void _advance() {
    _position = Duration.zero;
    if (_repeat != WearRepeatMode.one) {
      if (_index < _queue.length - 1) {
        _index++;
      } else if (_repeat == WearRepeatMode.all) {
        _index = 0;
      } else {
        _playing = false;
      }
    }
    _changed();
  }

  /// Tells the watch (and the demo screen) something changed.
  void _changed() {
    WearMediaController.instance.updateState(state);
    notifyListeners();
  }

  void _playQueue(List<DemoSong> songs, int start) {
    _queue = songs;
    _index = start;
    _queueVersion++;
    _position = Duration.zero;
    _playing = true;
    _changed();
  }

  @override
  void dispose() {
    _ticker.cancel();
    super.dispose();
  }

  // ---- Transport ----

  @override
  Future<void> togglePlayPause() async {
    _playing = !_playing;
    _changed();
  }

  @override
  Future<void> skipToNext() async {
    _position = current.duration;
    _advance();
  }

  @override
  Future<void> skipToPrevious() async {
    if (_position > const Duration(seconds: 3) || _index == 0) {
      _position = Duration.zero;
    } else {
      _index--;
      _position = Duration.zero;
    }
    _changed();
  }

  @override
  Future<void> seekTo(Duration position) async {
    _position = position;
    _changed();
  }

  @override
  Future<void> skipToQueueItem(int index) async {
    _index = index.clamp(0, _queue.length - 1);
    _position = Duration.zero;
    _playing = true;
    _changed();
  }

  @override
  Future<List<WearQueueItem>> getQueueItems(int start, int count) async => [
        for (final song in _queue.skip(start).take(count))
          WearQueueItem(title: song.title, artist: song.artist, artworkUri: song.albumId),
      ];

  @override
  Future<void> toggleFavorite() async {
    if (!_favorites.remove(current.id)) _favorites.add(current.id);
    _changed();
  }

  @override
  Future<void> toggleShuffle() async {
    _shuffle = !_shuffle;
    final playing = current;
    _queue = _shuffle ? (List.of(_songs)..shuffle()) : List.of(_songs);
    _index = _queue.indexOf(playing);
    _queueVersion++;
    _changed();
  }

  @override
  Future<void> cycleRepeatMode() async {
    _repeat = switch (_repeat) {
      WearRepeatMode.off => WearRepeatMode.all,
      WearRepeatMode.all => WearRepeatMode.one,
      WearRepeatMode.one => WearRepeatMode.off,
    };
    _changed();
  }

  @override
  Future<void> setSleepTimer(Duration? duration) async {
    _sleepAt = duration == null ? null : DateTime.now().add(duration);
    _changed();
  }

  // ---- Lyrics ----

  @override
  Future<WearLyrics?> getLyrics(String mediaId) async {
    final song = _songs.firstWhere((s) => s.id == mediaId, orElse: () => _songs.first);
    // A line every five seconds, so they visibly follow the song.
    return WearLyrics.synced([
      for (var i = 0; i * 5 < song.duration.inSeconds; i++)
        WearLyricLine(Duration(seconds: i * 5), '${song.title}, line ${i + 1}'),
    ]);
  }

  // ---- Library ----

  WearMediaItem _songItem(DemoSong song) => WearMediaItem(
        id: 'song:${song.id}',
        title: song.title,
        subtitle: song.artist,
        artist: song.artist,
        kind: WearItemKind.song,
        artworkUri: song.albumId,
        duration: song.duration,
      );

  WearMediaItem _albumItem(DemoSong song) => WearMediaItem(
        id: song.albumId,
        title: song.album,
        subtitle: song.artist,
        kind: WearItemKind.album,
        artworkUri: song.albumId,
      );

  WearMediaItem _artistItem(DemoSong song) => WearMediaItem(
        id: song.artistId,
        title: song.artist,
        kind: WearItemKind.artist,
        artworkUri: song.artistId,
      );

  Iterable<DemoSong> _distinct(String Function(DemoSong) key) {
    final seen = <String>{};
    return _songs.where((s) => seen.add(key(s)));
  }

  List<DemoSong> _songsIn(String folderId) => switch (folderId) {
        'songs' => _songs,
        'favorites' => _songs.where((s) => _favorites.contains(s.id)).toList(),
        'playlist' => _songs.where((s) => _playlist.contains(s.id)).toList(),
        _ => _songs.where((s) => s.albumId == folderId || s.artistId == folderId).toList(),
      };

  @override
  Future<List<WearHomeSection>> getHome() async => [
        WearHomeSection(items: [_songItem(_songs[0]), _albumItem(_songs[2])]),
        WearHomeSection(title: 'Your artists', items: [for (final s in _distinct((s) => s.artist)) _artistItem(s)]),
        WearHomeSection(title: 'Albums', items: [for (final s in _distinct((s) => s.album)) _albumItem(s)]),
      ];

  @override
  Future<WearFolderPage> browse(String folderId, int page, int pageSize) async {
    final List<WearMediaItem> all;
    String? art;
    switch (folderId) {
      case 'root':
        all = const [
          WearMediaItem(id: 'songs', title: 'Songs', kind: WearItemKind.folder),
          WearMediaItem(id: 'albums', title: 'Albums', kind: WearItemKind.folder),
          WearMediaItem(id: 'artists', title: 'Artists', kind: WearItemKind.folder),
          WearMediaItem(id: 'favorites', title: 'Favorites', kind: WearItemKind.playlist),
        ];
      case 'albums':
        all = [for (final s in _distinct((s) => s.album)) _albumItem(s)];
      case 'artists':
        all = [for (final s in _distinct((s) => s.artist)) _artistItem(s)];
      default:
        all = [for (final s in _songsIn(folderId)) _songItem(s)];
        art = folderId.startsWith('album:') || folderId.startsWith('artist:') ? folderId : null;
    }
    return WearFolderPage(
      items: all.skip(page * pageSize).take(pageSize).toList(),
      total: all.length,
      artworkUri: art,
    );
  }

  @override
  Future<List<WearMediaItem>> search(String query) async {
    final q = query.toLowerCase();
    return [
      for (final s in _distinct((s) => s.artist).where((s) => s.artist.toLowerCase().contains(q))) _artistItem(s),
      for (final s in _distinct((s) => s.album).where((s) => s.album.toLowerCase().contains(q))) _albumItem(s),
      for (final s in _songs.where((s) => s.title.toLowerCase().contains(q))) _songItem(s),
    ];
  }

  @override
  Future<void> playItem(String itemId) async {
    final id = itemId.substring('song:'.length);
    final index = _songs.indexWhere((s) => s.id == id);
    if (index >= 0) _playQueue(List.of(_songs), index);
  }

  @override
  Future<void> playFolder(String folderId, {required bool shuffle}) async {
    final songs = List.of(_songsIn(folderId));
    if (songs.isEmpty) return;
    if (shuffle) songs.shuffle();
    _shuffle = shuffle;
    _playQueue(songs, 0);
  }

  @override
  Future<List<WearMediaItem>> getPlaylists() async => const [
        WearMediaItem(id: 'playlist', title: 'My playlist', kind: WearItemKind.playlist),
      ];

  @override
  Future<void> addToPlaylist(String playlistId) async {
    if (!_playlist.contains(current.id)) _playlist.add(current.id);
  }

  // ---- Artwork: a colored square with the album's (or artist's) initial ----

  final _artCache = <String, Uint8List>{};

  @override
  Future<Uint8List?> loadArtwork(String artworkUri) async {
    final cached = _artCache[artworkUri];
    if (cached != null) return cached;
    final name = artworkUri.substring(artworkUri.indexOf(':') + 1);
    final song = _songs.firstWhere((s) => s.album == name || s.artist == name, orElse: () => _songs.first);
    final bytes = await _paintArtwork(name, song.color);
    if (bytes != null) _artCache[artworkUri] = bytes;
    return bytes;
  }

  static Future<Uint8List?> _paintArtwork(String name, Color color) async {
    const size = 400.0;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    final hsl = HSLColor.fromColor(color);
    canvas.drawRect(
      const Rect.fromLTWH(0, 0, size, size),
      Paint()
        ..shader = ui.Gradient.linear(
          Offset.zero,
          const Offset(size, size),
          [color, hsl.withLightness(max(0.15, hsl.lightness - 0.35)).toColor()],
        ),
    );
    final text = TextPainter(
      text: TextSpan(
        text: name.characters.first.toUpperCase(),
        style: const TextStyle(color: Colors.white, fontSize: 220, fontWeight: FontWeight.w800),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    text.paint(canvas, Offset((size - text.width) / 2, (size - text.height) / 2));
    final image = await recorder.endRecording().toImage(size.toInt(), size.toInt());
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    return data?.buffer.asUint8List();
  }
}
