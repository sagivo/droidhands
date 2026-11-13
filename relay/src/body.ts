import type { IncomingMessage } from 'node:http';

export type BodyResult =
  | { readonly kind: 'ok'; readonly body: string }
  /** More than the limit was sent; buffering stopped at the limit. */
  | { readonly kind: 'too_large' }
  /** The client went away before the body was complete. */
  | { readonly kind: 'aborted' };

/** The declared Content-Length, or null when absent (e.g. chunked). */
export function declaredLength(req: IncomingMessage): number | null {
  const header = req.headers['content-length'];
  if (header === undefined || !/^\d+$/.test(header)) return null;
  return Number(header);
}

/**
 * Reads the request body as UTF-8, buffering at most [limit] bytes. On overflow it stops
 * buffering immediately and resolves `too_large`; the caller should answer 413 with
 * `Connection: close` so the rest of the upload is not read.
 */
export function readBody(req: IncomingMessage, limit: number): Promise<BodyResult> {
  const declared = declaredLength(req);
  if (declared !== null && declared > limit) return Promise.resolve({ kind: 'too_large' });

  return new Promise<BodyResult>((resolve) => {
    const chunks: Buffer[] = [];
    let size = 0;
    let done = false;

    const finish = (result: BodyResult): void => {
      if (done) return;
      done = true;
      req.off('data', onData);
      req.off('end', onEnd);
      req.off('error', onAborted);
      req.off('close', onAborted);
      resolve(result);
    };
    const onData = (chunk: Buffer): void => {
      size += chunk.length;
      if (size > limit) {
        chunks.length = 0;
        finish({ kind: 'too_large' });
        return;
      }
      chunks.push(chunk);
    };
    const onEnd = (): void => finish({ kind: 'ok', body: Buffer.concat(chunks, size).toString('utf8') });
    const onAborted = (): void => finish({ kind: 'aborted' });

    req.on('data', onData);
    req.on('end', onEnd);
    req.on('error', onAborted);
    req.on('close', onAborted);
  });
}
