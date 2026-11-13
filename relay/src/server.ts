#!/usr/bin/env node
/**
 * CLI entry point: reads the configuration from the environment (see README.md), starts the relay
 * and shuts it down gracefully on SIGINT/SIGTERM.
 */
import { ConfigError, loadConfigFromEnv, type RelayConfig } from './config.js';
import { createLogger, errorMessage } from './logger.js';
import { createRelay } from './relay.js';

/** Hard deadline for a graceful shutdown before the process exits anyway. */
const SHUTDOWN_DEADLINE_MS = 10_000;

async function main(): Promise<void> {
  let config: RelayConfig;
  try {
    config = loadConfigFromEnv(process.env);
  } catch (err) {
    if (!(err instanceof ConfigError)) throw err;
    process.stderr.write(`phonebridge-relay: invalid configuration: ${err.message}\n`);
    process.exit(1);
  }

  const logger = createLogger(config.logLevel);
  const relay = await createRelay(config, { logger });
  logger.info('relay listening', {
    url: relay.url,
    publicUrl: config.publicUrl ?? '(derived from Host header)',
    trustProxy: config.trustProxy,
    accessKey: config.accessKey === null ? 'off' : 'required',
    tokenInUrl: config.allowTokenInUrl,
    allowedOrigins: config.allowedOrigins.join(',') || '(none)',
  });

  let stopping = false;
  const shutdown = (signal: NodeJS.Signals): void => {
    if (stopping) {
      logger.warn('second signal, exiting immediately', { signal });
      process.exit(1);
    }
    stopping = true;
    logger.info('shutting down', { signal });
    setTimeout(() => {
      logger.error('graceful shutdown timed out');
      process.exit(1);
    }, SHUTDOWN_DEADLINE_MS).unref();
    relay.close().then(
      // Let buffered log lines reach a piped stdout before exiting.
      () => process.stdout.write('', () => process.exit(0)),
      (err: unknown) => {
        logger.error('shutdown failed', { error: errorMessage(err) });
        process.exit(1);
      },
    );
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err: unknown) => {
  process.stderr.write(`phonebridge-relay: failed to start: ${errorMessage(err)}\n`);
  process.exit(1);
});
