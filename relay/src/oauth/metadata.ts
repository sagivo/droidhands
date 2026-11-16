/**
 * OAuth discovery documents and the URLs around a device's connector endpoint
 * (docs/PROTOCOL.md §5.1-5.2). `base` is always the relay's public base URL (`{relay}`): scheme,
 * host and optional path prefix, no trailing slash. Pure.
 */

/** The only scope; access levels are chosen on the phone. */
export const OAUTH_SCOPE = 'phone';

/** `/d/<deviceId>/mcp` (a trailing slash is tolerated). */
export const CONNECTOR_PATH_REGEX = /^\/d\/([a-z2-7]{26})\/mcp\/?$/;

/** Path (below `/.well-known/oauth-protected-resource`) of a device's resource metadata. */
const PRM_PREFIX = '/.well-known/oauth-protected-resource';

/** The device connector URL `{relay}/d/<deviceId>/mcp`. */
export function connectorUrl(base: string, deviceId: string): string {
  return `${base}/d/${deviceId}/mcp`;
}

/** RFC 9728 metadata URL of the device's connector. */
export function resourceMetadataUrl(base: string, deviceId: string): string {
  return `${base}${PRM_PREFIX}/d/${deviceId}/mcp`;
}

/** `WWW-Authenticate` for every 401 on the connector path (§5.1). */
export function connectorChallenge(base: string, deviceId: string): string {
  return `Bearer realm="droidhands", resource_metadata="${resourceMetadataUrl(base, deviceId)}"`;
}

/** RFC 9728 protected resource metadata for a device. */
export function protectedResourceMetadata(base: string, deviceId: string): Record<string, unknown> {
  return {
    resource: connectorUrl(base, deviceId),
    authorization_servers: [base],
    bearer_methods_supported: ['header'],
    resource_name: 'DroidHands',
    scopes_supported: [OAUTH_SCOPE],
  };
}

/** RFC 8414 authorization server metadata (also served as `openid-configuration`). */
export function authorizationServerMetadata(base: string): Record<string, unknown> {
  return {
    issuer: base,
    authorization_endpoint: `${base}/oauth/authorize`,
    token_endpoint: `${base}/oauth/token`,
    registration_endpoint: `${base}/oauth/register`,
    response_types_supported: ['code'],
    response_modes_supported: ['query'],
    grant_types_supported: ['authorization_code'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: [OAUTH_SCOPE],
    authorization_response_iss_parameter_supported: true,
  };
}

/**
 * The deviceId named by an RFC 8707 `resource` value: `{relay}/d/<id>/mcp` or `{relay}/d/<id>`
 * (a trailing slash, default port and host case are normalized). Null for anything else, including
 * resources of another relay or with a query or fragment.
 */
export function deviceIdFromResource(resource: string, base: string): string | null {
  const normalized = normalizeUrl(resource);
  const root = normalizeUrl(base);
  if (normalized === null || root === null || !normalized.startsWith(`${root}/d/`)) return null;
  const match = /^([a-z2-7]{26})(?:\/mcp)?$/.exec(normalized.slice(root.length + '/d/'.length));
  return match?.[1] ?? null;
}

/**
 * Which device a well-known path is about: `/.well-known/oauth-protected-resource[prefix]/d/<id>/mcp`
 * → id. [prefix] is the public URL's path (RFC 9728 §3.1 inserts it after the well-known part);
 * the form without it is accepted too. Null when the path is not a resource-metadata path.
 */
export function deviceIdFromMetadataPath(pathname: string, prefix: string): string | null {
  for (const candidate of new Set([PRM_PREFIX, `${PRM_PREFIX}${prefix}`])) {
    if (!pathname.startsWith(`${candidate}/`)) continue;
    const match = CONNECTOR_PATH_REGEX.exec(pathname.slice(candidate.length));
    if (match?.[1] !== undefined) return match[1];
  }
  return null;
}

/** True for the RFC 8414 / OpenID discovery paths, with or without the public URL's [prefix]. */
export function isAuthorizationServerMetadataPath(pathname: string, prefix: string): boolean {
  const roots = ['/.well-known/oauth-authorization-server', '/.well-known/openid-configuration'];
  return roots.some((root) => pathname === root || (prefix !== '' && pathname === `${root}${prefix}`));
}

/** `origin + pathname` without trailing slashes, or null for unparsable / query / fragment URLs. */
function normalizeUrl(value: string): string | null {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.search !== '' || url.hash !== '' || url.username !== '' || url.password !== '') return null;
  return `${url.origin}${url.pathname}`.replace(/\/+$/, '');
}
