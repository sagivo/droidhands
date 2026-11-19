import { DEVICE_AUTHORIZATION_PATH, DEVICE_CODE_GRANT, POLL_INTERVAL_SEC } from './oauth/deviceFlow.js';
import { connectorUrl } from './oauth/metadata.js';
import { SETUP_PATH } from './setup/setupUrl.js';

/**
 * Instructions for AI agents (docs/PROTOCOL.md §5.7), as Markdown. A person sends their connection
 * URL to an agent ("connect to my phone: https://…/d/<id>/mcp"); agents that open links get this
 * page from `GET` on that URL, and `GET /llms.txt` gives the same without a phone. MCP clients never
 * see it: they POST, or GET with `Accept: text/event-stream`. Pure; shared by the Node and Worker relays.
 */

/** `GET /llms.txt`. */
export const LLMS_TXT_PATH = '/llms.txt';

/**
 * True when a GET on a connection URL should get the guide: anything but an MCP client opening
 * its SSE stream (Streamable HTTP requires `Accept: text/event-stream` there; those keep getting 405).
 */
export function wantsAgentGuide(method: string, accept: string | undefined): boolean {
  if (method !== 'GET' && method !== 'HEAD') return false;
  return !(accept ?? '').toLowerCase().includes('text/event-stream');
}

/** Markdown if the client asked for it, otherwise plain text (the same characters, readable anywhere). */
export function agentGuideContentType(accept: string | undefined): string {
  return (accept ?? '').toLowerCase().includes('text/markdown') ? 'text/markdown; charset=utf-8' : 'text/plain; charset=utf-8';
}

/** `GET /`. */
export function bannerText(base: string): string {
  return (
    `DroidHands relay.\nSet up a phone: ${base}${SETUP_PATH}\n` +
    `AI apps connect to the connection URL shown in the DroidHands app (${base}/d/<deviceId>/mcp).\n` +
    `AI agents: instructions at ${base}${LLMS_TXT_PATH}\n`
  );
}

/** The guide for one phone's connection URL. */
export function agentGuide(base: string, deviceId: string): string {
  return guide(base, connectorUrl(base, deviceId));
}

/** The guide without a phone (`/llms.txt`): the agent needs the person's connection URL first. */
export function llmsTxt(base: string): string {
  return guide(base, null);
}

function guide(base: string, url: string | null): string {
  const mcp = url ?? `${base}/d/<id>/mcp`;
  const intro =
    url === null
      ? `This is a DroidHands relay. Each phone has its own connection URL, ${base}/d/<id>/mcp, shown in the ` +
        'DroidHands app. Ask the person for theirs, then open it (GET) for these instructions with the URL filled in.'
      : `This is a DroidHands connection URL: an MCP server (Streamable HTTP) for one person's Android phone.`;
  return `# DroidHands: connect to a phone

${intro}

With the person's approval, an AI agent can use their phone through it: read and send messages (WhatsApp, SMS),
search contacts, place calls, read and answer notifications, and operate apps. The person chooses what you may do
when they approve you, sees everything you do in the app, and can pause or revoke you at any time.

MCP server URL: ${mcp}

## If you are a person

Paste this URL into your AI agent as a custom connector (remote MCP server), or just send it to your agent in a
chat. Then switch to DroidHands on your phone and tap Allow. No password, no key to copy.

## If you are an AI agent

### Option A: your app supports remote MCP servers

Add ${mcp} as a remote MCP server (Streamable HTTP, OAuth sign-in). If it asks for a client ID, use
\`droidhands\` with no client secret. The person taps Allow on their phone. That's all.

### Option B: sign in yourself (any agent that can make HTTP requests)

The person only opens one link and taps Allow in the DroidHands app. Never ask them for a password, an API key or
a token, and don't ask them to create or store one.

1. Start the sign-in. Put your own name in client_name; the person sees it on their phone.

       curl -s -X POST ${base}${DEVICE_AUTHORIZATION_PATH} \\
         --data-urlencode 'resource=${mcp}' \\
         --data-urlencode 'client_name=YOUR NAME'

   The JSON answer has device_code, verification_uri_complete (a link) and expires_in (about 3 minutes).

2. Right away, send the person the verification_uri_complete link, for example: "Open this link and tap Allow
   in DroidHands: <link>". It opens the app; they approve there and come back.

3. Every ${POLL_INTERVAL_SEC} seconds, ask for the token:

       curl -s -X POST ${base}/oauth/token \\
         -d 'grant_type=${DEVICE_CODE_GRANT}' \\
         --data-urlencode 'device_code=<device_code>'

   * {"error":"authorization_pending",…}: not answered yet. Keep polling.
   * {"access_token":"pb1.…",…}: connected. Stop polling and tell the person.
   * access_denied or expired_token: tell the person. Start again only if they ask.

4. Send the access token as a Bearer token on every call below. Keep it to yourself (never show it in the chat).
   If you lose it, or a call gets HTTP 401, just sign in again: the person taps Allow once more.

### Calling the phone

POST one JSON-RPC 2.0 message per request to ${mcp} with these headers:

    Authorization: Bearer <access token>
    Content-Type: application/json
    Accept: application/json, text/event-stream
    User-Agent: <your app's name>

Send a User-Agent: some default ones (such as Python's urllib) are refused with HTTP 403 "error code: 1010"
before the request reaches the phone.

The server is stateless: there is no session to set up. Start with initialize: its result's "instructions" explain
how to drive the phone and exactly what this person allowed you to do. Then tools/list and tools/call:

    curl -s ${mcp} \\
      -H "Authorization: Bearer $DROIDHANDS_TOKEN" \\
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \\
      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"YOUR NAME","version":"1"}}}'

    … -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
    … -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"contacts_search","arguments":{"query":"Tania"}}}'

### Good to know

* Only do what the person asked. Requests outside what they allowed are refused; the result explains why.
* Tool failures and refusals come back as normal results with "isError": true and a readable explanation.
* HTTP 503 "Device offline": the phone is not connected. Ask the person to open DroidHands.
* HTTP 502 or 504 on tools/call: the phone may already have done it. Check (for example with sms_list) before
  sending again, so nothing goes out twice.
* About 40 tools: messaging, contacts, calls, notifications, calendar, location, apps and their actions, and
  reading and operating the screen of any app. tools/list returns the ones you are allowed to use.
`;
}
