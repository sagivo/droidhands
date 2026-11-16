#!/usr/bin/env node
// End-to-end use-case scenarios against a real DroidHands device (emulator + fixture apps).
// usage: PB_URL=http://127.0.0.1:8787/mcp PB_TOKEN=pb1... node scripts/e2e/scenarios.mjs [filter]
// App-action scenarios ("app actions: ..."): the local-plugin one pushes clients/android/fixtures/plugins/fakemail_test
// with scripts/plugin-push.sh (debug build) and needs Settings > Developer > Local plugins on; it skips otherwise.
// PB_BLOCKED_APP=<package> (an app blocked for this token) adds a check with that app; DroidHands itself is always used.
// PB_APPROVE=manual: don't tap approval prompts with uiautomator (it suspends accessibility services while it runs).
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
const MAIL = 'com.droidhands.fixture.mail';
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

// Phone use, the way an agent does it: no app-specific tool, only what the app declares plus the screen.
/** The ref of the first actionable element in a screen_observe text whose line matches [re]. */
const refOf = (obs, re) => obs.text.split('\n').find((l) => /^n\d+ /.test(l) && re.test(l))?.split(' ')[0];

await step('USE CASE: send a WhatsApp message to "Tania" (discovered actions + phone use)', async () => {
  const who = ok(await c.call('contacts_search', { query: 'Tania' }), 'contacts_search').json?.[0];
  const phone = (who?.phones?.find((p) => p.normalized)?.normalized || '').replace('+', '');
  assert(phone, `no number for Tania: ${JSON.stringify(who)}`);
  const list = ok(await c.call('app_actions_list', { app: 'WhatsApp' }), 'app_actions_list');
  const link = list.json?.actions?.find((a) => a.action === 'open_link');
  assert(link && /wa\.me/.test(link.description), `open_link should list the links WhatsApp declares: ${list.text.slice(0, 600)}`);
  const t0 = Date.now();
  ok(await c.call('app_action_run', { app: 'WhatsApp', action: 'open_link', params: { url: `https://wa.me/${phone}?text=${encodeURIComponent("I'll be 10 minutes late 🙏")}` } }), 'open_link');
  await waitFor(() => lastEvent(WA, 'CHAT_OPENED', t0), { what: 'fixture CHAT_OPENED event' });
  const obs = ok(await c.call('screen_observe', {}), 'screen_observe');
  assert(obs.images[0]?.data?.length > 1000, 'screen_observe returned no screenshot');
  const send = refOf(obs, /send/i);
  assert(send, `no Send element in:\n${obs.text.slice(0, 1200)}`);
  const tap = ok(await c.call('ui_tap', { ref: send }), 'ui_tap Send');
  assert(/Screen changed|did not visibly change/.test(tap.text), `no change report: ${tap.text}`);
  const ev = await waitFor(() => lastEvent(WA, 'SENT', t0), { what: 'fixture SENT event' });
  assert(ev.phone === phone && ev.text === "I'll be 10 minutes late 🙏", JSON.stringify(ev));
  return `${tap.text}\n fixture recorded: ${JSON.stringify(ev)}`;
});

await step('phone use: a number not on WhatsApp is visible on screen', async () => {
  ok(await c.call('app_action_run', { app: 'WhatsApp', action: 'draft_message', params: { phone: '15550000', text: 'hi' } }), 'draft_message');
  await sleep(1500);
  const obs = ok(await c.call('screen_observe', { screenshot: false, elements: 'all' }), 'screen_observe');
  assert(/isn.?t on WhatsApp/i.test(obs.text), obs.text.slice(0, 800));
  const dismiss = ok(await c.call('ui_tap', { text: 'OK' }), 'ui_tap OK');
  shell('input keyevent KEYCODE_HOME');
  return dismiss.text.slice(0, 200);
});

await step('phone use: tap a point of the screenshot (space=screenshot)', async () => {
  const t0 = Date.now();
  ok(await c.call('app_action_run', { app: 'WhatsApp', action: 'open_link', params: { url: 'https://wa.me/972541234567?text=From%20pixels' } }), 'open_link');
  await waitFor(() => lastEvent(WA, 'CHAT_OPENED', t0), { what: 'CHAT_OPENED' });
  const obs = ok(await c.call('screen_observe', {}), 'screen_observe');
  const m = /Screenshot (\d+)x(\d+) \(screen (\d+)x(\d+)/.exec(obs.text);
  assert(m, obs.text.slice(0, 300));
  const line = obs.text.split('\n').find((l) => /^n\d+ /.test(l) && /send/i.test(l));
  const [x, y, w, h] = /\((\d+),(\d+),(\d+),(\d+)\)$/.exec(line).slice(1).map(Number);
  const scale = Number(m[1]) / Number(m[3]);
  const r = ok(await c.call('ui_tap', { x: Math.round((x + w / 2) * scale), y: Math.round((y + h / 2) * scale), space: 'screenshot', observe: true }), 'ui_tap');
  assert(r.images.length === 1, 'observe=true should return a screenshot');
  const ev = await waitFor(() => lastEvent(WA, 'SENT', t0), { what: 'SENT' });
  assert(ev.text === 'From pixels', JSON.stringify(ev));
  shell('input keyevent KEYCODE_HOME');
  return r.text.split('\n').slice(0, 3).join(' | ');
});

await step('USE CASE: write and send an email (discovered compose_mailto + phone use)', async () => {
  const list = ok(await c.call('app_actions_list', { app: 'Fixture Mail' }), 'app_actions_list');
  assert(list.json?.actions?.some((a) => a.action === 'compose_mailto'), list.text.slice(0, 500));
  const t0 = Date.now();
  ok(await c.call('app_action_run', { app: 'Fixture Mail', action: 'compose_mailto', params: { to: 'dana@example.com', subject: 'Lunch', text: 'Free on Thursday?' } }), 'compose_mailto');
  await sleep(1200);
  const obs = ok(await c.call('screen_observe', {}), 'screen_observe');
  const send = refOf(obs, /send/i);
  assert(send, obs.text.slice(0, 1200));
  ok(await c.call('ui_tap', { ref: send }), 'ui_tap Send');
  const ev = await waitFor(() => lastEvent(MAIL, 'EMAIL_SENT', t0), { what: 'EMAIL_SENT' });
  assert(ev.to.includes('dana@example.com') && ev.subject === 'Lunch' && /Thursday/.test(ev.body), JSON.stringify(ev));
  shell('input keyevent KEYCODE_HOME');
  return JSON.stringify(ev);
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

const BRIDGE = 'com.droidhands.android';
const PLUGIN_PUSH = new URL('../plugin-push.sh', import.meta.url).pathname;
const FAKEMAIL_PLUGIN = new URL('../../clients/android/fixtures/plugins/fakemail_test', import.meta.url).pathname;
const UI = new URL('./ui.py', import.meta.url).pathname;
const ui = (...a) => execFileSync('timeout', ['-k', '2', '60', 'python3', '-I', UI, ...a], { encoding: 'utf8' }).trim();

/**
 * Approves the pending DroidHands prompt whose title contains `title`: opens it from the shade, then taps Allow.
 * The prompt's countdown can keep uiautomator from reading the shade; then it asks for a tap on the phone.
 */
async function approveOnPhone(title) {
  shell('cmd statusbar expand-notifications');
  // uiautomator suspends every accessibility service (DroidHands's too) while it reads the screen, which can make
  // the approved action fail. PB_APPROVE=manual leaves the tap to a person (or a screenshot-and-tap helper).
  if (process.env.PB_APPROVE === 'manual') {
    console.log(`    → tap Allow on the phone ("${title}")`);
    return;
  }
  await sleep(1500);
  try {
    ui('tap', title);
    await sleep(1500); // Allow is enabled shortly after the approval screen appears.
    ui('tap', 'Allow');
  } catch {
    console.log(`    → could not tap the prompt automatically: tap Allow on the phone ("${title}")`);
  }
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

await step('app actions: app_actions_list for WhatsApp shows the bundled plugin, checked against the installed app', async () => {
  const r = ok(await c.call('app_actions_list', { app: WA }), 'app_actions_list');
  if (r.json?.integration !== 'plugin') skip(`this build has no bundled WhatsApp plugin (integration: ${r.json?.integration})`);
  const draft = r.json.actions.find((a) => a.action === 'draft_message');
  assert(draft && draft.available !== false, `draft_message should be available: ${JSON.stringify(draft)}`);
  return `${draft.title}: ${JSON.stringify(draft.params).slice(0, 200)}`;
});

await step('app actions: app_actions_list(query) finds which app can do something', async () => {
  const r = ok(await c.call('app_actions_list', { query: 'share text', limit: 20 }), 'app_actions_list');
  const matches = r.json?.matches || [];
  assert(matches.some((m) => m.action === 'share_text'), r.text.slice(0, 500));
  assert(matches.every((m) => m.package !== BRIDGE), 'DroidHands itself must never be listed');
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

await step('app actions: a plugin link the installed app doesn\'t handle is unavailable, not attempted', async () => {
  const list = ok(await c.call('app_actions_list', { app: MAIL }), 'app_actions_list');
  if (list.json?.integration !== 'local') skip('needs the local fakemail_test plugin (see the scenario above)');
  const stale = list.json.actions.find((a) => a.action === 'open_folder');
  assert(stale?.available === false && /another version/.test(stale.unavailable_reason || ''), JSON.stringify(stale));
  const run = await c.call('app_action_run', { app: MAIL, action: 'open_folder', params: { folder: 'inbox' } });
  assert(run.isError && /can.t run right now/.test(run.text), run.text);
  return stale.unavailable_reason;
});

await step('app actions: a recipe that fails on this app version is recorded (health)', async () => {
  const list = ok(await c.call('app_actions_list', { app: MAIL }), 'app_actions_list');
  if (list.json?.integration !== 'local') skip('needs the local fakemail_test plugin');
  for (let i = 0; i < 2; i++) {
    const pending = c.call('app_action_run', { app: MAIL, action: 'archive_all' });
    await sleep(2500);
    await approveOnPhone('wants to:');
    const r = await pending;
    assert(r.isError && /Step 2 of 2/.test(r.text), r.text);
    if (i === 1) assert(/failed 2 times in a row/.test(r.text), r.text);
  }
  const after = ok(await c.call('app_actions_list', { app: MAIL }), 'app_actions_list');
  const health = after.json.actions.find((a) => a.action === 'archive_all')?.health;
  assert(health?.status === 'failing' && health.failures === 2, JSON.stringify(health));
  shell('input keyevent KEYCODE_HOME');
  return JSON.stringify(health);
});

await step('app actions: blocked apps are refused and never listed', async () => {
  // DroidHands itself is on every agent's never-allow list.
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
