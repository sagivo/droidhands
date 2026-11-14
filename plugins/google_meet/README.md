# Google Meet

`open_meeting` opens `https://meet.google.com/<code>`. The current Meet app is the former Duo app,
`com.google.android.apps.tachyon`, which `meet.google.com` lists in its Android app-links file. The original
Meet app (`com.google.android.apps.meetings`) is deprecated and left out.

Meet shows its join screen (camera preview, "Join now" or "Ask to join") before anyone enters the meeting, so
the action is `action` and `completes: false`. If a device ever joins directly from the link, raise the risk
to `sensitive`.

Not tested on a device yet (`status: experimental`).
