# Netflix

Both links are the ones the open-source multistream app opens as plain `VIEW` intents pinned to
`com.netflix.mediaclient` (<https://github.com/renaudallard/multistream>, file
`provider/api/src/main/kotlin/it/allard/multistream/provider/api/DeepLinks.kt`; its README says Netflix search was
verified on a real device):

* `https://www.netflix.com/search?q=<query>`
* `https://www.netflix.com/title/<id>`

Known limitations: Netflix needs a signed-in account and may show the profile picker first. Starting playback
directly (`/watch/<id>`) is not included: only old, iOS and tvOS sources mention it.
