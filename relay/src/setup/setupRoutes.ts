import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import type { IncomingMessage, ServerResponse } from 'node:http';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { readBody } from '../body.js';
import { constantTimeEqual } from '../encoding.js';
import { admit, clientInfo, publicPathPrefix, relayBase, type HttpDeps } from '../httpDeps.js';
import { allowMethods, sendJson, sendText } from '../httpUtil.js';
import { errorMessage } from '../logger.js';
import { sendSetupPage } from '../pages/setupPage.js';
import { qrPath, qrSvg } from './qr.js';
import { setupUrl } from './setupUrl.js';

/**
 * `GET /setup` (docs/PROTOCOL.md §5.5), `GET /download/phonebridge.apk` and `POST /setup/qr`.
 */

/** File the operator may drop into PUBLIC_DIR. */
export const APK_FILE_NAME = 'phonebridge.apk';
export const APK_PATH = `/download/${APK_FILE_NAME}`;
export const SETUP_QR_PATH = '/setup/qr';

const APK_CONTENT_TYPE = 'application/vnd.android.package-archive';

/** `{"key": "<access key>"}` is tiny. */
const MAX_QR_BODY_BYTES = 1024;

export async function handleSetupPage(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  if (!admit(deps, clientInfo(deps, req)).allowed) return sendText(res, 429, 'Too Many Requests\n');
  const base = relayBase(deps, req);
  const accessKeyRequired = deps.config.accessKey !== null;
  const prefix = publicPathPrefix(deps.config);
  // APK_URL (e.g. the latest GitHub release) wins over a file in PUBLIC_DIR.
  const apkUrl = deps.config.apkUrl ?? ((await apkFile(deps)) === null ? null : `${prefix}${APK_PATH}`);
  sendSetupPage(req, res, {
    base,
    apkUrl,
    qrEndpoint: `${prefix}${SETUP_QR_PATH}`,
    accessKeyRequired,
    // With an access key the QR must include it, which only the page itself knows (see /setup/qr).
    qrSvg: accessKeyRequired ? null : qrSvg(setupUrl(base, null), 'QR code of this setup page'),
  });
}

/**
 * Streams the operator-provided APK with the Android package content type, or redirects to
 * APK_URL when it is set (so links to this path keep working).
 */
export async function handleApkDownload(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (!allowMethods(req, res, 'GET, HEAD')) return;
  if (!admit(deps, clientInfo(deps, req)).allowed) return sendText(res, 429, 'Too Many Requests\n');
  if (deps.config.apkUrl !== null) {
    res.setHeader('Location', deps.config.apkUrl);
    return sendText(res, 302, 'Found\n');
  }
  const apk = await apkFile(deps);
  if (apk === null) return sendText(res, 404, 'Not Found\n');
  res.statusCode = 200;
  res.setHeader('Content-Type', APK_CONTENT_TYPE);
  res.setHeader('Content-Length', String(apk.size));
  res.setHeader('Content-Disposition', `attachment; filename="${APK_FILE_NAME}"`);
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  try {
    await pipeline(createReadStream(apk.path), res);
  } catch (err) {
    deps.logger.debug('apk download interrupted', { error: errorMessage(err) });
    res.destroy();
  }
}

/**
 * `POST /setup/qr` with `{"key": …}`: the SVG path of a QR code for `{relay}/setup#key=…`, if the
 * key is this relay's access key (constant-time; wrong keys count as authentication failures).
 * Lets the setup page show a scannable link that includes the key without the server ever
 * rendering the key into a page for someone who does not already have it.
 */
export async function handleSetupQr(deps: HttpDeps, req: IncomingMessage, res: ServerResponse): Promise<void> {
  if (!allowMethods(req, res, 'POST')) return;
  const client = clientInfo(deps, req);
  if (!admit(deps, client).allowed) return sendJson(res, 429, { error: 'rate_limited' });
  const body = await readBody(req, MAX_QR_BODY_BYTES);
  if (body.kind === 'aborted') return;
  if (body.kind === 'too_large') {
    res.setHeader('Connection', 'close');
    return sendJson(res, 413, { error: 'too_large' });
  }
  let key: unknown;
  try {
    key = (JSON.parse(body.body) as { key?: unknown } | null)?.key;
  } catch {
    return sendJson(res, 400, { error: 'invalid_request' });
  }
  const expected = deps.config.accessKey;
  if (expected !== null && (typeof key !== 'string' || !constantTimeEqual(key, expected))) {
    deps.limiter.recordAuthFailure(client.ipKey);
    return sendJson(res, 403, { error: 'access_key' });
  }
  sendJson(res, 200, qrPath(setupUrl(relayBase(deps, req), expected)));
}

async function apkFile(deps: HttpDeps): Promise<{ path: string; size: number } | null> {
  const path = join(deps.config.publicDir, APK_FILE_NAME);
  try {
    const info = await stat(path);
    return info.isFile() ? { path, size: info.size } : null;
  } catch {
    return null;
  }
}
