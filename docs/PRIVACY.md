# DroidHands privacy policy

*Applies to the DroidHands apps for Android and iPhone and to the default relay at connect.droidhands.com.
Last updated: November 17, 2026.*

DroidHands lets AI agents that **you** connect use your phone. It has no account, no analytics, no ads and no
tracking. We (the DroidHands project) never sell or share data, and we don't build profiles.

## What happens to your data

- **On your phone.** DroidHands reads contacts, calendar events, reminders and your location only when an agent you
  connected asks for them and you gave that agent the ability, and only if you allowed DroidHands that permission on
  the phone. Your list of agents, their access settings and the activity log stay on the phone. Agent tokens are
  stored only as SHA-256 hashes. You can clear the activity log, revoke agents, or delete the app to remove all of it.
- **To your agent.** What a tool returns (for example a calendar event or a contact's number) goes to the agent
  that asked, which is a service you chose (such as Claude or ChatGPT). Its own privacy policy applies to what it
  does with that data.
- **Through the relay.** Requests and answers travel between your agent and your phone through a relay, over
  HTTPS and WebSocket (TLS). The default relay is run by the DroidHands project on Cloudflare Workers. It passes
  traffic through and does not store it: no request or answer bodies, tokens or secrets are logged. To route and
  rate-limit, it keeps your phone's device id while connected, and, for at most about an hour after a pairing, the
  pairing's state including the connecting client's IP address. A newly paired token is held, encrypted, for at
  most 120 seconds until the agent collects it. Cloudflare, as our hosting provider, processes connection metadata
  such as IP addresses under [its privacy policy](https://www.cloudflare.com/privacypolicy/). You can run your own
  relay instead (Settings › Connection); then none of your traffic reaches ours.

- **Updates (Android, from GitHub only).** The Android app downloaded from GitHub asks GitHub about once a day
  whether a newer release exists. Copies from Google Play and the App Store update through the store instead.

## What we don't do

- No sign-up, email, phone number or name is asked for.
- No analytics, crash reporting or advertising SDKs are included in the apps.
- No data is used for tracking or shared with data brokers.

## Children

DroidHands is not directed at children under 13.

## Changes and contact

Changes to this policy are published in this file, with the date above. Questions:
[open an issue](https://github.com/sagivo/droidhands/issues) on GitHub.
