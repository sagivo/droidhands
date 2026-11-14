# Slack

The links are Slack's documented `slack://` deep links
([Deep linking into Slack](https://docs.slack.dev/interactivity/deep-linking)): `slack://channel?team=&id=`,
`slack://user?team=&id=` and `slack://open?team=`. Slack lists Android among the supported clients. They take
IDs only ("these schemes do not support workspace subdomains, channel names, or user names"), and unknown
paths fall back to `slack://open`.

Slack has no documented link for sending or drafting a message: use `share_text` (Slack's share screen) or
the screen tools.

Not tested on a device yet (`status: experimental`).
