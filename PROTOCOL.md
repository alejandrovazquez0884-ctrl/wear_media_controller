# Watch protocol

The phone plugin and the watch app talk over the Wearable Data Layer. Both apps must share the
same application id and signing key.

- `/wear_media/state` (DataItem, phone → watch): the playback state, capabilities and artwork.
- `/wear_media/cmd` (message, watch → phone): a command string.
- `/wear_media/<topic>/request` (watch → phone) is answered by `/wear_media/<topic>` (phone →
  watch) for: `queue`, `lyrics`, `browse`, `home`, `search`, `thumb`, `outputs`, `playlists`.
