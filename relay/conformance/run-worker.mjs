#!/usr/bin/env node
// Runs the conformance suite against the Cloudflare Worker relay in `wrangler dev --local`
// (workerd + Miniflare, nothing is deployed). The settings, including a throwaway RELAY_SECRET,
// go into a .dev.vars file generated in a temporary directory under .wrangler/ and passed with
// --env-file, so a developer's own relay/.dev.vars is never touched. Extra arguments go to
// `node --test`.
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { RELAY_DIR, freePort, main, onCleanup, start } from './runner.mjs';

const APK_URL = 'https://downloads.example.com/phonebridge.apk';

await main('worker', async (settings) => {
  const port = await freePort();
  const inspectorPort = await freePort();
  const dir = join(RELAY_DIR, '.wrangler', `conformance-${process.pid}`);
  mkdirSync(dir, { recursive: true });
  onCleanup(() => rmSync(dir, { recursive: true, force: true }));
  const vars = { ...settings, APK_URL };
  const devVars = join(dir, '.dev.vars');
  writeFileSync(devVars, `${Object.entries(vars).map(([k, v]) => `${k}=${JSON.stringify(v)}`).join('\n')}\n`, { mode: 0o600 });

  const wrangler = join(RELAY_DIR, 'node_modules', '.bin', 'wrangler');
  const proc = start(
    wrangler,
    [
      'dev',
      '--local',
      '--ip',
      '127.0.0.1',
      '--port',
      String(port),
      '--inspector-port',
      String(inspectorPort),
      '--env-file',
      devVars,
      '--persist-to',
      join(dir, 'state'),
      '--show-interactive-dev-session=false',
      '--log-level',
      'warn',
    ],
    { name: 'worker', env: { WRANGLER_SEND_METRICS: 'false', NO_COLOR: '1' } },
  );
  return { proc, baseUrl: `http://127.0.0.1:${port}`, suiteExtra: { CONFORMANCE_APK_URL: APK_URL } };
});
