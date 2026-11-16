# WhatsApp

* There is no built-in "send" action: DroidHands doesn't hard-code WhatsApp's screens. Agents open the chat with
  `draft_message` and finish with phone use (`screen_observe`, `ui_tap` on Send), or reply to an incoming message
  with `notification_reply`.
* `draft_message` uses WhatsApp's documented click-to-chat link, `https://wa.me/<number>?text=<text>`
  ([WhatsApp Help: How to use click to chat](https://faq.whatsapp.com/5913398998672934)).
* `share_text` is `ACTION_SEND` with `text/plain`, pinned to WhatsApp, which opens its chat picker.

Both packages are listed in the `wa.me` app-links file. On an emulator, `clients/android/fixtures/fakewhatsapp`
(package `com.whatsapp`) handles `wa.me` links and `ACTION_SEND`, so these actions can be tested there.

Not tested on a device yet (`status: experimental`).
