# How agents reach your phone

An AI agent talks to DroidHands over MCP, so it needs a way to reach the phone. DroidHands offers a
few **routes**. You can use more than one at a time, for example the relay for claude.ai and
Tailscale for Claude Code on your laptop. Whatever the route, every agent still has to be allowed on
the phone, where you choose its access once; everything it does is checked against that access and
logged there ([SECURITY.md](SECURITY.md)).

| Route | Who can reach the phone | Privacy | claude.ai / ChatGPT? | Setup |
|---|---|---|---|---|
| **DroidHands relay** (default) | Agents anywhere, with your connection URL | HTTPS to the relay. Whoever runs the relay could see the requests passing through. | Yes | None: the app is online after the wizard |
| **Tailscale** (private, no relay) | Only your own devices on your tailnet | No relay. Tailscale (WireGuard) encrypts the traffic end to end. | No | Tailscale on the phone and the computer, then one switch |
| **Your own relay** | Agents anywhere (or only your tailnet, see below) | Only you handle the requests | Yes, when it has a public HTTPS address | Run the relay, scan its QR code in DroidHands |
| Same Wi-Fi (advanced) | Anyone on the same network | Unencrypted `http://` | No | Settings › Local server › All networks |
| USB (developers) | The computer the phone is plugged into | Stays on the cable | No | `adb forward` |

In the app, the routes are on the onboarding **Connect** step (under **More ways to connect**) and in
**Settings › How agents reach this phone**, which lists the connection URL of every route that is
on, with **Copy** and **Share**. Home shows them too.

## DroidHands relay (default)

Nothing to do. On first run the app connects to the relay it comes with and the Connect step says
**You're online**. Tap **Connect an AI agent**, paste the connection URL
(`https://<relay>/d/<id>/mcp`) into your agent and tap **Allow** on the phone
([GETTING_STARTED.md](GETTING_STARTED.md#3-connect-your-ai-agent)).

It works with every agent, including cloud ones (claude.ai, ChatGPT), on Wi-Fi or mobile data, and
behind any router: the phone connects out to the relay. The relay is a tunnel and can't do anything
by itself, but it does see the requests and answers passing through. If that matters to you, use
Tailscale or your own relay.

## Tailscale (private, no relay)

[Tailscale](https://tailscale.com) puts your devices on a private network, your *tailnet*. With this
route the phone serves agents directly on its tailnet address: nothing goes through a relay, and the
traffic is encrypted by Tailscale. Only devices signed in to your tailnet can reach it.

It works with agents that run **on your own devices**: Claude Code, Codex, Claude Desktop (through
`mcp-remote`, see below) and other MCP clients on a computer in your tailnet. It does **not** work
with **claude.ai or ChatGPT**: they run in the cloud and can't reach your tailnet. Keep the relay on
as well if you use them.

1. Install Tailscale on the phone (the app offers **Get Tailscale**, which opens the Play Store) and on
   your computer, and sign in to both with the same account.
2. In DroidHands: Connect step › **More ways to connect** › Tailscale › **Use Tailscale**, or
   *Settings › How agents reach this phone* › **Tailscale (private, no relay)** › **Save**.
3. DroidHands shows the URL, for example `http://pixel-8.tail1234.ts.net:8765/mcp` (the phone's
   MagicDNS name, or its `100.x.y.z` address when MagicDNS is off). Paste it into your agent, then tap
   **Allow** on the phone. If your version of the app has no one-tap connect on this route yet,
   create a token instead (**Use a token instead** on the *Connect an AI agent* page) and use the
   snippet it shows.

   | Agent (on a computer in your tailnet) | How |
   |---|---|
   | Claude Code | `claude mcp add --transport http phone http://pixel-8.tail1234.ts.net:8765/mcp` |
   | Codex | Add a streamable-HTTP MCP server with that URL, e.g. `[mcp_servers.phone]` with `url = "…"` in `~/.codex/config.toml` |
   | Claude Desktop | Through `mcp-remote` in `claude_desktop_config.json` (it runs on your computer). Its *Connectors* page connects from Anthropic's servers, which can't reach your tailnet. |

While Tailscale is off on the phone, DroidHands shows **Waiting for Tailscale** and listens
nowhere; it starts again by itself when Tailscale reconnects, and follows the address if it changes.
The URL is `http://`, which is fine here: Tailscale encrypts everything between your devices.

Good to know:

* DroidHands listens **only** on the phone's tailnet address, not on Wi-Fi or mobile data, and not
  on `127.0.0.1` (so `adb forward` doesn't reach it in this mode).
* If Tailscale is on but DroidHands still waits, check that DroidHands isn't excluded in Tailscale's
  app settings (split tunneling): excluded apps can't use the tailnet.
* Mobile carriers sometimes give phones addresses in the same `100.64.0.0/10` range. DroidHands only
  uses one that belongs to a VPN (Tailscale), never the carrier's.
* DroidHands listens on the phone's IPv4 tailnet address. Clients that try MagicDNS's IPv6 address
  first fall back to IPv4 by themselves.

## Your own relay

Run the relay yourself and nobody else handles your agents' requests. It works with every agent as
long as it has an HTTPS address they can reach.

* **On a server or a platform** (Fly.io, Render, Cloudflare Workers, Docker behind Caddy):
  [relay/README.md](../relay/README.md#deploy).
* **On your computer, through Tailscale**: `./scripts/relay-tailscale.sh` (below).

Then point the phone at it. The relay prints a **setup link** and its QR code when it starts (its
setup page, `https://<relay>/setup`, shows them too):

* **Scan QR code in DroidHands**: in *More ways to connect* › Your own relay, or *Settings › How
  agents reach this phone* › Relay, tap **Scan QR code** and point the camera at the code. The
  scanner is Google Play services', so DroidHands needs no camera permission; on phones without
  Google Play services the button is hidden.
* Or open the setup link on the phone and tap **Open in DroidHands**, or paste the setup link (or
  just the relay's address) into the Relay URL field there.

Either way DroidHands shows **Connect to relay?** with the relay's address before it changes
anything. A QR code that isn't a relay setup link (another web page, an agent's `/mcp` connection
URL, `javascript:` and other schemes) is refused. Agents connected through the previous relay need
the new connection URL.

### On your computer, with scripts/relay-tailscale.sh

```bash
./scripts/relay-tailscale.sh                    # private: only your tailnet can reach it
./scripts/relay-tailscale.sh --public --access-key "$(openssl rand -hex 16)"   # the internet too
./scripts/relay-tailscale.sh --stop             # remove the Tailscale mapping and stop the relay
```

It checks that Tailscale is installed and logged in, runs the relay from `relay/` (Node.js 20+, or
`--docker`) on `127.0.0.1`, publishes it at `https://<your-computer>.<tailnet>.ts.net` with
`tailscale serve`, and prints the setup link and QR code for the phone. The relay keeps its secret in
`~/.droidhands-relay`, so connected agents keep working after a restart.

* **Private (default)**: only devices on your tailnet reach the relay, so the phone needs the
  Tailscale app too. Agents on your own computers work; claude.ai and ChatGPT don't.
* **`--public`**: published with Tailscale Funnel, so anyone on the internet can reach it, which is
  what claude.ai and ChatGPT need. Add an access key (it goes into the setup link, so the phone gets
  it automatically), otherwise anyone who finds the address can connect a phone to your relay.
* `--https-port 8443` uses another port (Funnel allows 443, 8443 and 10000). Running the script
  again is safe, and it never replaces a `tailscale serve` mapping it didn't create.

Your tailnet needs MagicDNS and HTTPS certificates (the Tailscale admin console's DNS page), and for
`--public`, Funnel allowed for this computer. The computer has to stay on for agents to reach the
phone.

## Same Wi-Fi (advanced)

*Settings › How agents reach this phone › Local server (advanced)*: turn it on and choose **Listen on
› All networks**. Agents on the same Wi-Fi, hotspot or VPN can reach `http://<phone-ip>:8765/mcp`.
Everyone on that network can reach the endpoint and the traffic is unencrypted `http://`: an agent
still needs a token, but prefer Tailscale.

## USB (developers)

*Local server (advanced)* with **Listen on › This phone only** (the default): DroidHands listens on
`127.0.0.1`. On the computer the phone is plugged into, run `adb forward tcp:8765 tcp:8765` and use
`http://127.0.0.1:8765/mcp` with a token. [GETTING_STARTED.md](GETTING_STARTED.md#developer-setup-over-usb-scriptssetup-phonesh)
sets up a developer build this way.
