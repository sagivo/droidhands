#!/usr/bin/env node
// End-to-end use-case scenarios against a real PhoneBridge device (emulator + fixture apps).
// usage: PB_URL=http://127.0.0.1:8787/mcp PB_TOKEN=pb1... node scripts/e2e/scenarios.mjs [filter]
// App-action scenarios ("app actions: ..."): the local-plugin one pushes clients/android/fixtures/plugins/fakemail_test
// with scripts/plugin-push.sh (debug build) and needs Settings > Developer > Local plugins on; it skips otherwise.
// PB_BLOCKED_APP=<package> (an app blocked for this token) adds a check with that app; PhoneBridge itself is always used.
import { execFileSync } from 'node:child_process';
import { McpClient, Runner, adb, shell, sleep, waitFor, fixtureEvents, assert, skip } from './lib.mjs';

const url = process.env.PB_URL || 'http://127.0.0.1:8787/mcp';
const token = process.env.PB_TOKEN;
if (!token) throw new Error('PB_TOKEN is required');
const only = process.argv[2];
const c = new McpClient(url, token);
const run = new Runner();
const step = (name, fn) => (!only || name.toLowerCase().includes(only.toLowerCase()) ? run.step(name, fn) : null);

const WA = 'com.whatsapp';
const MAIL = 'dev.phonebridge.fixture.mail';
const ok = (r, what) => { assert(!r.isError, `${what} returned error: ${r.text}`); return r; };
// Device and host clocks differ slightly; allow 5 s of skew.
const lastEvent = (pkg, ev, since) => fixtureEvents(pkg).filter((e) => e.event === ev && e.ts >= since - 5000).pop();

await c.initialize();
// Make sure no stray dialogs are in front.
shell('input keyevent KEYCODE_HOME');

await step('contacts_search resolves "Tanya" fuzzily to Tania Cohen', async () => {
  const r = ok(await c.call('contacts_search', { query: 'Tanya' }), 'contacts_search');
  assert(r.json?.[0]?.name === 'Tania Cohen', `top match was ${JSON.stringify(r.json?.[0])}`);
  return r.text.slice(0, 200);
});

await step('contacts_search finds a Hebrew-script contact from Latin "Yossi"', async () => {
  const r = ok(await c.call('contacts_search', { query: 'Yossi' }), 'contacts_search');
  assert(r.json?.some((m) => m.name === 'יוסי לוי'), `no Hebrew match: ${r.text.slice(0, 200)}`);
  return r.text.slice(0, 160);
});

await step('USE CASE: send a WhatsApp message to "Tania"', async () => {
  const t0 = Date.now();
  const r = ok(await c.call('whatsapp_send', { contact: 'Tania', message: "I'll be 10 minutes late 🙏" }), 'whatsapp_send');
  const ev = await waitFor(() => lastEvent(WA, 'SENT', t0), { what: 'fixture SENT event' });
  assert(ev.phone === '972541234567', `sent to ${ev.phone}`);
  assert(ev.text === "I'll be 10 minutes late 🙏", `text was ${ev.text}`);
  return `${r.text}\n fixture recorded: ${JSON.stringify(ev)}`;
});

await step('whatsapp_send to a number not on WhatsApp fails clearly', async () => {
  const r = await c.call('whatsapp_send', { to: '+1 555 0000', message: 'hi' });
  assert(r.isError, 'expected an error');
  assert(/isn.?t on WhatsApp|not on WhatsApp/i.test(r.text), r.text);
  shell('input keyevent KEYCODE_BACK');
  return r.text.slice(0, 200);
});

await step('USE CASE: reply to the latest WhatsApp message (notification inline reply)', async () => {
  shell(`am broadcast -n ${WA}/.IncomingMessageReceiver -a com.whatsapp.fixture.INCOMING --es from Tania --es text 'Where are you?'`);
  const t0 = Date.now();
  const n = await waitFor(async () => (await c.call('notifications_list', { package: WA })).json?.find((x) => x.title === 'Tania'), { what: 'Tania notification' });
  ok(await c.call('notification_reply', { key: n.key, message: 'Almost there!' }), 'notification_reply');
  const ev = await waitFor(() => lastEvent(WA, 'REPLY', t0), { what: 'fixture REPLY event' });
  assert(ev.text === 'Almost there!', ev.text);
  return JSON.stringify(ev);
});

await step('USE CASE: send an SMS to "Dana"', async () => {
  const r = ok(await c.call('sms_send', { contact: 'Dana', message: 'Running late, see you at 8' }), 'sms_send');
  const row = await waitFor(() => shell("content query --uri content://sms/sent --projection address:body").split('\n').find((l) => l.includes('Running late, see you at 8')), { what: 'sent SMS row' });
  return `${r.text}\n provider: ${row}`;
});

await step('USE CASE: read recent SMS (incoming from the modem)', async () => {
  adb('emu', 'sms', 'send', '5550199', 'Dinner at 7? Reply YES');
  const r = await waitFor(async () => {
    const res = ok(await c.call('sms_list', { box: 'inbox', limit: 5 }), 'sms_list');
    return res.json?.some((m) => (m.body || '').includes('Dinner at 7')) ? res : null;
  }, { what: 'incoming SMS in sms_list' });
  return r.text.slice(0, 300);
});

await step('USE CASE: reply to an SMS via the Messages notification', async () => {
  const n = await waitFor(async () => (await c.call('notifications_list', { package: 'com.google.android.apps.messaging' })).json?.[0], { what: 'Messages notification' });
  ok(await c.call('notification_reply', { key: n.key, message: 'YES' }), 'notification_reply');
  const row = await waitFor(() => shell('content query --uri content://sms/sent --projection address:body').split('\n').find((l) => /address=5550199.*body=YES/.test(l)), { what: 'reply in sent box' });
  return row;
});

await step('USE CASE: reply to an email (inline reply on the mail notification)', async () => {
  shell(`am broadcast -n ${MAIL}/.IncomingEmailReceiver -a ${MAIL}.INCOMING --es from bob@example.com --es subject 'Q3 report' --es body 'Can you send me the numbers?'`);
  const t0 = Date.now();
  const n = await waitFor(async () => (await c.call('notifications_list', { package: MAIL })).json?.find((x) => x.title === 'bob@example.com'), { what: 'mail notification' });
  ok(await c.call('notification_reply', { key: n.key, message: 'Sure, sending them this afternoon.' }), 'notification_reply');
  const ev = await waitFor(() => lastEvent(MAIL, 'EMAIL_REPLY', t0), { what: 'EMAIL_REPLY' });
  assert(ev.to === 'bob@example.com' && ev.subject === 'Re: Q3 report', JSON.stringify(ev));
  return JSON.stringify(ev);
});

await step('USE CASE: reply to an email in the mail app (Gmail-style: Reply opens the app; screen tools)', async () => {
  shell(`am broadcast -n ${MAIL}/.IncomingEmailReceiver -a ${MAIL}.INCOMING --es from carol@example.com --es subject 'Offsite' --es body 'Are you joining?' --ez inline false`);
  const t0 = Date.now();
  const n = await waitFor(async () => (await c.call('notifications_list', { package: MAIL })).json?.find((x) => x.title === 'carol@example.com'), { what: 'mail notification' });
  ok(await c.call('notification_action', { key: n.key, action: 'Reply' }), 'notification_action Reply');
  ok(await c.call('ui_wait', { text: 'Compose email', timeout_ms: 8000 }), 'ui_wait compose');
  const screen = ok(await c.call('screen_read', {}), 'screen_read');
  const bodyRef = screen.text.split('\n').find((l) => /Compose email/.test(l))?.trim().split(/\s+/)[0];
  assert(bodyRef?.startsWith('n'), `no body field ref in:\n${screen.text.slice(0, 800)}`);
  ok(await c.call('ui_type', { ref: bodyRef, text: "Yes, I'll be there." }), 'ui_type');
  ok(await c.call('ui_tap', { text: 'Send' }), 'ui_tap Send');
  const ev = await waitFor(() => lastEvent(MAIL, 'EMAIL_SENT', t0), { what: 'EMAIL_SENT' });
  assert(ev.to.includes('carol@example.com') && /Yes, I'll be there/.test(ev.body), JSON.stringify(ev));
  return JSON.stringify(ev);
});

await step('USE CASE: make a phone call to "Bob"', async () => {
  const startedAt = Date.now() - 5000; // device/host clock skew allowance
  const r = ok(await c.call('phone_call', { contact: 'Bob' }), 'phone_call');
  assert(r.json?.to === '+16505550100', r.text);
  // The platform call log records the outgoing call (Telecom's own event log is a ring buffer).
  const entry = await waitFor(async () => {
    const res = ok(await c.call('call_log_list', { type: 'outgoing', limit: 3 }), 'call_log_list');
    return res.json?.find((x) => (x.number || '').includes('6505550100') && Date.parse(x.time) >= startedAt);
  }, { what: 'outgoing call in the call log', timeoutMs: 20000 });
  await sleep(1500);
  await c.call('call_end', {}); // best effort: the emulator modem often drops the call itself
  await sleep(1500);
  shell('input keyevent KEYCODE_WAKEUP');
  return `${r.text}\n call log: ${JSON.stringify(entry)}`;
});

await step('call_log_list shows the call to Bob', async () => {
  const r = await waitFor(async () => {
    const res = ok(await c.call('call_log_list', { limit: 5 }), 'call_log_list');
    return res.json?.some((x) => (x.number || '').includes('6505550100')) ? res : null;
  }, { what: 'call in call log' });
  return r.text.slice(0, 250);
});

await step('generic app control: open Fixture Mail, read inbox, open an email', async () => {
  ok(await c.call('app_open', { name: 'Fixture Mail' }), 'app_open');
  ok(await c.call('ui_wait', { package: MAIL, timeout_ms: 8000 }), 'ui_wait');
  const s = ok(await c.call('screen_read', {}), 'screen_read');
  assert(/email_row|email_subject/.test(s.text), s.text.slice(0, 500));
  const ref = s.text.split('\n').find((l) => l.includes('"Q3 report"'))?.trim().split(/\s+/)[0];
  ok(await c.call('ui_tap', { ref }), 'ui_tap ref');
  ok(await c.call('ui_wait', { text: 'Can you send me the numbers?', timeout_ms: 5000 }), 'ui_wait body');
  return s.text.split('\n').slice(0, 8).join('\n');
});

await step('screen_screenshot returns an image', async () => {
  const r = ok(await c.call('screen_screenshot', { max_dimension: 800 }), 'screenshot');
  assert(r.images[0]?.data?.length > 1000, 'no image data');
  return `${r.images[0].mimeType} ${r.images[0].data.length} b64 chars; ${r.text}`;
});

await step('device: volume, alarm, clipboard, url', async () => {
  ok(await c.call('volume_set', { stream: 'music', percent: 40 }), 'volume_set');
  const alarm = ok(await c.call('alarm_set', { hour: 7, minute: 30, label: 'E2E alarm' }), 'alarm_set');
  ok(await c.call('clipboard_set', { text: 'from agent' }), 'clipboard_set');
  ok(await c.call('url_open', { url: 'https://example.com' }), 'url_open');
  const bad = await c.call('url_open', { url: 'javascript:alert(1)' });
  assert(bad.isError, 'javascript: URL should be rejected');
  shell('input keyevent KEYCODE_HOME');
  return alarm.text.slice(0, 160);
});

await step('location_get after a GPS fix', async () => {
  adb('emu', 'geo', 'fix', '34.7818', '32.0853');
  await sleep(2000);
  const r = ok(await c.call('location_get', { accuracy: 'high', timeout_seconds: 20 }), 'location_get');
  assert(Math.abs(r.json.latitude - 32.0853) < 0.01, r.text);
  return r.text;
});

// ---- App actions (docs/PLUGINS.md): apps_list -> app_actions_list -> app_action_run ----

const BRIDGE = 'dev.phonebridge.android';
const PLUGIN_PUSH = new URL('../plugin-push.sh', import.meta.url).pathname;
const FAKEMAIL_PLUGIN = new URL('../../clients/android/fixtures/plugins/fakemail_test', import.meta.url).pathname;
const UI = new URL('./ui.py', import.meta.url).pathname;
const ui = (...a) => execFileSync('timeout', ['-k', '2', '60', 'python3', '-I', UI, ...a], { encoding: 'utf8' }).trim();

/** Approves the pending PhoneBridge prompt whose title contains `title`: opens it from the shade, then taps Allow. */
async function approveOnPhone(title) {
  shell('cmd statusbar expand-notifications');
  await sleep(1500);
  ui('tap', title);
  await sleep(1500); // Allow is enabled shortly after the approval screen appears.
  ui('tap', 'Allow');
}

const topActivity = () => shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1");

await step('app actions: apps_list and app_actions_list show what Fixture Mail can do', async () => {
  const apps = ok(await c.call('apps_list', { query: 'Fixture Mail' }), 'apps_list');
  const entry = apps.json?.find((a) => a.package === MAIL);
  assert(entry, `Fixture Mail not listed: ${apps.text.slice(0, 300)}`);
  assert(['basic', 'local', 'plugin'].includes(entry.integration), JSON.stringify(entry));
  assert(entry.actions?.includes('open') && entry.actions.includes('share_text'), JSON.stringify(entry));
  const list = ok(await c.call('app_actions_list', { app: 'Fixture Mail' }), 'app_actions_list');
  const share = list.json?.actions?.find((a) => a.action === 'share_text');
  assert(share?.params?.properties?.text && share.completes === false, list.text.slice(0, 500));
  return JSON.stringify(entry);
});

await step('app actions: app_actions_list for WhatsApp shows the bundled plugin\'s send_message', async () => {
  const r = ok(await c.call('app_actions_list', { app: WA }), 'app_actions_list');
  if (r.json?.integration !== 'plugin') skip(`this build has no bundled WhatsApp plugin (integration: ${r.json?.integration})`);
  const send = r.json.actions.find((a) => a.action === 'send_message');
  assert(send, `no send_message in: ${r.json.actions.map((a) => a.action).join(', ')}`);
  assert(send.params?.properties?.message, JSON.stringify(send.params));
  return `${send.title}: ${JSON.stringify(send.params).slice(0, 200)}`;
});

await step('app actions: app_actions_list(query) finds which app can do something', async () => {
  const r = ok(await c.call('app_actions_list', { query: 'share text', limit: 20 }), 'app_actions_list');
  const matches = r.json?.matches || [];
  assert(matches.some((m) => m.action === 'share_text'), r.text.slice(0, 500));
  assert(matches.every((m) => m.package !== BRIDGE), 'PhoneBridge itself must never be listed');
  assert(/app_action_run/.test(r.json.how_to_run || ''), r.text.slice(0, 300));
  return matches.slice(0, 5).map((m) => `${m.app}/${m.action}`).join(', ');
});

await step('app actions: app_action_run opens Settings (discovered "open")', async () => {
  shell('input keyevent KEYCODE_HOME');
  await sleep(500);
  const r = ok(await c.call('app_action_run', { app: 'com.android.settings', action: 'open' }), 'app_action_run');
  assert(r.json?.done === true && r.json?.package === 'com.android.settings', r.text);
  const top = await waitFor(() => (/com\.android\.settings/.test(topActivity()) ? topActivity() : null), { what: 'Settings in front' });
  shell('input keyevent KEYCODE_HOME');
  return `${r.text.slice(0, 200)}\n top: ${top}`;
});

await step('app actions: a local plugin asks for approval (unreviewed) and sends with a screen recipe', async () => {
  try {
    execFileSync(PLUGIN_PUSH, [FAKEMAIL_PLUGIN], { encoding: 'utf8', timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    skip(`plugin-push.sh failed (needs a debug build): ${String(e.stderr || e.message).trim().slice(0, 200)}`);
  }
  const list = ok(await c.call('app_actions_list', { app: MAIL }), 'app_actions_list');
  if (list.json?.integration !== 'local') skip('turn on Settings > Developer > Local plugins on the phone to run this scenario');
  assert(list.json.actions.some((a) => a.action === 'send_email'), list.text.slice(0, 400));
  assert(/review/i.test(list.json.note || ''), `no unreviewed-plugin note: ${list.text.slice(0, 300)}`);

  shell('input keyevent KEYCODE_HOME');
  const t0 = Date.now();
  const subject = `Plugin test ${t0}`;
  const pending = c.call('app_action_run', {
    app: MAIL, action: 'send_email', params: { to: 'bob@example.com', subject, body: 'Sent by a local plugin' },
  });
  // Local plugins always ask, whatever the token's approval policy, and the prompt says the plugin is unreviewed.
  await waitFor(() => /unreviewed plugin/i.test(shell("dumpsys notification --noredact | grep -i 'unreviewed plugin' || true")), {
    what: 'approval prompt for the unreviewed plugin', timeoutMs: 20_000,
  });
  await approveOnPhone('wants to:');
  const r = ok(await pending, 'app_action_run send_email');
  assert(r.json?.done === true && r.json?.steps_run >= 8, r.text);
  const ev = await waitFor(() => lastEvent(MAIL, 'EMAIL_SENT', t0), { what: 'fixture EMAIL_SENT event' });
  assert(ev.to.includes('bob@example.com') && ev.subject === subject && ev.body === 'Sent by a local plugin', JSON.stringify(ev));
  shell('input keyevent KEYCODE_HOME');
  return `${r.text.slice(0, 300)}\n fixture recorded: ${JSON.stringify(ev)}`;
});

await step('app actions: blocked apps are refused and never listed', async () => {
  // PhoneBridge itself is on every agent's never-allow list.
  const run = await c.call('app_action_run', { app: BRIDGE, action: 'open' });
  assert(run.isError && /blocked|never-allow/i.test(run.text), run.text);
  const list = await c.call('app_actions_list', { app: BRIDGE });
  assert(list.isError && /blocked|never-allow/i.test(list.text), list.text);
  const blocked = process.env.PB_BLOCKED_APP;
  if (blocked) {
    const r = await c.call('app_action_run', { app: blocked, action: 'open' });
    assert(r.isError && /blocked/i.test(r.text), `expected a refusal for ${blocked}: ${r.text}`);
    const apps = ok(await c.call('apps_list', {}), 'apps_list');
    assert(!apps.json?.some((a) => a.package === blocked), `${blocked} is listed`);
    const q = ok(await c.call('app_actions_list', { query: 'open', limit: 50 }), 'app_actions_list');
    assert(!(q.json?.matches || []).some((m) => m.package === blocked), `${blocked} has listed actions`);
  }
  return run.text.slice(0, 200);
});

console.log();
process.exit(run.summary() ? 0 : 1);
