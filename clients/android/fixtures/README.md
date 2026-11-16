# PhoneBridge E2E test fixtures

> **These are TEST FIXTURES. Install them only on emulators or dedicated test devices.**
> `fakewhatsapp` **impersonates the real WhatsApp package name (`com.whatsapp`)**. Installing it on a
> real phone would fail if WhatsApp is installed (signature mismatch). If WhatsApp is not installed,
> it would take WhatsApp's identity and links. Never ship or distribute these APKs.

Real WhatsApp and Gmail can't run on a stock emulator (no phone-number registration, no Google
account). These two small apps copy only what PhoneBridge's tools depend on: package name, deep
links, view ids, and notification shape (MessagingStyle, RemoteInput replies, semantic actions,
group summary). The apps record what they observe, so a test can check what PhoneBridge actually did.

| Module | Package / label | What it mimics |
|---|---|---|
| `:fixtures:fakewhatsapp` | `com.whatsapp` / "WhatsApp" | Chat deep links declared in its manifest (WhatsApp's `draft_message`, discovered `open_link`) and the Send button (phone use: `screen_observe`, `ui_tap`), message notifications with inline Reply and Mark as read (`notification_reply`, `notification_action`) |
| `:fixtures:fakemail` | `dev.phonebridge.fixture.mail` / "Fixture Mail" | Inbox, email screen and compose screen (`mailto:`, `ACTION_SEND`), plus email notifications with inline Reply (or Gmail-like Reply that opens the app) and Archive |

Both apps use classic Android Views only (framework `Activity` and widgets, `androidx.core` for
NotificationCompat), so the accessibility tree looks like a real View-based app with resource ids.

## Build and install

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd clients/android
./gradlew :fixtures:fakewhatsapp:assembleDebug :fixtures:fakemail:assembleDebug
./gradlew :fixtures:fakewhatsapp:installDebug :fixtures:fakemail:installDebug    # emulator must be running
# or: adb install -r fixtures/fakewhatsapp/build/outputs/apk/debug/fakewhatsapp-debug.apk
#     adb install -r fixtures/fakemail/build/outputs/apk/debug/fakemail-debug.apk

# Notifications (Android 13+). Without this, nothing is shown and events report posted=false.
adb shell pm grant com.whatsapp android.permission.POST_NOTIFICATIONS
adb shell pm grant dev.phonebridge.fixture.mail android.permission.POST_NOTIFICATIONS

# Launch each app once after install (or after pm clear / force-stop). This takes it out of the
# "stopped" state so test broadcasts reach it. Alternatively add --include-stopped-packages to am broadcast.
adb shell am start -n com.whatsapp/.MainActivity
adb shell am start -n dev.phonebridge.fixture.mail/.InboxActivity

# Optional, Android 12+: let *implicit* https links (no package set) open the fake app instead of the browser.
# This isn't needed when the link intent targets the package (setPackage("com.whatsapp"), or `am start ... com.whatsapp`).
adb shell pm set-app-links-user-selection --user 0 --package com.whatsapp true all
adb shell pm get-app-links --user 0 com.whatsapp
```

**adb quoting pitfall:** `adb shell` joins its arguments with spaces and the device shell splits them
again. Your local quotes are lost, and `&`, `?` and spaces break the command. Wrap the whole device
command in double quotes and put values in single quotes, as the recipes below do.

## Observing events

Each app records every event in two places:

1. **logcat**: tag `FIXTURE`, one line per event: `FIXTURE <EVENT> key=value key=value ...`. Values
   are URL-encoded (UTF-8, space = `%20`), so each line splits cleanly on spaces. Fields that are
   null are omitted. Very long values can be truncated by logcat's line limit; the file has them in full.
2. **`files/events.jsonl`** in the app's data dir (append-only, one JSON object per line):
   `{"ts":1731500000000,"event":"SENT","phone":"972541234567","text":"I'll be 10 minutes late"}`.

```sh
adb logcat -c                                   # clear before a test step
adb logcat -s FIXTURE                           # follow live (both apps share the tag)
adb logcat -d -s FIXTURE                        # dump and exit
adb logcat -d -s FIXTURE --pid=$(adb shell pidof com.whatsapp)   # one app only (while it runs)

adb shell run-as com.whatsapp cat files/events.jsonl
adb shell run-as dev.phonebridge.fixture.mail cat files/events.jsonl
adb shell run-as com.whatsapp rm -f files/events.jsonl            # truncate the event log only

# decode logcat lines (values never contain a raw space, so the LAST "FIXTURE " starts the message)
adb logcat -d -s FIXTURE | python3 -c '
import sys, urllib.parse as u
for line in sys.stdin:
    if "FIXTURE " not in line: continue
    event, *pairs = line.rsplit("FIXTURE ", 1)[1].split()
    print(event, {k: u.unquote(v) for k, v in (p.split("=", 1) for p in pairs)})'
```

`run-as` works because these are debug (debuggable) builds.

## Reset

```sh
adb shell pm clear com.whatsapp                  # kills the app, deletes events.jsonl, revokes runtime permissions
adb shell pm clear dev.phonebridge.fixture.mail  # also resets the inbox to its 3 seed emails
# then re-grant POST_NOTIFICATIONS and launch once (see above)
```

## Fake WhatsApp (`com.whatsapp`)

### Screens and view ids (`com.whatsapp:id/<name>`)

| Screen | Views |
|---|---|
| Chats home (`MainActivity`, launcher; also links without a phone) | `home_title` ("Chats"), `chat_list` > `contact_row` > `conversations_row_contact_name`, `single_msg_tv` |
| Chat (`ConversationActivity`, exported, singleTask) | `conversation_contact_name` ("+<phone>"), `conversation_scroll` > `conversation_list` > `message_text` (one per sent message), `entry` (EditText, hint "Message", prefilled with `text`), `send` (ImageButton, content description "Send") |

Links handled by `ConversationActivity` (VIEW, categories DEFAULT and BROWSABLE):
`https://api.whatsapp.com/send?phone=<digits>&text=<msg>` (also `/send/`),
`https://wa.me/<digits>?text=<msg>`, and `whatsapp://send?phone=<digits>&text=<msg>`. It also handles
`ACTION_SEND` (`text/plain`) with a `jid` extra (`<digits>@s.whatsapp.net`) and `EXTRA_TEXT`.
Non-digits in the phone are dropped. A new link while a chat is open replaces it (`onNewIntent`): the
message list is cleared, the header is updated and the entry is prefilled again.

| Input | Behavior | Event |
|---|---|---|
| phone present | chat screen, `text` prefilled | `CHAT_OPENED phone text uri` |
| tap `send` with non-blank `entry` | bubble added, entry cleared | `SENT phone text` |
| phone ends in `0000` | Chats home + dialog "The phone number +<phone> isn't on WhatsApp." with buttons "OK" and "Invite" | `NOT_ON_WHATSAPP phone text uri`; `INVITE phone` if Invite is tapped |
| no phone | Chats home (no `entry` / `send`) | `HOME_OPENED source=link uri` |
| launcher | Chats home; asks for POST_NOTIFICATIONS if not granted | `HOME_OPENED source=launcher`, `NOTIFICATION_PERMISSION granted` |

```sh
# Open a chat with prefilled text (what draft_message / open_link do)
adb shell "am start -a android.intent.action.VIEW -d 'https://api.whatsapp.com/send?phone=972541234567&text=Hello%20there' com.whatsapp"
adb shell "am start -a android.intent.action.VIEW -d 'https://wa.me/972541234567?text=Hi' com.whatsapp"
adb shell "am start -a android.intent.action.VIEW -d 'whatsapp://send?phone=972541234567&text=Hi'"
adb shell "am start -n com.whatsapp/.ConversationActivity -a android.intent.action.SEND -t text/plain --es jid 972541234567@s.whatsapp.net --es android.intent.extra.TEXT 'Hi'"
# Failure paths
adb shell "am start -a android.intent.action.VIEW -d 'https://api.whatsapp.com/send?phone=15550000&text=Hi' com.whatsapp"   # not on WhatsApp
adb shell "am start -a android.intent.action.VIEW -d 'https://api.whatsapp.com/send?text=Hi' com.whatsapp"                 # no phone -> Chats
# Inspect the UI tree like PhoneBridge's screen_read / screen_observe would
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml
```

### Incoming message notifications

```sh
adb shell "am broadcast -n com.whatsapp/.IncomingMessageReceiver -a com.whatsapp.fixture.INCOMING --es from Tania --es text 'Are you coming?'"
# with the sender's number (tapping the notification opens that chat)
adb shell "am broadcast -n com.whatsapp/.IncomingMessageReceiver -a com.whatsapp.fixture.INCOMING --es from 'Tania Cohen' --es text 'Are you coming?' --es phone 972541234567"
# from an interactive `adb shell` session the plain form works as-is:
#   am broadcast -n com.whatsapp/.IncomingMessageReceiver -a com.whatsapp.fixture.INCOMING --es from Tania --es text "Are you coming?"
```

The broadcast is explicit (`-n`), so Android 8+ delivers it to the manifest receiver. Each
broadcast records `INCOMING from text phone notification_id posted`. `posted=false` means
notifications are not allowed. The notification it posts or updates:

* channel `messages` (IMPORTANCE_HIGH), category `msg`, small icon `stat_notify_chat`;
* `NotificationCompat.MessagingStyle` with user Person "You" (key `me`), sender Person `from`,
  conversation title = `from`, not a group conversation. Messages from the same sender are appended
  to the same notification. Its id is stable: `(from.trim().lowercase().hashCode() & 0x3fffffff) + 1000`;
* group `chats`, plus a group summary (id 1, title "WhatsApp", InboxStyle "N chats"), like real
  WhatsApp. `notifications_list` sees the summary too;
* actions, both explicit broadcast PendingIntents to non-exported receivers:
  * **Reply**: RemoteInput key `reply_text`, free-form input, `SEMANTIC_ACTION_REPLY`, **FLAG_MUTABLE**.
    It records `REPLY from text phone source` (`error=no_remote_input` if no RemoteInput results
    came with it). The reply is then appended to the notification as a message from "You", posted
    with onlyAlertOnce;
  * **Mark as read**: `SEMANTIC_ACTION_MARK_AS_READ`, immutable. It records `MARK_READ from phone`
    and cancels the notification (and the summary when no chats are left).

## Fake mail (`dev.phonebridge.fixture.mail`)

### Screens and view ids (`dev.phonebridge.fixture.mail:id/<name>`)

| Screen | Views |
|---|---|
| Inbox (`InboxActivity`, launcher) | `inbox_title`, `compose` (ImageButton "Compose"), `email_list` > `email_row` (clickable) > `email_from`, `email_subject`, `email_snippet` |
| Email (`EmailActivity`) | `email_subject`, `email_from`, `email_body`, `reply` (Button "Reply"), `archive` (Button "Archive") |
| Compose (`ComposeActivity`, exported) | `close` ("Navigate up"), `send` (ImageButton top-right, content description "Send"), `to` (hint "To"), `cc` ("Cc"), `bcc` ("Bcc"), `subject` ("Subject"), `body` ("Compose email") |

The inbox lives in `files/inbox.json` (newest first). The seed emails are `seed-1` dana@example.com
"Lunch on Thursday?", `seed-2` tom@example.org "Project update" and `seed-3`
billing@example-shop.com "Your receipt #10432".

`ComposeActivity` prefills from:
* `ACTION_SENDTO` or `ACTION_VIEW` with `mailto:a@x.com,b@x.com?to=..&cc=..&bcc=..&subject=..&body=..`
  (RFC 6068: `%20` is a space and `+` stays `+`);
* `ACTION_SEND` / `ACTION_SEND_MULTIPLE` (`message/rfc822` or `text/plain`) with `EXTRA_EMAIL`,
  `EXTRA_CC`, `EXTRA_BCC` (string array, list, or comma-separated string), `EXTRA_SUBJECT` and
  `EXTRA_TEXT`. These extras also fill whatever a `mailto:` URI leaves empty;
* internal reply intents: `to` = sender, `subject` = "Re: <subject>", extras `in_reply_to`
  (email id) and `source` (`app` or `notification`).

| Action | Event |
|---|---|
| compose opened | `COMPOSE_OPENED action to cc bcc? subject body in_reply_to? source?` |
| tap `send` | `EMAIL_SENT to cc bcc? subject body in_reply_to?`, then the screen closes |
| tap `send` with empty To | Toast "Add at least one recipient.", nothing sent, `SEND_BLOCKED reason=empty_to` |
| leave compose without sending | `COMPOSE_CLOSED in_reply_to?` |
| open an email | `EMAIL_OPENED id from subject` |
| Archive button | `ARCHIVE id from subject source=app` (email removed from the inbox) |

```sh
adb shell "am start -a android.intent.action.SENDTO -d 'mailto:bob@example.com?cc=carol@example.com&subject=Hi%20Bob&body=See%20you%20at%205' dev.phonebridge.fixture.mail"
adb shell "am start -a android.intent.action.VIEW -d 'mailto:bob@example.com?subject=Hi' dev.phonebridge.fixture.mail"
adb shell "am start -a android.intent.action.SEND -t message/rfc822 --esa android.intent.extra.EMAIL bob@example.com,carol@example.com --esa android.intent.extra.CC dave@example.com --es android.intent.extra.SUBJECT 'Status' --es android.intent.extra.TEXT 'All good' dev.phonebridge.fixture.mail"
```

### Incoming email notifications

```sh
# Inline-reply variant (default): actions Reply (RemoteInput), Reply in app, Archive
adb shell "am broadcast -n dev.phonebridge.fixture.mail/.IncomingEmailReceiver -a dev.phonebridge.fixture.mail.INCOMING --es from dana@example.com --es subject 'Dinner tonight?' --es body 'Are you free at 8?'"
# Gmail-like variant: actions Archive, Reply (NO RemoteInput; opens the compose screen)
adb shell "am broadcast -n dev.phonebridge.fixture.mail/.IncomingEmailReceiver -a dev.phonebridge.fixture.mail.INCOMING --es from dana@example.com --es subject 'Dinner tonight?' --es body 'Are you free at 8?' --ez inline false"
```

Each broadcast adds the email to the inbox and records
`EMAIL_RECEIVED id from subject body inline notification_id posted`. The notification is on channel
`mail` (IMPORTANCE_HIGH), category `email`, BigTextStyle, title = `from`, text = `subject`, sub-text
`me@fixture.test`, with one notification per email. Tapping it opens the email. Actions:

* **Reply** (inline variant): RemoteInput key `reply_body`, `SEMANTIC_ACTION_REPLY`, explicit
  **FLAG_MUTABLE** broadcast. It records `EMAIL_REPLY to=<from> subject="Re: <subject>" body in_reply_to=<id>`
  and cancels the notification;
* **Reply in app** (inline variant), or **Reply** (Gmail-like variant): no RemoteInput. An immutable
  explicit activity PendingIntent opens the prefilled compose screen (`COMPOSE_OPENED source=notification`)
  and the notification is cancelled. Sending then records `EMAIL_SENT ... in_reply_to=<id>`;
* **Archive**: `SEMANTIC_ACTION_ARCHIVE`, immutable broadcast. It records
  `ARCHIVE id from subject source=notification`, removes the email from the inbox and cancels the notification.

## Event reference

| App | Events |
|---|---|
| fakewhatsapp | `HOME_OPENED`, `NOTIFICATION_PERMISSION`, `CHAT_OPENED`, `SENT`, `NOT_ON_WHATSAPP`, `INVITE`, `INCOMING`, `REPLY`, `MARK_READ` |
| fakemail | `NOTIFICATION_PERMISSION`, `EMAIL_OPENED`, `ARCHIVE`, `COMPOSE_OPENED`, `EMAIL_SENT`, `SEND_BLOCKED`, `COMPOSE_CLOSED`, `EMAIL_RECEIVED`, `EMAIL_REPLY` |
