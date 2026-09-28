import 'dart:typed_data';

import 'models.dart';

/// Your app's side of the watch: it runs what the watch asks for.
///
/// Only the transport controls are required. Everything else has a do-nothing default; turn a
/// feature on in [WearCapabilities] once you implement what it needs.
abstract class WearMediaHandler {
  const WearMediaHandler();

  Future<void> togglePlayPause();

  Future<void> skipToNext();

  /// Like a player's "previous" button: restarts the track, or goes back when it just began.
  Future<void> skipToPrevious();

  Future<void> seekTo(Duration position);

  /// Plays the queue entry at [index] (as counted in [WearPlaybackState.queueIndex]).
  Future<void> skipToQueueItem(int index) async {}

  /// [count] queue entries starting at [start]. Needs [WearCapabilities.queue].
  Future<List<WearQueueItem>> getQueueItems(int start, int count) async => const [];

  Future<void> toggleFavorite() async {}

  Future<void> toggleShuffle() async {}

  /// off → all → one → off.
  Future<void> cycleRepeatMode() async {}

  /// null cancels it. Needs [WearCapabilities.sleepTimer].
  Future<void> setSleepTimer(Duration? duration) async {}

  /// Lyrics for [mediaId], or null when there are none. Needs [WearCapabilities.lyrics].
  Future<WearLyrics?> getLyrics(String mediaId) async => null;

  /// The watch's home page. Needs [WearCapabilities.library].
  Future<List<WearHomeSection>> getHome() async => const [];

  /// A page of a folder. [folderId] is `"root"` for the library's top level, or the id of a
  /// folder item. Needs [WearCapabilities.library].
  Future<WearFolderPage> browse(String folderId, int page, int pageSize) async =>
      const WearFolderPage(items: [], total: 0);

  /// Needs [WearCapabilities.search].
  Future<List<WearMediaItem>> search(String query) async => const [];

  /// Plays a song picked on the watch (from a folder, the home page or search), ideally with the
  /// rest of where it came from as the queue.
  Future<void> playItem(String itemId) async {}

  /// Plays a whole folder, from the start or shuffled.
  Future<void> playFolder(String folderId, {required bool shuffle}) async {}

  /// Playlists the playing track can be added to. Needs [WearCapabilities.playlists].
  Future<List<WearMediaItem>> getPlaylists() async => const [];

  Future<void> addToPlaylist(String playlistId) async {}

  /// Image bytes (any common format and size) for an artwork key your app handed out. The
  /// plugin shrinks them before sending them to the watch.
  Future<Uint8List?> loadArtwork(String artworkUri) async => null;

  /// Routes playback to an output picked on the watch ([deviceId] is an Android
  /// `AudioDeviceInfo.id`; null means "let the system choose"). Return false if your player
  /// can't, and the phone's own output switcher opens instead.
  Future<bool> selectAudioDevice(int? deviceId) async => false;
}
