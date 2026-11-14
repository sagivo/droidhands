# Facebook

* `open_profile` opens `https://www.facebook.com/<name>`. `www.facebook.com` lists `com.facebook.katana` in its
  Android app-links file, so the app opens these links itself.
* `share_text` replaces the discovered action of the same id to warn agents that Facebook drops any shared text
  except a link: Meta's Platform Policy forbids prefilling the user's message, and the Android app has long
  ignored `EXTRA_TEXT` other than URLs (many reports on Stack Overflow since 2012).

Facebook Lite (`com.facebook.lite`) is a different app and is not covered.

Not tested on a device yet (`status: experimental`).
