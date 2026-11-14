import assert from 'node:assert/strict';
import { chmod, mkdir, mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { resolveConfig } from '../src/config.js';
import { createLogger } from '../src/logger.js';
import { RELAY_SECRET_FILE, loadRelaySecret } from '../src/relaySecret.js';

function capture(): { lines: string[]; logger: ReturnType<typeof createLogger> } {
  const lines: string[] = [];
  return { lines, logger: createLogger('debug', (line) => lines.push(line)) };
}

async function tempDir(t: { after: (fn: () => Promise<void>) => void }): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'pb-secret-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

test('RELAY_SECRET wins and is never logged', async () => {
  const secret = 'an-operator-provided-secret-of-sufficient-length';
  const { lines, logger } = capture();
  const loaded = await loadRelaySecret(resolveConfig({ relaySecret: secret, dataDir: '/nonexistent' }), logger);
  assert.equal(loaded.source, 'env');
  assert.equal(loaded.key.toString('utf8'), secret);
  assert.ok(!lines.join('\n').includes(secret));
});

test('generated once into DATA_DIR (0700 dir, 0600 file), then reused', async (t) => {
  const dataDir = join(await tempDir(t), 'nested', 'data');
  const { lines, logger } = capture();
  const first = await loadRelaySecret(resolveConfig({ dataDir }), logger);
  assert.equal(first.source, 'generated');
  const file = join(dataDir, RELAY_SECRET_FILE);
  if (process.platform !== 'win32') {
    assert.equal((await stat(dataDir)).mode & 0o777, 0o700);
    assert.equal((await stat(file)).mode & 0o777, 0o600);
  }
  const onDisk = (await readFile(file, 'utf8')).trim();
  assert.match(onDisk, /^[A-Za-z0-9_-]{43}$/);
  assert.ok(!lines.join('\n').includes(onDisk), 'the secret is not logged');

  const second = await loadRelaySecret(resolveConfig({ dataDir }), logger);
  assert.equal(second.source, 'file');
  assert.deepEqual(second.key, first.key);
});

test('an unusable DATA_DIR falls back to a temporary secret with a warning', async (t) => {
  const root = await tempDir(t);
  const blocker = join(root, 'not-a-dir');
  await writeFile(blocker, 'x');
  const { lines, logger } = capture();
  const loaded = await loadRelaySecret(resolveConfig({ dataDir: join(blocker, 'data') }), logger);
  assert.equal(loaded.source, 'temporary');
  assert.equal(loaded.key.length, 32);
  assert.match(lines.join('\n'), /"level":"warn".*temporary/);

  const malformed = join(root, 'malformed');
  await mkdir(malformed);
  await writeFile(join(malformed, RELAY_SECRET_FILE), 'short\n');
  assert.equal((await loadRelaySecret(resolveConfig({ dataDir: malformed }), logger)).source, 'temporary');
  assert.equal((await readFile(join(malformed, RELAY_SECRET_FILE), 'utf8')), 'short\n', 'never overwritten');

  const none = await loadRelaySecret(resolveConfig({ dataDir: null }), logger);
  assert.equal(none.source, 'temporary');
});

test('a world-readable secret file is used but warned about', { skip: process.platform === 'win32' }, async (t) => {
  const dataDir = await tempDir(t);
  const file = join(dataDir, RELAY_SECRET_FILE);
  await writeFile(file, `${'s'.repeat(43)}\n`);
  await chmod(file, 0o644);
  const { lines, logger } = capture();
  const loaded = await loadRelaySecret(resolveConfig({ dataDir }), logger);
  assert.equal(loaded.source, 'file');
  assert.match(lines.join('\n'), /readable by other users/);
});
