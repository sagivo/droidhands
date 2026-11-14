# Zoom

`join_meeting` opens `https://zoom.us/j/<meeting ID>`, the standard Zoom join link. `zoom.us` lists
`us.zoom.videomeetings` (and its Intune build, `us.zoom.videomeetings4intune`) in its Android app-links file
(`/.well-known/assetlinks.json`), so the app handles the link itself. The older `zoomus://zoom.us/join?confno=…`
scheme is not used: Zoom's developer forum says the client URL schemes are deprecated in favor of join links.

The passcode is not passed: the `pwd=` value in invite links is an encoded token, not the passcode people
type, so Zoom asks for the passcode when the meeting has one.

The action is `sensitive` because joining puts the user in front of other people. Depending on the user's Zoom
settings it may join straight away or stop at a video preview, name or passcode prompt, or a waiting room.

Not tested on a device yet (`status: experimental`).
