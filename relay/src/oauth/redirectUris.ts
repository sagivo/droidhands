/**
 * Redirect URI rules for dynamically registered public clients (docs/PROTOCOL.md §5.2). Pure.
 *
 * Accepted:
 * * `https://<host>/…`
 * * loopback `http://localhost|127.0.0.1|[::1][:<port>]/…` (native apps, RFC 8252 §7.3). The port is
 *   ignored when matching an authorization request against the registration ([matchRedirectUri]).
 * * private-use schemes, e.g. `com.example.app:/cb` (RFC 8252 §7.1) or `cursor://anysphere.cursor-mcp/cb`
 *
 * Never: fragments, credentials, other `http://` hosts, or schemes that execute, read local data or
 * hand off to other apps/protocols ([DENIED_SCHEMES]: `javascript:`, `data:`, `file:`, `intent:`, …).
 */

/** Longest accepted redirect URI. */
export const MAX_REDIRECT_URI_LENGTH = 512;

const LOOPBACK_HOSTS: ReadonlySet<string> = new Set(['localhost', '127.0.0.1', '[::1]']);

/** Schemes that could run script, read local data or reach other handlers/protocols. */
const DENIED_SCHEMES: ReadonlySet<string> = new Set([
  'javascript:', 'vbscript:', 'data:', 'file:', 'blob:', 'about:', 'filesystem:', 'view-source:', 'jar:',
  'intent:', 'android-app:', 'content:', 'chrome:', 'chrome-extension:', 'moz-extension:', 'resource:',
  'ws:', 'wss:', 'ftp:', 'ftps:', 'sftp:', 'ssh:', 'telnet:', 'smb:', 'mailto:', 'tel:', 'sms:', 'mms:',
  'geo:', 'market:', 'itms:', 'itms-apps:', 'ms-settings:',
]);

/** RFC 3986 scheme syntax. */
const SCHEME_REGEX = /^[a-z][a-z0-9+.-]*:$/;

export type RedirectUriKind = 'https' | 'loopback' | 'private_use';

/** Why [uri] is not an acceptable redirect URI (phrased for an `error_description`), or null. */
export function redirectUriProblem(uri: string): string | null {
  return classifyRedirectUri(uri).problem;
}

/** Classifies [uri]; exactly one of `kind` / `problem` is non-null. */
export function classifyRedirectUri(uri: string): { kind: RedirectUriKind | null; problem: string | null } {
  const fail = (problem: string): { kind: null; problem: string } => ({ kind: null, problem });
  if (uri.length === 0 || uri.length > MAX_REDIRECT_URI_LENGTH) {
    return fail(`redirect URIs must be 1-${MAX_REDIRECT_URI_LENGTH} characters`);
  }
  // Control characters and whitespace would be silently stripped or encoded by URL parsing.
  if (/[\s\x00-\x1f\x7f]/.test(uri)) return fail('redirect URIs must not contain whitespace or control characters');
  let url: URL;
  try {
    url = new URL(uri);
  } catch {
    return fail(`not an absolute URI: ${uri}`);
  }
  if (uri.includes('#')) return fail('redirect URIs must not contain a fragment');
  if (url.username !== '' || url.password !== '') return fail('redirect URIs must not contain credentials');

  const scheme = url.protocol.toLowerCase();
  if (scheme === 'https:') {
    if (url.hostname === '') return fail('https redirect URIs need a host');
    return { kind: 'https', problem: null };
  }
  if (scheme === 'http:') {
    if (!LOOPBACK_HOSTS.has(url.hostname)) {
      return fail('http redirect URIs are only allowed for localhost, 127.0.0.1 or [::1]; use https');
    }
    return { kind: 'loopback', problem: null };
  }
  if (!SCHEME_REGEX.test(scheme) || DENIED_SCHEMES.has(scheme)) {
    return fail('custom-scheme redirect URIs must use the app\'s own scheme, such as com.example.app:/callback');
  }
  return { kind: 'private_use', problem: null };
}

/**
 * True when [requested] may be used by a client that registered [registered]: an exact match, or
 * for loopback URIs the same host, path and query on any port (or none). Native apps bind a free
 * port per run, so RFC 8252 §7.3 requires ignoring it.
 */
export function matchRedirectUri(registered: readonly string[], requested: string): boolean {
  if (registered.includes(requested)) return true;
  const wanted = loopbackWithoutPort(requested);
  return wanted !== null && registered.some((uri) => loopbackWithoutPort(uri) === wanted);
}

/** A valid loopback redirect URI with its port removed, or null for any other URI. */
function loopbackWithoutPort(uri: string): string | null {
  if (classifyRedirectUri(uri).kind !== 'loopback') return null;
  const url = new URL(uri);
  url.port = '';
  return url.toString();
}

/**
 * What the phone shows as "returns to":the host for https, host:port for loopback, the scheme for
 * private-use URIs. [uri] must be a valid redirect URI.
 */
export function redirectHost(uri: string): string {
  const url = new URL(uri);
  switch (classifyRedirectUri(uri).kind) {
    case 'https':
      return url.hostname;
    case 'loopback':
      return url.host;
    default:
      return url.protocol.slice(0, -1);
  }
}

/**
 * Appends authorization-response parameters to [redirectUri], keeping its own query (RFC 6749
 * §3.1.2). Parameters with an undefined value are omitted.
 */
export function buildRedirect(redirectUri: string, params: Record<string, string | undefined>): string {
  const url = new URL(redirectUri);
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined) url.searchParams.append(name, value);
  }
  return url.toString();
}
