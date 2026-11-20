# DroidHands: connect to a phone

These instructions are for AI agents. A person sent you a message like this one, copied from the DroidHands app
on their phone:

> Add a new connector named DroidHands with the URL https://connect.droidhands.com/d/abc123/mcp and follow the
> instructions at https://droidhands.com/connect.md. No password or token is needed: I'll tap Allow in the
> DroidHands app on my phone.

DroidHands is an MCP server (Streamable HTTP) for the person's phone. Once they approve you, you can use their
phone through it: read and send messages (WhatsApp, SMS and more), search contacts, place calls, read and answer
notifications, check the calendar and location, and operate apps on the screen. The person chooses what you may
do when they approve you, sees everything you do in the app, and can pause or revoke you at any time.

## 1. What the message gives you

* **The connection URL.** It ends in `/mcp`. Most often `https://connect.droidhands.com/d/<id>/mcp` (the
  DroidHands relay) or another `https://<relay>/d/<id>/mcp` (a relay the person runs). Sometimes the phone itself:
  `http://<phone>.<tailnet>.ts.net:8765/mcp` (Tailscale) or `http://127.0.0.1:8765/mcp` (USB).
* **A token, only sometimes.** It starts with `pb1.`. When the message has one, send it as a bearer token
  (section 4). When it doesn't, there is none: sign-in is a tap on **Allow** on the phone. Never ask the person
  for a password, an API key or a token, and never ask them to create one.
* **Extra steps, only sometimes.** For example, a phone on USB needs `adb forward tcp:8765 tcp:8765` on the
  computer first. Do what the message says.

No URL in the message? Ask the person to open DroidHands on their phone, tap **Connect an AI agent**, and send you
the message shown there.

## 2. Add the connector

Use the first option that fits you. Whatever you add, name it **DroidHands** (`droidhands` where names must be
lowercase).

### A. You can add MCP servers yourself

For example a coding agent on a computer, or an app that gives you a tool to add connectors. Add a remote MCP
server:

* Name: `DroidHands`
* URL: the connection URL
* Transport: Streamable HTTP (sometimes called "HTTP")
* Authentication: OAuth. If a client ID is asked for, use `droidhands`, with no client secret. With a token from
  the message, use the header `Authorization: Bearer <token>` instead of OAuth.

Common commands (replace `<URL>`; with a token, add the header option shown):

| Agent | Command | Then |
|---|---|---|
| Claude Code | `claude mcp add --transport http droidhands <URL>` (token: `--header "Authorization: Bearer <token>"`) | `/mcp`, pick droidhands, Authenticate |
| Codex | `codex mcp add droidhands --url <URL>` | Its browser sign-in opens; if not, `codex mcp login droidhands` |
| Gemini CLI | `gemini mcp add --transport http -s user droidhands <URL>` (token: `-H "Authorization: Bearer <token>"`) | `/mcp auth droidhands` |
| Cursor, Windsurf, VS Code and others | Add `{"url": "<URL>"}` under the server name `droidhands` in the client's MCP settings (token: `"headers": {"Authorization": "Bearer <token>"}`) | Sign in from its MCP settings |
| Clients that only run local (stdio) servers | `npx -y mcp-remote <URL>` as the command (token: `--header "Authorization:${AUTH_HEADER}"` with `AUTH_HEADER=Bearer <token>` in its env; for `http://` URLs other than localhost add `--allow-http`) | |

If the sign-in opens a browser, the phone asks the person to **Allow**. Tell them, for example: "DroidHands is
asking on your phone: check the code matches and tap Allow."

### B. The person adds connectors in your app's settings

For example claude.ai, the Claude and ChatGPT apps, or Grok. You can't add it yourself, so walk the person through
it in a few short steps for your app. What to tell them:

* **Claude** (claude.ai, Claude Desktop, the Claude apps): open
  `https://claude.ai/customize/connectors?modal=add-custom-connector&connectorName=DroidHands&connectorUrl=<URL, percent-encoded>`.
  It opens "Add custom connector" with everything filled in: tap **Add**, then **Connect**. Or by hand: Settings ›
  Connectors › Add custom connector, name DroidHands, paste the URL.
* **ChatGPT**: on chatgpt.com in a browser (not in the ChatGPT app), open `https://chatgpt.com/plugins`, tap
  **Add**, then **Add custom MCP server**. Name it DroidHands, paste the URL, keep OAuth, tick **I understand**,
  then **Create**.
* **Grok** (paid plans): open `https://grok.com/connectors`, tap **New Connector**, then **Custom**. Paste the URL
  and tap **Connect**.
* **Any other app**: its settings for connectors, integrations, plugins, tools or MCP servers. Add a remote MCP
  server with the URL and OAuth sign-in; if it asks for a client ID, it's `droidhands` with no secret.

Then they tap **Allow** in DroidHands on the phone. Some apps only offer a new connector in a new chat, or after
it is turned on for the chat.

### C. Neither: sign in yourself

Any agent that can make HTTP requests can connect without connector settings, on a relay URL
(`https://<relay>/d/<id>/mcp`). The person only opens one link and taps **Allow**.

In the commands below, `<relay>` is the connection URL's origin (scheme and host, for example
`https://connect.droidhands.com`) and `<URL>` is the whole connection URL.

1. Start the sign-in. Put your own name in `client_name`: the person sees it on their phone.

       curl -s -X POST <relay>/oauth/device_authorization \
         --data-urlencode 'resource=<URL>' \
         --data-urlencode 'client_name=YOUR NAME'

   The JSON answer has `device_code`, `verification_uri_complete` (a link) and `expires_in` (about 3 minutes).

2. Right away, send the person the `verification_uri_complete` link, for example: "Open this link and tap Allow in
   DroidHands: <link>". It opens the app; they approve there and come back.

3. Every 5 seconds, ask for the token:

       curl -s -X POST <relay>/oauth/token \
         -d 'grant_type=urn:ietf:params:oauth:grant-type:device_code' \
         --data-urlencode 'device_code=<device_code>'

   * `{"error":"authorization_pending",…}`: not answered yet. Keep polling.
   * `{"access_token":"pb1.…",…}`: connected. Stop polling and tell the person.
   * `access_denied` or `expired_token`: tell the person. Start again only if they ask.

4. Use the access token as a bearer token (section 3). Keep it to yourself: never show it in the chat. If you lose
   it, or a call gets HTTP 401, sign in again: the person taps Allow once more.

A `GET` on the connection URL also returns these steps with the URL filled in.

## 3. Calling the phone

If your app connected the connector for you, just use its tools. Otherwise, POST one JSON-RPC 2.0 message per
request to the connection URL with these headers:

    Authorization: Bearer <token>
    Content-Type: application/json
    Accept: application/json, text/event-stream
    User-Agent: <your app's name>

Send a User-Agent: some default ones (such as Python's urllib) are refused with HTTP 403 "error code: 1010"
before the request reaches the phone.

The server is stateless: there is no session to set up. Start with `initialize`: its result's `instructions`
explain how to drive the phone and exactly what this person allowed you to do. Then `tools/list` and
`tools/call`:

    curl -s <URL> \
      -H "Authorization: Bearer $DROIDHANDS_TOKEN" \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"YOUR NAME","version":"1"}}}'

    … -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
    … -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"contacts_search","arguments":{"query":"Tania"}}}'

## 4. With a token from the message

The token is the person's approval, already given: no sign-in and no tap on Allow. Send it on every request as
`Authorization: Bearer <token>` (in connector settings: a custom header, or a "bearer token" or "API key" field).
Store it only where your app keeps secrets, and never repeat it in the chat. A token is shown only once on the
phone: if it stops working (HTTP 401), it was revoked or has expired, and the person makes a new one.

## 5. Good to know

* Once connected, tell the person, and suggest a first try such as "What's my battery level?"
* Only do what the person asked. Requests outside what they allowed are refused; the result explains why.
* Tool failures and refusals come back as normal results with `"isError": true` and a readable explanation.
* HTTP 503 "Device offline": the phone is not connected. Ask the person to open DroidHands.
* HTTP 502 or 504 on `tools/call`: the phone may already have done it. Check (for example with `sms_list`) before
  sending again, so nothing goes out twice.
* About 40 tools: messaging, contacts, calls, notifications, calendar, location, apps and their actions, and
  reading and operating the screen of any app. `tools/list` returns the ones you are allowed to use.
* The `http://` URLs (Tailscale, USB) reach the phone directly and only work from the person's own devices: a
  cloud app such as claude.ai or ChatGPT can't reach them. Ask the person for the relay URL instead.
