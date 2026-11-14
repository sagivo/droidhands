# Phone

* `call` is an alias of the built-in `phone_call` tool (scope `calls`, always `sensitive`, approval with the
  resolved number, MMI/USSD codes refused).
* `open_dialer` is `ACTION_DIAL` with a `tel:` number: it fills in the dialpad and never places the call. Every
  default dialer has to handle it (`RoleManager.ROLE_DIALER`). The number pattern refuses `*` and `#`, like
  the built-in tools, so a recipe can't be used to type MMI/USSD codes.
* `recent_calls` is `ACTION_VIEW` with the MIME type `CallLog.Calls.CONTENT_TYPE` (`vnd.android.cursor.dir/calls`),
  the documented way to show the call log (see `CallLog.Calls.EXTRA_CALL_TYPE_FILTER`).

`android.packages` lists Google Phone, the AOSP dialer (emulators, some phones) and Samsung Phone. Samsung is
included because these are platform intents, but nobody has checked `recent_calls` on a Samsung phone yet.

Not tested on a device yet (`status: experimental`).
