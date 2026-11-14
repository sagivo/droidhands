import { randomBytes } from 'node:crypto';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { RelayConfig } from './config.js';
import { errorMessage, type Logger } from './logger.js';

/** File name of the persisted secret inside DATA_DIR. */
export const RELAY_SECRET_FILE = 'relay-secret';

/** Bytes of entropy in a generated secret. */
const GENERATED_SECRET_BYTES = 32;

/** Where the secret came from (for the startup log; the secret itself is never logged). */
export type RelaySecretSource = 'env' | 'file' | 'generated' | 'temporary';

export interface LoadedRelaySecret {
  readonly key: Buffer;
  readonly source: RelaySecretSource;
}

/**
 * Resolves the HMAC key that signs OAuth client ids (docs/PROTOCOL.md §5.2):
 *
 * 1. `RELAY_SECRET` ([RelayConfig.relaySecret]) when set;
 * 2. otherwise `{DATA_DIR}/relay-secret`, created on first start (directory 0700, file 0600);
 * 3. if that is impossible (read-only disk, permissions, malformed file), a random in-memory secret,
 *    with a warning: agents registered now must reconnect after the next restart.
 *
 * Never throws.
 */
export async function loadRelaySecret(config: RelayConfig, logger: Logger): Promise<LoadedRelaySecret> {
  if (config.relaySecret !== null) return { key: Buffer.from(config.relaySecret, 'utf8'), source: 'env' };
  if (config.dataDir !== null) {
    try {
      return await readOrCreate(config.dataDir, logger);
    } catch (err) {
      logger.warn('could not persist the relay secret; using a temporary one', {
        dataDir: config.dataDir,
        error: errorMessage(err),
        hint: 'set RELAY_SECRET, or make DATA_DIR writable, so connected AI apps survive a relay restart',
      });
    }
  } else {
    logger.warn('no RELAY_SECRET and no DATA_DIR; using a temporary relay secret', {
      hint: 'AI apps connected now will have to reconnect after the relay restarts',
    });
  }
  return { key: randomBytes(GENERATED_SECRET_BYTES), source: 'temporary' };
}

async function readOrCreate(dataDir: string, logger: Logger): Promise<LoadedRelaySecret> {
  const file = join(dataDir, RELAY_SECRET_FILE);
  const existing = await readSecretFile(file, logger);
  if (existing !== null) return { key: existing, source: 'file' };

  await mkdir(dataDir, { recursive: true, mode: 0o700 });
  const encoded = randomBytes(GENERATED_SECRET_BYTES).toString('base64url');
  try {
    // 'wx': never overwrite a secret another process created in the meantime.
    await writeFile(file, `${encoded}\n`, { mode: 0o600, flag: 'wx' });
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err;
    const raced = await readSecretFile(file, logger);
    if (raced === null) throw new Error(`${file} vanished while being created`);
    return { key: raced, source: 'file' };
  }
  logger.info('generated a new relay secret', { file });
  return { key: Buffer.from(encoded, 'utf8'), source: 'generated' };
}

/** The secret in [file], or null when the file does not exist. Throws when it is unusable. */
async function readSecretFile(file: string, logger: Logger): Promise<Buffer | null> {
  let text: string;
  try {
    text = await readFile(file, 'utf8');
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null;
    throw err;
  }
  const secret = text.trim();
  if (secret.length < 32) throw new Error(`${file} is malformed (expected at least 32 characters)`);
  const info = await stat(file);
  if (process.platform !== 'win32' && (info.mode & 0o077) !== 0) {
    logger.warn('relay secret file is readable by other users', { file, hint: `chmod 600 ${file}` });
  }
  return Buffer.from(secret, 'utf8');
}
