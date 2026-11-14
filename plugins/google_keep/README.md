# Google Keep

`add_note` shares text to Keep with the standard `ACTION_SEND` (`text/plain`) intent: `EXTRA_TEXT` becomes the
note and `EXTRA_SUBJECT` the title (the same extras Chrome uses when sharing a page to Keep). Keep shows a small
overlay rather than the full app. Whether that overlay saves the note immediately or waits for the user to
confirm has not been checked on a device, so the action reports `completes: false` and tells agents to check
with `screen_read`.

Not tested on a device yet (`status: experimental`).
