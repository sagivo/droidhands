/**
 * Better Auth for DroidHands accounts: Google sign-in only, users and sessions in D1.
 *
 * * The `bearer` plugin lets the phone use its session token as `Authorization: Bearer <token>`
 *   (it has no cookie jar for this site).
 * * The `jwt` plugin signs short-lived relay tokens (docs/PROTOCOL.md §7.3) and publishes their
 *   public keys at `/api/auth/jwks`, where relays fetch them.
 */
import { betterAuth } from 'better-auth';
import { bearer, jwt } from 'better-auth/plugins';

/** `aud` of relay tokens: what relays check (docs/PROTOCOL.md §7.3). */
export const RELAY_AUDIENCE = 'droidhands-relay';

/** Lifetime of a relay token. The phone gets a fresh one for every relay connection. */
export const RELAY_TOKEN_TTL_SECONDS = 10 * 60;

/** A phone's session lasts 90 days and is extended (at most once a day) while the phone uses it. */
const SESSION_TTL_SECONDS = 90 * 24 * 60 * 60;
const SESSION_UPDATE_AGE_SECONDS = 24 * 60 * 60;

export interface Env {
  readonly DB: D1Database;
  /** Public base URL of this Worker, e.g. https://api.droidhands.com (no trailing slash). */
  readonly BETTER_AUTH_URL: string;
  /** Secret: signs session cookies and encrypts the JWT signing keys (`openssl rand -base64 32`). */
  readonly BETTER_AUTH_SECRET: string;
  /** Secrets: the Google OAuth client (a "Web application" client in Google Cloud). */
  readonly GOOGLE_CLIENT_ID: string;
  readonly GOOGLE_CLIENT_SECRET: string;
}

export type Auth = ReturnType<typeof createAuth>;

/** Names of required settings that are missing (the Worker then answers 500 and names them). */
export function missingSettings(env: Env): string[] {
  const names = ['BETTER_AUTH_URL', 'BETTER_AUTH_SECRET', 'GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET'] as const;
  return names.filter((name) => typeof env[name] !== 'string' || env[name].trim() === '');
}

export function createAuth(env: Env) {
  const baseURL = env.BETTER_AUTH_URL.replace(/\/+$/, '');
  return betterAuth({
    appName: 'DroidHands',
    baseURL,
    secret: env.BETTER_AUTH_SECRET,
    database: env.DB,
    emailAndPassword: { enabled: false },
    socialProviders: {
      google: {
        clientId: env.GOOGLE_CLIENT_ID,
        clientSecret: env.GOOGLE_CLIENT_SECRET,
        // Always show Google's account chooser, so a shared browser can't pick the wrong account.
        prompt: 'select_account',
      },
    },
    session: { expiresIn: SESSION_TTL_SECONDS, updateAge: SESSION_UPDATE_AGE_SECONDS },
    account: { accountLinking: { enabled: false } },
    advanced: {
      // migrations/ is applied by `wrangler d1 migrations apply` before every deploy; checking the
      // schema again on each cold start would cost D1 queries for nothing.
      database: { validateSchema: false },
      // Cloudflare puts the client address here; Better Auth uses it for its rate limits.
      ipAddress: { ipAddressHeaders: ['cf-connecting-ip'] },
    },
    databaseHooks: {
      account: {
        // DroidHands only needs to know who signed in: never keep Google's tokens.
        create: { before: async (account) => ({ data: withoutProviderTokens(account) }) },
        update: { before: async (account) => ({ data: withoutProviderTokens(account) }) },
      },
    },
    plugins: [
      bearer(),
      jwt({
        jwt: {
          issuer: baseURL,
          audience: RELAY_AUDIENCE,
          expirationTime: `${RELAY_TOKEN_TTL_SECONDS}s`,
          // Relays learn the account id (`sub`) and nothing else: no name, email or picture.
          definePayload: () => ({}),
        },
      }),
    ],
  });
}

function withoutProviderTokens<T extends Record<string, unknown>>(account: T): T {
  return { ...account, accessToken: null, refreshToken: null, idToken: null, accessTokenExpiresAt: null, refreshTokenExpiresAt: null };
}
