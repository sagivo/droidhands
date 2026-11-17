import { html, type PageOptions } from './html.js';

/**
 * The connection URL opened in a browser (docs/PROTOCOL.md §5.1, "Setup page"): one-click setup for the
 * common agents, so a link shared from the phone to a computer is all it takes. Static per URL: it does not
 * look the device up, so it reveals nothing about the phone (not even whether it is online). Adding the URL
 * to an agent grants nothing: the phone still asks the user to Allow.
 */

/** Name of the server in command-line agents; the same as the phone's snippets. */
export const SERVER_NAME = 'phone';

/** Name of the connector in Claude, next to the user's other connectors. */
export const CONNECTOR_NAME = 'DroidHands';

/**
 * True for a plain browser visit of the connection URL: a GET or HEAD that asks for HTML, without
 * credentials. MCP clients never ask for HTML (their GET wants `text/event-stream`), so they keep the
 * usual answers.
 */
export function wantsSetupPage(method: string | undefined, accept: string | undefined, authorization: string | undefined): boolean {
  if (method !== 'GET' && method !== 'HEAD') return false;
  if (authorization !== undefined && authorization !== '') return false;
  const types = (accept ?? '').toLowerCase();
  return types.includes('text/html') && !types.includes('text/event-stream');
}

/** claude.ai's documented install link: "Add custom connector" with the name and URL filled in. */
export function claudeAddLink(url: string): string {
  return (
    'https://claude.ai/customize/connectors?modal=add-custom-connector' +
    `&connectorName=${encodeURIComponent(CONNECTOR_NAME)}&connectorUrl=${encodeURIComponent(url)}`
  );
}

/** Cursor's documented MCP install deeplink, for a remote server at [url]. */
export function cursorInstallLink(url: string): string {
  // Connection URLs are ASCII, so btoa (in Node and in Workers) encodes them as-is.
  const config = btoa(JSON.stringify({ url }));
  return `cursor://anysphere.cursor-deeplink/mcp/install?name=${encodeURIComponent(SERVER_NAME)}&config=${encodeURIComponent(config)}`;
}

/** [value] as one POSIX shell word: unchanged when it needs no quoting, else single-quoted. */
export function shellQuote(value: string): string {
  return /^[A-Za-z0-9_.:/@%+=,-]+$/.test(value) ? value : `'${value.replace(/'/g, `'\\''`)}'`;
}

export interface AgentCommand {
  readonly agent: string;
  readonly command: string;
  readonly then: string;
}

/** The command-line agents and what to type after the command (same as the phone's guides). */
export function agentCommands(url: string): readonly AgentCommand[] {
  const quoted = shellQuote(url);
  return [
    {
      agent: 'Claude Code',
      command: `claude mcp add --transport http ${SERVER_NAME} ${quoted}`,
      then: `Then type /mcp in Claude Code, pick ${SERVER_NAME} and choose Authenticate.`,
    },
    {
      agent: 'Codex',
      command: `codex mcp add ${SERVER_NAME} --url ${quoted}`,
      then: `Your browser opens to sign in. If it doesn't, run codex mcp login ${SERVER_NAME}.`,
    },
    {
      agent: 'Gemini CLI',
      command: `gemini mcp add --transport http -s user ${SERVER_NAME} ${quoted}`,
      then: `Then type /mcp auth ${SERVER_NAME} in Gemini CLI.`,
    },
  ];
}

/**
 * The setup page for the connection URL [url]. [agentGuide] (docs/PROTOCOL.md §5.7) is included, folded
 * away, for agents whose link fetcher asks for HTML like a browser.
 */
export function connectorSetupPage(url: string, agentGuide: string): PageOptions {
  const commands = agentCommands(url).map(
    (c) => html`<div class="card">
<h2>${c.agent}</h2>
<div class="copyrow"><code class="cmd">${c.command}</code><button type="button" class="copy" data-copy="${c.command}">Copy</button></div>
<p class="muted">${c.then}</p>
</div>`,
  );
  return {
    title: 'Add your phone to your AI agent · DroidHands',
    body: html`<p class="eyebrow">DroidHands</p>
<h1>Add your phone to your AI agent</h1>
<p class="muted">Pick your agent below. Nothing works until you tap <strong>Allow</strong> on the phone, and you choose there what it may do.</p>
<p class="muted"><strong>AI agent reading this?</strong> You don't need connector settings: if you can make HTTP requests, sign in yourself with the steps under "For AI agents" at the end of this page.</p>
<div class="card">
<h2>Claude</h2>
<p class="muted">claude.ai, Claude Desktop and the Claude apps.</p>
<a class="button" href="${claudeAddLink(url)}">Add to Claude</a>
<p class="muted">Claude opens with DroidHands filled in. Click Add, then Connect.</p>
</div>
${commands}
<div class="card">
<h2>Cursor</h2>
<a class="button" href="${cursorInstallLink(url)}">Add to Cursor</a>
<p class="muted">Cursor opens and asks you to install the server. Then sign in from its MCP settings.</p>
</div>
<div class="card">
<h2>ChatGPT and other agents</h2>
<div class="copyrow"><code class="cmd">${url}</code><button type="button" class="copy" data-copy="${url}">Copy</button></div>
<p class="muted">Add it as a remote MCP server (Streamable HTTP) with OAuth sign-in. In ChatGPT: Plugins › Add › Add custom MCP server. If your app asks for a client ID, use droidhands.</p>
</div>
<p class="muted">This link isn't a password: every agent still has to be allowed on the phone.</p>
<details class="card"><summary>For AI agents: how to connect</summary><pre class="guide">${agentGuide}</pre></details>`,
    script: COPY_SCRIPT,
  };
}

/** Copy buttons: the text comes from `data-copy`; the label says "Copied" for a moment. */
const COPY_SCRIPT = `(function () {
  'use strict';
  function copied(button) {
    var label = button.textContent;
    button.textContent = 'Copied';
    setTimeout(function () { button.textContent = label; }, 1500);
  }
  function fallback(text, button) {
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try { if (document.execCommand('copy')) copied(button); } catch (e) {}
    document.body.removeChild(area);
  }
  Array.prototype.forEach.call(document.querySelectorAll('button.copy'), function (button) {
    button.addEventListener('click', function () {
      var text = button.getAttribute('data-copy') || '';
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { copied(button); }, function () { fallback(text, button); });
      } else {
        fallback(text, button);
      }
    });
  });
})();`;
