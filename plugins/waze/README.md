# Waze

All actions use Waze's documented deep links (<https://developers.google.com/waze/deeplinks>): `https://waze.com/ul`
with `q`, `ll`, `favorite` and `navigate=yes`.

Known limitations:

* `navigate` (an address with `navigate=yes`, without coordinates) depends on Waze's own address lookup. The docs'
  example adds `ll`, and the Waze forum reports that the lookup sometimes finds nothing, so Waze shows search
  results instead (<https://waze.com/discuss/t/some-deep-links-examples/198673>). That is why it reports
  `completes: false`. `navigate_to_coordinates` skips the lookup.
* `navigate_favorite` needs Home or Work saved in Waze; otherwise Waze asks for the address.
