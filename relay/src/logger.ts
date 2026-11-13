/**
 * Minimal structured logger: one JSON object per line.
 *
 * Callers must never pass tokens, device secrets, access keys or request/response bodies as fields.
 * Device ids are logged as a 6-char prefix (see [deviceTag]).
 */

export const LOG_LEVELS = ['debug', 'info', 'warn', 'error', 'silent'] as const;
export type LogLevel = (typeof LOG_LEVELS)[number];

export type LogFields = Record<string, string | number | boolean | null | undefined>;

export interface Logger {
  debug(msg: string, fields?: LogFields): void;
  info(msg: string, fields?: LogFields): void;
  warn(msg: string, fields?: LogFields): void;
  error(msg: string, fields?: LogFields): void;
}

const SEVERITY: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40, silent: 100 };

/** Creates a logger that writes lines at or above [level] to [sink] (stdout by default). */
export function createLogger(
  level: LogLevel,
  sink: (line: string) => void = (line) => process.stdout.write(`${line}\n`),
): Logger {
  const threshold = SEVERITY[level];
  const emit = (lvl: Exclude<LogLevel, 'silent'>, msg: string, fields?: LogFields): void => {
    if (SEVERITY[lvl] < threshold) return;
    sink(JSON.stringify({ time: new Date().toISOString(), level: lvl, msg, ...fields }));
  };
  return {
    debug: (msg, fields) => emit('debug', msg, fields),
    info: (msg, fields) => emit('info', msg, fields),
    warn: (msg, fields) => emit('warn', msg, fields),
    error: (msg, fields) => emit('error', msg, fields),
  };
}

/** The loggable form of a device id: its first 6 characters. */
export function deviceTag(deviceId: string): string {
  return deviceId.slice(0, 6);
}

/** Message of an unknown thrown value, without stack traces or attached objects. */
export function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
