# Settings

Every action starts a public `android.provider.Settings` action
(<https://developer.android.com/reference/android/provider/Settings>), plus `Intent.ACTION_POWER_USAGE_SUMMARY` for
`battery`, pinned to `com.android.settings`. None of them changes a setting.

| Action | Intent action | Since |
|---|---|---|
| `wifi` | `android.settings.WIFI_SETTINGS` | 1 |
| `bluetooth` | `android.settings.BLUETOOTH_SETTINGS` | 1 |
| `airplane_mode` | `android.settings.AIRPLANE_MODE_SETTINGS` | 3 |
| `display` | `android.settings.DISPLAY_SETTINGS` | 1 |
| `sound` | `android.settings.SOUND_SETTINGS` | 1 |
| `battery` | `android.intent.action.POWER_USAGE_SUMMARY` | 4 |
| `battery_saver` | `android.settings.BATTERY_SAVER_SETTINGS` | 22 |
| `location` | `android.settings.LOCATION_SOURCE_SETTINGS` | 1 |
| `notifications` | `android.settings.ALL_APPS_NOTIFICATION_SETTINGS` | 33 |
| `app_notifications` | `android.settings.APP_NOTIFICATION_SETTINGS` + extra `android.provider.extra.APP_PACKAGE` | 26 |
| `app_details` | `android.settings.APPLICATION_DETAILS_SETTINGS` + data `package:<name>` | 9 |
| `storage` | `android.settings.INTERNAL_STORAGE_SETTINGS` | 3 |
| `date_time` | `android.settings.DATE_SETTINGS` | 1 |
| `data_usage` | `android.settings.DATA_USAGE_SETTINGS` | 28 |

Known limitations:

* Android warns that "a matching Activity may not exist" for most of these: phone makers may leave a screen out or
  handle it in another app (Samsung, for example, may show battery screens in Device care). Then the action fails
  and nothing opens.
* `app_notifications` and `app_details` refuse PhoneBridge's own package (`dev.phonebridge…`), so an agent can't
  open the pages that turn off PhoneBridge's notifications or force-stop it. That check is in the parameter
  `pattern`; a `package` parameter is not otherwise checked against the agent's app access.
