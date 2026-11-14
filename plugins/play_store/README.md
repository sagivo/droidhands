# Google Play Store

* `app_page` and `search` use the link formats in "Linking to Google Play"
  (<https://developer.android.com/distribute/marketing-tools/linking-to-google-play>), opened in the Play Store app
  as the page recommends. They use `https://play.google.com/…` rather than `market://` because that is what the
  current page documents; the Play Store handles both.
* `subscriptions` is the subscription center link from the Play Billing docs
  (<https://developer.android.com/google/play/billing/subscriptions>).
