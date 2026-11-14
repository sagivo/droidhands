# Spotify

* `play` sends the standard `MEDIA_PLAY_FROM_SEARCH` intent with an unstructured query
  (<https://developer.android.com/guide/components/intents-common>, "Play music based on a search query").
* `search` opens a `spotify:search:` URI.
* `open_item` opens `https://open.spotify.com/<type>/<id>`, the content link Spotify's Android docs use
  (<https://developer.spotify.com/documentation/android/tutorials/content-linking>); the app declares these paths as
  verified app links. Spotify IDs are the 22-character base-62 codes of Spotify URIs.

Known limitation: opening an item shows its page; whether playback starts depends on the Spotify version.
