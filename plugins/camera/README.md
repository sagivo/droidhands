# Camera

Both actions are Android's documented "open the camera" intents
(<https://developer.android.com/guide/components/intents-common>, "Start a camera app in still image mode" and
"... in video mode"): `android.media.action.STILL_IMAGE_CAMERA` and `android.media.action.VIDEO_CAMERA`. They only
open the camera. The plugin does not use `IMAGE_CAPTURE`, so DroidHands never receives a photo or video.

Package names come from the OEM camera list of the Android system app database
(<https://sysapps.bayton.org/bundles/oem-camera-apps>). The plugin applies to the first one installed, so the
generic cameras (`com.mediatek.camera`, `org.codeaurora.snapcam`, `com.android.camera2`) come last: some phones
ship one of them next to the maker's camera.

Known limitations: front camera, timer and photo modes can't be chosen. The extras some apps read for that
(`android.intent.extras.CAMERA_FACING` and similar) are undocumented and inconsistent, so they are left out.
