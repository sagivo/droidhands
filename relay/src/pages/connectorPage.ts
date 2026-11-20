import { html, type PageOptions } from './html.js';

/**
 * The connection URL opened in a browser (docs/PROTOCOL.md §5.1, "Setup page"): one-click setup for the
 * common agents, so a link shared from the phone to a computer is all it takes. Static per URL: it does not
 * look the device up, so it reveals nothing about the phone (not even whether it is online). Adding the URL
 * to an agent grants nothing: the phone still asks the user to Allow.
 */

/** Name of the connector, next to the user's other connectors; the same as the phone's message. */
export const CONNECTOR_NAME = 'DroidHands';

/** The full instructions for agents, on the website (website/public/connect.md). */
export const INSTRUCTIONS_URL = 'https://droidhands.com/connect.md';

/** The one message that adds DroidHands to any agent: the same as the phone's (AgentPrompt.signIn). */
export function agentPrompt(url: string): string {
  return (
    `Add a new connector named ${CONNECTOR_NAME} with the URL ${url} and follow the instructions at ${INSTRUCTIONS_URL}. ` +
    "No password or token is needed: I'll tap Allow in the DroidHands app on my phone."
  );
}

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

/**
 * The setup page for the connection URL [url]. [agentGuide] (docs/PROTOCOL.md §5.7) is included, folded
 * away, for agents whose link fetcher asks for HTML like a browser.
 */
export function connectorSetupPage(url: string, agentGuide: string): PageOptions {
  const prompt = agentPrompt(url);
  return {
    title: 'Add your phone to your AI agent · DroidHands',
    body: html`<p class="eyebrow">DroidHands</p>
<h1>Add your phone to your AI agent</h1>
<p class="muted">Send this message to your agent (Claude, ChatGPT, Grok or any other), in a chat or on the computer it runs on. Nothing works until you tap <strong>Allow</strong> on the phone, and you choose there what it may do.</p>
<div class="card">
<div class="copyrow"><code class="cmd prompt">${prompt}</code><button type="button" class="copy" data-copy="${prompt}">Copy</button></div>
</div>
<p class="muted"><strong>AI agent reading this?</strong> Follow the instructions at <a href="${INSTRUCTIONS_URL}">${INSTRUCTIONS_URL}</a>, or the steps under "For AI agents" at the end of this page.</p>
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
