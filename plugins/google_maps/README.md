# Google Maps

Sources:

* `show_on_map` and `navigate` are the Android Maps intents Google documents
  (<https://developer.android.com/guide/components/google-maps-intents>): `geo:0,0?q=…` and
  `google.navigation:q=…&mode=d|w|b|l`, sent as `ACTION_VIEW` pinned to `com.google.android.apps.maps`.
* `directions` and `directions_between` use Maps URLs
  (<https://developers.google.com/maps/documentation/urls/get-started>), which open in the Maps app when it is
  installed. `travelmode` accepts `driving`, `walking`, `bicycling`, `two-wheeler` and `transit`.

Known limitations:

* `navigate` always starts from the current location, and Maps takes the first result for an ambiguous destination.
* `directions` has no `origin` on purpose: the docs only describe leaving it out (current location), not sending
  it empty. Use `directions_between` for another starting point.
* Not covered: the `avoid=` navigation option (tolls, highways, ferries) and Street View.
