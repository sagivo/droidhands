# Uber

`ride_to` uses Uber's documented standard deep link
(<https://developer.uber.com/docs/riders/ride-requests/tutorials/deep-links/introduction>):
`uber://?action=setPickup&pickup=my_location&dropoff[latitude]=…&dropoff[longitude]=…&dropoff[formatted_address]=…`,
with the brackets percent-encoded as in Uber's own example. It opens the ride screen and never requests a ride.

Why not `https://m.uber.com/ul/?…`: that universal-link form is no longer in Uber's docs. Universal links are now
`https://m.uber.com/looking?drop[0]=<JSON>`, while the `action=setPickup` parameters are documented for the
`uber://` scheme.

Why coordinates are required: Uber's docs treat the address as display text (the universal-link location object
says it "will not override the latitude/longitude", and the FAQ asks for a nickname or formatted address in
addition to the location), so an address alone is not a verified destination.

Known limitations:

* Uber notes that on Android the destination may only appear after the user taps the pickup field.
* Ride type and payment are left to the user (Uber's `product_id` doesn't work with a current-location pickup).

Lyft is not included: its developer docs are offline, and the only source that can be checked (Lyft's archived
Android SDK, <https://github.com/lyft/lyft-android-sdk>) opens `lyft://ridetype?…` through the app's launcher
intent rather than as a link, so a pinned `VIEW` link is unverified.
