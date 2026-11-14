// Shared plumbing of the conformance runners: one table of relay settings (applied to whichever
// relay is started, and announced to the suite), free ports, child processes and the suite run.
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:net';
import { fileURLToPath } from 'node:url';

export const RELAY_DIR = fileURLToPath(new URL('..', import.meta.url));
const SUITE = fileURLToPath(new URL('./suite.test.mjs', import.meta.url));

/**
 * Environment variables for the relay under test. Short timeouts make the timeout, expiry and cap
 * tests fast; high rate limits keep a whole suite run from one address under budget.
 */
export function relaySettings() {
  return {
    RELAY_SECRET: randomBytes(32).toString('base64url'),
    RELAY_ACCESS_KEY: `conformance-${randomBytes(6).toString('hex')}`,
    REQUEST_TIMEOUT_MS: '1500',
    HELLO_TIMEOUT_MS: '1000',
    MAX_INFLIGHT_PER_DEVICE: '3',
    MAX_BODY_BYTES: '65536',
    PAIRING_TTL_MS: '2500',
    AUTH_CODE_TTL_MS: '2500',
    ALLOWED_ORIGINS: 'https://allowed.example',
    ALLOW_TOKEN_IN_URL: 'true',
    RATE_LIMIT_REQUESTS_PER_MIN: '100000',
    RATE_LIMIT_AUTH_FAILURES_PER_MIN: '100000',
    LOG_LEVEL: 'warn',
    SETUP_BANNER: 'false',
  };
}

/** What the suite may assume about a relay started with [settings]. */
export function suiteEnv(baseUrl, settings, extra = {}) {
  return {
    RELAY_URL: baseUrl,
    CONFORMANCE_ACCESS_KEY: settings.RELAY_ACCESS_KEY,
    CONFORMANCE_REQUEST_TIMEOUT_MS: settings.REQUEST_TIMEOUT_MS,
    CONFORMANCE_HELLO_TIMEOUT_MS: settings.HELLO_TIMEOUT_MS,
    CONFORMANCE_MAX_INFLIGHT: settings.MAX_INFLIGHT_PER_DEVICE,
    CONFORMANCE_MAX_BODY_BYTES: settings.MAX_BODY_BYTES,
    CONFORMANCE_PAIRING_TTL_MS: settings.PAIRING_TTL_MS,
    CONFORMANCE_CODE_TTL_MS: settings.AUTH_CODE_TTL_MS,
    CONFORMANCE_ALLOWED_ORIGIN: settings.ALLOWED_ORIGINS,
    CONFORMANCE_TOKEN_IN_URL: settings.ALLOW_TOKEN_IN_URL,
    ...extra,
  };
}

export function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
  });
}

/** Starts [command] in its own process group; its output is kept and shown if the run fails. */
export function start(command, args, { env, cwd = RELAY_DIR, name }) {
  const child = spawn(command, args, { cwd, env: { ...process.env, ...env }, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const output = [];
  const keep = (chunk) => {
    output.push(String(chunk));
    if (output.length > 400) output.shift();
  };
  child.stdout.on('data', keep);
  child.stderr.on('data', keep);
  let exited = false;
  child.on('exit', () => (exited = true));
  const stop = () =>
    new Promise((resolve) => {
      if (exited) return resolve();
      child.once('exit', () => resolve());
      try {
        process.kill(-child.pid, 'SIGTERM');
      } catch {
        resolve();
      }
      setTimeout(() => {
        try {
          process.kill(-child.pid, 'SIGKILL');
        } catch {
          // Gone.
        }
      }, 5_000).unref();
    });
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => stop().then(() => process.exit(130)));
  }
  return { child, stop, output: () => output.join(''), exited: () => exited, name };
}

/** Polls `${baseUrl}/healthz` until it answers 200. */
export async function waitForHealth(baseUrl, proc, timeoutMs = 60_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (proc.exited()) throw new Error(`${proc.name} exited before it was ready:\n${proc.output()}`);
    try {
      const res = await fetch(`${baseUrl}/healthz`, { signal: AbortSignal.timeout(2_000) });
      if (res.status === 200) return;
    } catch {
      // Not listening yet.
    }
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`${proc.name} did not become ready within ${timeoutMs} ms:\n${proc.output()}`);
}

/** Runs the suite against [env.RELAY_URL]; resolves with its exit code. */
export function runSuite(env) {
  return new Promise((resolve) => {
    const extra = process.argv.slice(2);
    const child = spawn(process.execPath, ['--test', '--test-concurrency=1', ...extra, SUITE], {
      cwd: RELAY_DIR,
      env: { ...process.env, ...env },
      stdio: 'inherit',
    });
    child.on('exit', (code) => resolve(code ?? 1));
  });
}

/** Starts a relay with [launch], runs the suite against it, stops it and exits with the result. */
export async function main(name, launch) {
  let proc = null;
  let code = 1;
  try {
    const settings = relaySettings();
    const started = await launch(settings);
    proc = started.proc;
    await waitForHealth(started.baseUrl, proc);
    console.log(`conformance: ${name} relay ready at ${started.baseUrl}`);
    code = await runSuite(suiteEnv(started.baseUrl, settings, started.suiteExtra));
    if (code !== 0) console.error(`\n--- ${name} relay output (tail) ---\n${proc.output().slice(-8_000)}`);
  } catch (err) {
    console.error(`conformance: ${err instanceof Error ? err.message : err}`);
  } finally {
    await proc?.stop();
    await runCleanups();
  }
  console.log(`conformance: ${name} ${code === 0 ? 'passed' : 'FAILED'}`);
  process.exit(code);
}

const cleanups = [];
/** Registers [fn] to run after the relay stopped. */
export function onCleanup(fn) {
  cleanups.push(fn);
}
async function runCleanups() {
  for (const fn of cleanups.splice(0)) await fn();
}
