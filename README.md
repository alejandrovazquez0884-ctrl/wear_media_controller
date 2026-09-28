# wear_media_controller

A Wear OS controller for any Flutter music app, in the style of Spotify's watch app: now playing
with artwork, a seek ring, synced lyrics, the queue, the library and search, all driven from the
watch.

The system media controls on Wear OS only offer play/pause/skip. This plugin lets your app give
the watch much more, without the watch app knowing anything about your app: your app answers a
small set of questions (what's playing, what's in this folder, what are the lyrics...) and runs
the commands it receives.

It has two parts:

- **The Flutter plugin** (this package), in your phone app. You implement `WearMediaHandler` and
  keep the watch updated with `WearMediaController.instance.updateState(...)`.
- **The watch app** ([`wear_app/`](wear_app)), a Kotlin + Compose Wear OS app you build with your
  own application id, name and icon.

## What the watch shows

- **Player**: artwork background, title and artist (tinted with the cover's color), previous /
  play-pause / next with a progress ring, audio output picker, queue and favorite buttons. The
  bezel changes the phone's volume; long-press play and drag around to seek.
- **Lyrics**: synced lines that follow the song (tap a line to jump), or plain text.
- **Cover**: full-screen artwork; tap to play/pause, double-tap right/left to skip/go back.
- **Queue**: upcoming tracks with thumbnails, shuffle, repeat and a sleep timer.
- **Home and library**: your sections, folders, album/artist pages and search (keyboard or voice).
- It opens by itself when your app starts playing, instead of the system media controls.

Anything your app doesn't support is hidden: declare what you do in `WearCapabilities`.

## Phone side

```yaml
dependencies:
  wear_media_controller:
    git:
      url: https://github.com/<you>/wear_media_controller
```

```dart
import 'package:wear_media_controller/wear_media_controller.dart';

class MyWearHandler extends WearMediaHandler {
  @override
  Future<void> togglePlayPause() => player.playOrPause();

  @override
  Future<void> skipToNext() => player.next();

  @override
  Future<void> skipToPrevious() => player.previous();

  @override
  Future<void> seekTo(Duration position) => player.seek(position);

  // Optional: queue, lyrics, library, search, favorites, playlists, sleep timer, artwork...
  @override
  Future<Uint8List?> loadArtwork(String artworkUri) => File(artworkUri).readAsBytes();
}

void setUpWatch() {
  final watch = WearMediaController.instance;
  watch.handler = MyWearHandler();
  watch.setCapabilities(const WearCapabilities(queue: true, lyrics: true));
}

// Whenever the track, play state, position (after a seek) or queue changes:
WearMediaController.instance.updateState(WearPlaybackState(
  mediaId: track.id,
  title: track.title,
  artist: track.artist,
  artworkUri: track.coverPath, // any key; handed back to loadArtwork
  isPlaying: player.isPlaying,
  duration: track.duration,
  position: player.position,
  queueIndex: player.index,
  queueLength: player.queue.length,
  queueVersion: queueVersion, // bump when the queue's contents change
));
```

There's no need to send position updates while playing: the watch keeps time on its own and
resyncs on each update. The whole API is documented in
[`lib/src/handler.dart`](lib/src/handler.dart) and [`lib/src/models.dart`](lib/src/models.dart);
[`example/lib/fake_player.dart`](example/lib/fake_player.dart) implements every part of it.

A few conventions:

- `browse('root')` is the library's top level. Items have a `WearItemKind`: songs play when
  tapped, albums/artists/playlists/genres open a detail page, and other folders open as lists.
- A song item is shown as "playing" when its id equals `mediaId`, or ends with `:<mediaId>`.
- `albumId` / `artistId` in the state are folder ids for the watch's "go to album/artist".
- `selectAudioDevice` gets an Android `AudioDeviceInfo` id. If your player can't route to it
  (for example `ExoPlayer.setPreferredAudioDevice`), return `false` and the phone's own output
  switcher opens instead.

## Watch side

Wear OS only lets a phone app and a watch app talk when they have **the same application id and
signing key**. So each app builds its own copy of the watch app:

1. In [`wear_app/gradle.properties`](wear_app/gradle.properties), set `wearApplicationId` to your
   phone app's id, `wearAppName`, and your release keystore.
2. Replace the placeholder icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`,
   `ic_app_logo.xml` and `values/colors.xml`).
3. Build it: `cd wear_app && ./gradlew assembleRelease`.
4. Publish the APK next to your phone app (a Wear OS release in the same Play listing, or as a
   separate download for sideloading).

## Trying the example

The example is a pretend player with a made-up library (no audio), to try everything on a real
watch:

```sh
cd example && flutter build apk --release   # install on the phone
cd ../wear_app && ./gradlew assembleRelease  # install app/build/outputs/apk/release/app-release.apk on the watch
```

Both are signed with the local debug key by default, so they pair with each other.

## How it talks

See [PROTOCOL.md](PROTOCOL.md). Everything goes through the Wearable Data Layer: the state as a
DataItem (with the artwork as an Asset), and everything else as small JSON messages.

## License

MIT. The watch app bundles the Figtree and Rubik fonts (SIL Open Font License 1.1).
