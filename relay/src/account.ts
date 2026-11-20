import { createLocalJWKSet, errors, jwtVerify, type JSONWebKeySet } from 'jose';
import { CloseCode, type HandshakeErrorCode } from './protocol.js';

/**
 * Account check of a device hello (docs/PROTOCOL.md §3.1, §7.3), shared by the Node relay and the
 * Cloudflare Worker relay. When the relay has ACCOUNTS_URL, a phone must show a relay token: a
 * short-lived JWT the API server (api/) signed for the signed-in person. The relay checks
 * it against the server's public keys (`{ACCOUNTS_URL}/api/auth/jwks`) and learns only the account
 * id (`sub`).
 */

/** `aud` of relay tokens (api/src/auth.ts). */
export const RELAY_TOKEN_AUDIENCE = 'droidhands-relay';

/** Path of the API server's public keys. */
export const JWKS_PATH = '/api/auth/jwks';

/** Keys are fetched again after this long, so a rotated key is picked up. */
const JWKS_MAX_AGE_MS = 10 * 60 * 1000;
/** A token signed with an unknown key fetches the keys again, at most this often. */
const JWKS_MIN_REFETCH_MS = 30 * 1000;
/** Clock difference tolerated between the relay and the API server. */
const CLOCK_TOLERANCE_SECONDS = 60;
/** Longest token considered (relay tokens are well under 1 KiB). */
const MAX_TOKEN_LENGTH = 4096;

export type AccountCheck =
  | { readonly ok: true; readonly accountId: string }
  | {
      readonly ok: false;
      readonly code: Extract<HandshakeErrorCode, 'account_required' | 'account_unavailable'>;
      readonly closeCode: number;
      readonly message: string;
      /** Counts toward the client's authentication-failure limit. */
      readonly authFailure: boolean;
    };

/** Fetches the API server's JWKS document (its parsed JSON). Throws when unreachable. */
export type JwksFetcher = () => Promise<unknown>;

/** The default [JwksFetcher]: `GET {accountsUrl}/api/auth/jwks` with [fetchFn]. */
export function httpJwksFetcher(accountsUrl: string, fetchFn: typeof fetch = fetch): JwksFetcher {
  const url = `${accountsUrl}${JWKS_PATH}`;
  return async () => {
    const res = await fetchFn(url, { headers: { accept: 'application/json' }, signal: AbortSignal.timeout(5_000) });
    if (!res.ok) throw new Error(`JWKS: HTTP ${res.status}`);
    return res.json();
  };
}

const REQUIRED_MESSAGE = 'Sign in to DroidHands to use this relay (Connect › DroidHands relay). If the app has no sign-in, update it.';

export class AccountVerifier {
  /**
   * The last keys fetched. Only settled values are kept between calls, never a pending fetch: on
   * Cloudflare a promise started by one request can't be awaited by another.
   */
  private keys: { readonly set: ReturnType<typeof createLocalJWKSet>; readonly fetchedAt: number } | null = null;

  constructor(
    /** Expected `iss`: the API server's base URL (the relay's ACCOUNTS_URL). */
    private readonly issuer: string,
    private readonly fetchJwks: JwksFetcher,
    private readonly now: () => number = Date.now,
  ) {}

  /** Checks `hello.accountToken` ([token], null when absent). */
  async check(token: string | null): Promise<AccountCheck> {
    if (token === null || token === '' || token.length > MAX_TOKEN_LENGTH) return required();
    let keys = this.keys;
    if (keys === null || this.now() - keys.fetchedAt > JWKS_MAX_AGE_MS) {
      keys = await this.refresh();
      if (keys === null) return unavailable();
    }
    let result = await this.verify(token, keys.set);
    if (result === 'unknown_key' && this.now() - keys.fetchedAt > JWKS_MIN_REFETCH_MS) {
      const fresh = await this.refresh();
      if (fresh === null) return unavailable();
      result = await this.verify(token, fresh.set);
    }
    if (typeof result === 'string') return required();
    return { ok: true, accountId: result.accountId };
  }

  private async verify(token: string, set: ReturnType<typeof createLocalJWKSet>): Promise<{ accountId: string } | 'invalid' | 'unknown_key'> {
    try {
      const { payload } = await jwtVerify(token, set, {
        issuer: this.issuer,
        audience: RELAY_TOKEN_AUDIENCE,
        algorithms: ['EdDSA', 'ES256', 'RS256'],
        clockTolerance: CLOCK_TOLERANCE_SECONDS,
        currentDate: new Date(this.now()),
        requiredClaims: ['sub', 'exp'],
      });
      return typeof payload.sub === 'string' && payload.sub !== '' ? { accountId: payload.sub } : 'invalid';
    } catch (err) {
      return err instanceof errors.JWKSNoMatchingKey ? 'unknown_key' : 'invalid';
    }
  }

  private async refresh(): Promise<AccountVerifier['keys']> {
    try {
      const document = (await this.fetchJwks()) as JSONWebKeySet;
      if (!Array.isArray(document?.keys)) throw new Error('JWKS: no keys');
      this.keys = { set: createLocalJWKSet(document), fetchedAt: this.now() };
      return this.keys;
    } catch {
      return null;
    }
  }
}

function required(): AccountCheck {
  return { ok: false, code: 'account_required', closeCode: CloseCode.UNAUTHORIZED, message: REQUIRED_MESSAGE, authFailure: true };
}

function unavailable(): AccountCheck {
  return {
    ok: false,
    code: 'account_unavailable',
    closeCode: CloseCode.TRY_AGAIN_LATER,
    message: "The relay can't check DroidHands accounts right now. DroidHands keeps retrying.",
    authFailure: false,
  };
}
