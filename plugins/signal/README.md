# Signal

`open_chat` uses Signal's phone-number link, `https://signal.me/#p/+<E.164 number>`. Sources in
[Signal-Android](https://github.com/signalapp/Signal-Android):

* `AndroidManifest.xml`: `DeepLinkEntryActivity` handles `https://signal.me` (a verified app link) and `sgnl://signal.me`.
* `util/SignalMeUtil.kt`: the link must match `^(https|sgnl)://signal\.me/#p/(\+[0-9]+)$`, so the `+` must be
  literal (not `%2B`); `SignalMeUtilText_parseE164FromLink.kt` tests that form.
* `util/CommunicationActions.java`: `handleE164Link` looks the number up and opens the conversation, or shows
  "… is not a Signal user".

Sending messages has no link or intent in Signal: use `share_text` (Signal's share screen) or the screen tools.

Not tested on a device yet (`status: experimental`).
