# Google Translate

`process_text` is the text-selection "Translate" action: `Intent.ACTION_PROCESS_TEXT` with type `text/plain`,
`EXTRA_PROCESS_TEXT` and `EXTRA_PROCESS_TEXT_READONLY`, pinned to `com.google.android.apps.translate`. Google
presented it with Translate as the example (<https://android-developers.googleblog.com/2015/10/in-app-translations-in-android.html>).

Known limitations: the target language can't be chosen. A `translate.google.com/m/translate?sl=…&tl=…&q=…` link
pinned to the app is reported to work in 2016-2017 forum posts only, so it is left out until someone tests it on a
current version.
