# WhatsApp

* `send_message` and `open_chat` are aliases of the built-in `whatsapp_send` and `whatsapp_open_chat` tools, with
  their scopes, approval (the prompt shows the resolved name and number) and audit entries.
* `draft_message` uses WhatsApp's documented click-to-chat link, `https://wa.me/<number>?text=<text>`
  ([WhatsApp Help: How to use click to chat](https://faq.whatsapp.com/5913398998672934)).
* `share_text` is `ACTION_SEND` with `text/plain`, pinned to WhatsApp, which opens its chat picker.

Both packages are listed in the `wa.me` app-links file. On an emulator, `clients/android/fixtures/fakewhatsapp`
(package `com.whatsapp`) handles `wa.me` links and `ACTION_SEND`, so these actions can be tested there.

Not tested on a device yet (`status: experimental`).
