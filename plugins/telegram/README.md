# Telegram

The links are Telegram's documented deep links ([core.telegram.org/api/links](https://core.telegram.org/api/links)):

| Action | Link | Notes |
|---|---|---|
| `open_chat` | `tg://resolve?domain=<username>` | "Public username links" |
| `draft_message` | `tg://resolve?domain=<username>&text=<draft>` | The official Android app reads `text` and puts it in the message box for chats with users and bots only (`LaunchActivity.java`, `start_text`). |
| `open_chat_by_phone` | `tg://resolve?phone=<number>` | "Phone number links"; the Android app strips a leading `+`. Resolved with `contacts.resolvePhone`, so it fails for numbers whose owner hides them. |
| `share_link` | `tg://msg_url?url=<url>&text=<text>` | "Share links": opens the chat picker. |

`android.packages` lists the official builds only (Google Play, the APK from telegram.org and the beta),
which come from the same source code. Telegram X and third-party forks are left out until someone checks
that they handle these links the same way.

Not tested on a device yet (`status: experimental`).
