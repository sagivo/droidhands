#!/usr/bin/env node
// Runs the conformance suite against the Node relay (build it first: `npm run conformance:node`
// does). Extra arguments go to `node --test` (e.g. --test-name-pattern=pairing).
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { RELAY_DIR, freePort, main, start } from './runner.mjs';

await main('node', async (settings) => {
  if (!existsSync(join(RELAY_DIR, 'dist', 'relay.js'))) throw new Error('dist/ is missing: run `npm run build` first');
  const port = await freePort();
  const proc = start(process.execPath, ['conformance/node-relay.mjs'], {
    name: 'node',
    env: { ...settings, PORT: String(port), HOST: '127.0.0.1', DATA_DIR: '' },
  });
  return { proc, baseUrl: `http://127.0.0.1:${port}` };
});
