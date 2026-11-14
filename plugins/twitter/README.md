# X (Twitter)

* `compose` opens X's web intent, `https://twitter.com/intent/tweet?text=…` (X for Websites, "Web Intent"),
  which the X app opens in its composer. Nothing is published until Post is tapped.
* `open_profile` opens `https://twitter.com/<handle>`.

`twitter.com` and `x.com` both list `com.twitter.android` in their Android app-links files. The recipes use
`twitter.com` links because older versions of the app may not know the `x.com` paths.

Not tested on a device yet (`status: experimental`).
