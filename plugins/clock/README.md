# Clock

* `set_alarm` and `set_timer` run DroidHands's built-in `alarm_set` and `timer_set` tools, with their scope
  (`device`), approval rules and audit entries. Those tools hand the alarm to the phone's default clock app.
* `show_alarms` and `show_timers` are `AlarmClock.ACTION_SHOW_ALARMS` (Android 4.4+) and `ACTION_SHOW_TIMERS`
  (Android 8+). AOSP DeskClock, which Google Clock is based on, handles both in `HandleApiCalls`, an activity that
  needs the `com.android.alarm.permission.SET_ALARM` permission DroidHands already holds
  (<https://github.com/LineageOS/android_packages_apps_DeskClock/blob/lineage-24.0/AndroidManifest.xml>).

Known limitations:

* Plugin parameters can't be lists, so `set_alarm` has no `days`: repeating alarms need `alarm_set` directly.
* Only Google Clock and AOSP-based clock apps are listed (`com.android.deskclock` is also used by Xiaomi). Other
  makers' clock apps (Samsung's, for example) still work with `alarm_set` and `timer_set`.
