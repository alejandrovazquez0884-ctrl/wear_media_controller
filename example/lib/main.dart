import 'package:flutter/material.dart';
import 'package:wear_media_controller/wear_media_controller.dart';

import 'fake_player.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  final player = FakePlayer();
  WearMediaController.instance
    ..handler = player
    ..setCapabilities(const WearCapabilities(
      queue: true,
      lyrics: true,
      library: true,
      search: true,
      favorite: true,
      sleepTimer: true,
      playlists: true,
    ))
    ..updateState(player.state);
  runApp(DemoApp(player: player));
}

class DemoApp extends StatelessWidget {
  const DemoApp({super.key, required this.player});

  final FakePlayer player;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Wear media controller demo',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, brightness: Brightness.dark),
      home: Scaffold(
        appBar: AppBar(title: const Text('Wear media controller')),
        body: ListenableBuilder(
          listenable: player,
          builder: (context, _) {
            final song = player.current;
            final total = song.duration.inMilliseconds;
            return Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'A pretend player (no sound) to try the watch app. Open the controller on '
                    'your watch; everything there drives this screen and the other way around.',
                  ),
                  const Spacer(),
                  Text(song.title, style: Theme.of(context).textTheme.headlineSmall, textAlign: TextAlign.center),
                  Text('${song.artist} · ${song.album}', textAlign: TextAlign.center),
                  const SizedBox(height: 16),
                  LinearProgressIndicator(value: total == 0 ? 0 : player.position.inMilliseconds / total),
                  const SizedBox(height: 16),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      IconButton(icon: const Icon(Icons.skip_previous), iconSize: 40, onPressed: player.skipToPrevious),
                      IconButton(
                        icon: Icon(player.isPlaying ? Icons.pause_circle : Icons.play_circle),
                        iconSize: 64,
                        onPressed: player.togglePlayPause,
                      ),
                      IconButton(icon: const Icon(Icons.skip_next), iconSize: 40, onPressed: player.skipToNext),
                    ],
                  ),
                  const Spacer(),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}
