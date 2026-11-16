# YouTube

Sources:

* A decompiled YouTube manifest (2022 build, <https://github.com/crifan/AndroidYouTubeDynamicDebug>, file
  `AndroidManifest.xml`) shows the handlers these actions rely on: `android.intent.action.SEARCH` (the results
  activity), `android.media.action.MEDIA_PLAY_FROM_SEARCH`, and `VIEW` for any path on `youtube.com`,
  `www.youtube.com`, `m.youtube.com` and `youtu.be`.
* `search` passes the text in the standard `query` extra (`SearchManager.QUERY`), as in community examples such as
  <https://www.b4x.com/android/forum/threads/solved-launch-youtube-search-with-my-string.44049/>.

Known limitations: `play_from_search` lets YouTube pick the video, and YouTube may show results or an ad first.

YouTube Music is not covered: no source confirms which intents it handles. If it declares
`MEDIA_PLAY_FROM_SEARCH`, DroidHands offers the discovered `play_from_search` action for it anyway.
