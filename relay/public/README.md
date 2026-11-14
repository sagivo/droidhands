# relay/public

Put the Android app here as **`phonebridge.apk`** and the relay's phone setup page (`/setup`)
shows an **Install PhoneBridge** button that downloads it from `/download/phonebridge.apk`. The
file is picked up on the next page load; no restart needed. Without it, the page just tells the
user to get the app from you.

```sh
cp clients/android/app/build/outputs/apk/release/app-release.apk relay/public/phonebridge.apk
```

APK files are git-ignored. The Docker image copies this directory at build time; set `PUBLIC_DIR`
to serve it from somewhere else.
