#!/usr/bin/env node
// End-to-end use-case scenarios against a real PhoneBridge device (emulator + fixture apps).
// usage: PB_URL=http://127.0.0.1:8787/mcp PB_TOKEN=pb1... node scripts/e2e/scenarios.mjs [filter]
import { McpClient, Runner, adb, shell, sleep, waitFor, fixtureEvents, assert } from './lib.mjs';

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
  // dumpsys redacts numbers, so count Telecom's SET_DIALING events before/after.
  const dialings = () => (shell('dumpsys telecom').match(/SET_DIALING/g) || []).length;
  const before = dialings();
  const r = ok(await c.call('phone_call', { contact: 'Bob' }), 'phone_call');
  assert(r.json?.to === '+16505550100', r.text);
  await waitFor(() => dialings() > before, { what: 'Telecom SET_DIALING', timeoutMs: 15000 });
  shell('input keyevent KEYCODE_ENDCALL');
  await sleep(1000);
  // ENDCALL with no call in progress (the emulator modem may already have dropped it) turns the screen off.
  shell('input keyevent KEYCODE_WAKEUP');
  shell('wm dismiss-keyguard');
  return `${r.text}\n Telecom: outgoing call reached DIALING`;
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

console.log();
process.exit(run.summary() ? 0 : 1);
