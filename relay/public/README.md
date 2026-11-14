# relay/public

Put the Android app here as **`phonebridge.apk`** and the relay's phone setup page (`/setup`)
shows an **Install PhoneBridge** button that downloads it from `/download/phonebridge.apk`. The
file is picked up on the next page load; no restart needed. Without it, the page just tells the
user to get the app from you.

```sh
cp clients/android/app/build/outputs/apk/release/PhoneBridge-*.apk relay/public/phonebridge.apk
```

If the app is published on GitHub Releases, set `APK_URL` instead (for example
`https://github.com/<owner>/<repo>/releases/latest/download/PhoneBridge.apk`): the button then links
there, and `/download/phonebridge.apk` redirects to it.

APK files are git-ignored. The Docker image copies this directory at build time; set `PUBLIC_DIR`
to serve it from somewhere else.
