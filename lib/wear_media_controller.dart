/// Lets a Flutter music app drive a Wear OS controller app on the user's watch: now playing,
/// queue, lyrics, library and search, over the Wearable Data Layer.
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'src/handler.dart';
import 'src/models.dart';

export 'src/handler.dart';
export 'src/models.dart';

/// Entry point. Set a [handler], declare [WearCapabilities], then keep the watch up to date with
/// [updateState].
///
/// Use it after `WidgetsFlutterBinding.ensureInitialized()` (or `runApp`).
class WearMediaController {
  WearMediaController._();

  static final WearMediaController instance = WearMediaController._();

  static const _channel = MethodChannel('wear_media_controller');

  WearMediaHandler? _handler;
  WearCapabilities _capabilities = const WearCapabilities();
  WearPlaybackState? _state;

  /// Where the watch's requests go. Nothing reaches your app until one is set.
  set handler(WearMediaHandler? handler) {
    _handler = handler;
    _channel.setMethodCallHandler(handler == null ? null : _onCall);
  }

  /// What the watch should offer; sent along with every state update.
  Future<void> setCapabilities(WearCapabilities capabilities) {
    _capabilities = capabilities;
    return _publish();
  }

  /// Sends what's playing to the watch. Call it on every change; unchanged artwork isn't resent.
  Future<void> updateState(WearPlaybackState state) {
    _state = state;
    return _publish();
  }

  /// Tells the watch nothing is playing.
  Future<void> clearState() async {
    _state = null;
    try {
      await _channel.invokeMethod('clearState');
    } on MissingPluginException {
      // Not on Android.
    }
  }

  Future<void> _publish() async {
    final state = _state;
    if (state == null) return;
    try {
      await _channel.invokeMethod('updateState', {
        ...state.toMap(),
        'capabilities': _capabilities.toMap(),
      });
    } on MissingPluginException {
      // Not on Android.
    }
  }

  Future<Object?> _onCall(MethodCall call) async {
    final handler = _handler;
    if (handler == null) return null;
    final args = (call.arguments as Map?)?.cast<String, Object?>() ?? const {};
    try {
      switch (call.method) {
        case 'requestState':
          await _publish();
        case 'togglePlayPause':
          await handler.togglePlayPause();
        case 'skipToNext':
          await handler.skipToNext();
        case 'skipToPrevious':
          await handler.skipToPrevious();
        case 'seekTo':
          await handler.seekTo(Duration(milliseconds: args['positionMs'] as int));
        case 'skipToQueueItem':
          await handler.skipToQueueItem(args['index'] as int);
        case 'toggleFavorite':
          await handler.toggleFavorite();
        case 'toggleShuffle':
          await handler.toggleShuffle();
        case 'cycleRepeatMode':
          await handler.cycleRepeatMode();
        case 'setSleepTimer':
          final minutes = args['minutes'] as int;
          await handler.setSleepTimer(minutes > 0 ? Duration(minutes: minutes) : null);
        case 'playItem':
          await handler.playItem(args['id'] as String);
        case 'playFolder':
          await handler.playFolder(args['id'] as String, shuffle: args['shuffle'] as bool);
        case 'addToPlaylist':
          await handler.addToPlaylist(args['id'] as String);
        case 'selectAudioDevice':
          return await handler.selectAudioDevice(args['deviceId'] as int?);
        case 'getQueueItems':
          final items = await handler.getQueueItems(args['start'] as int, args['count'] as int);
          return [for (final item in items) item.toMap()];
        case 'getLyrics':
          return (await handler.getLyrics(args['mediaId'] as String))?.toMap();
        case 'getHome':
          final sections = await handler.getHome();
          return [for (final section in sections) section.toMap()];
        case 'browse':
          final page = await handler.browse(
            args['id'] as String,
            args['page'] as int,
            args['pageSize'] as int,
          );
          return page.toMap();
        case 'search':
          final items = await handler.search(args['query'] as String);
          return [for (final item in items) item.toMap()];
        case 'getPlaylists':
          final items = await handler.getPlaylists();
          return [for (final item in items) item.toMap()];
        case 'loadArtwork':
          return await handler.loadArtwork(args['uri'] as String);
      }
    } catch (e, st) {
      debugPrint('wear_media_controller: ${call.method} failed: $e\n$st');
      rethrow;
    }
    return null;
  }
}
