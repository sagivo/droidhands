import type { AddressInfo } from 'node:net';
import type { NetworkInterfaceInfo } from 'node:os';

/**
 * The phone setup link (docs/PROTOCOL.md §5.5). Pure.
 */

/** Path of the setup page. */
export const SETUP_PATH = '/setup';

/** Android package that handles `droidhands://configure`. */
export const ANDROID_PACKAGE = 'com.droidhands.android';

/**
 * `{base}/setup`, with `#key=<accessKey>` when the relay requires one. The key travels in the
 * fragment, so browsers never send it to the server (or its logs).
 */
export function setupUrl(base: string, accessKey: string | null): string {
  return `${base}${SETUP_PATH}${accessKey === null ? '' : `#key=${encodeURIComponent(accessKey)}`}`;
}

/**
 * The best guess at the base URL a phone can reach when PUBLIC_URL is unset: the first external
 * IPv4 address when bound to a wildcard address, otherwise the bound address itself.
 */
export function guessBaseUrl(
  publicUrl: string | null,
  address: AddressInfo,
  interfaces: NodeJS.Dict<NetworkInterfaceInfo[]>,
): string {
  if (publicUrl !== null) return publicUrl;
  let host = address.family === 'IPv6' ? `[${address.address}]` : address.address;
  if (address.address === '0.0.0.0' || address.address === '::') {
    const external = Object.values(interfaces)
      .flatMap((list) => list ?? [])
      .find((i) => i.family === 'IPv4' && !i.internal);
    host = external?.address ?? '127.0.0.1';
  }
  return `http://${host}:${address.port}`;
}
