# DroidHands API

The DroidHands API server, on `api.droidhands.com`. It handles login: people sign in with Google once
in the app, and it keeps their account. After that their phone can use the hosted relay at
`connect.droidhands.com`. Self-hosted relays, Tailscale and the local server need no account.

* **Stack:** [Better Auth](https://www.better-auth.com) on Cloudflare Workers, with users in
  [D1](https://developers.cloudflare.com/d1/). Google is the only sign-in method.
* **What it stores:** in `user`, each person's name, email and Google picture URL. In `account`, their
  Google subject id, which links them to Google. In `session`, the app sessions, with the IP address and
  user agent of the sign-in. Google's access, refresh and ID tokens are thrown away (`src/auth.ts`).
* **What relays learn:** only the account id. The relay token a phone shows a relay is a 10-minute
  JWT with `sub` and nothing else.
* **Protocol:** [docs/PROTOCOL.md §7](../docs/PROTOCOL.md#7-droidhands-accounts-hosted-relay).

```
phone ── browser tab ──► /app/sign-in ──► Google ──► /api/auth/callback/google ──► /app/done
  ▲                                                                                    │
  └──────────── droidhands://signed-in?code=… (one-time, bound to the app's PKCE) ◄────┘
phone ── POST /app/token {code, code_verifier} ──► session token (kept in the app)
phone ── POST /app/relay-token (Bearer) ──► JWT ──► hello.accountToken ──► relay (checks /api/auth/jwks)
```

## Routes

| Route | What |
|---|---|
| `/api/auth/*` | Better Auth: the Google OAuth callback, `/api/auth/jwks` (the relay token keys), sessions |
| `GET /app/sign-in`, `/app/done`, `/app/failed` | The phone's browser sign-in (§7.1) |
| `POST /app/token` | Trades a sign-in code plus its verifier for a session |
| `GET /app/me`, `POST /app/relay-token`, `/app/sign-out`, `/app/delete-account` | Bearer API (§7.2) |
| `GET /delete-account` | Deletes an account on the web (Google Play requires this outside the app) |
| `GET /healthz` | `{"ok":true}` |

## Set up

You need a Cloudflare account and a Google Cloud project.

1. **Google OAuth client.** In the Google Cloud console, open *APIs & Services › Credentials*. Choose
   *Create credentials › OAuth client ID › Web application*.
   * Authorized redirect URI: `https://api.droidhands.com/api/auth/callback/google`. For local
     development, also add `http://localhost:8788/api/auth/callback/google`.
   * The OAuth consent screen needs only the default scopes (`openid`, `email`, `profile`). It also
     needs the app name, a support email and a privacy policy link. Publish it ("In production"), or
     only test users can sign in.
2. **D1 database.** Run `npx wrangler d1 create droidhands-api` and put the printed
   `database_id` in `wrangler.toml`.
3. **Secrets:**
   ```sh
   cd api && npm ci
   npx wrangler secret put BETTER_AUTH_SECRET     # paste: openssl rand -base64 32
   npx wrangler secret put GOOGLE_CLIENT_ID
   npx wrangler secret put GOOGLE_CLIENT_SECRET
   ```
   Rotating `BETTER_AUTH_SECRET` signs everyone out. It also makes the stored signing keys unreadable,
   so phones can't reach the relay until they sign in again.
4. **Deploy:** run `npm run deploy`. It applies `migrations/` to D1 first, then deploys the Worker on
   `api.droidhands.com`. Check it with `curl https://api.droidhands.com/healthz`.

### Turn on accounts for the relay

Do this only once the API Worker is live and an app build with sign-in has shipped. From then on,
every phone without a signed-in app is refused.

1. In `relay/wrangler.toml`, set `ACCOUNTS_URL = "https://api.droidhands.com"` and uncomment the
   `[[services]]` `ACCOUNTS` binding.
2. Deploy the relay. Workers Builds deploys it on every push to main that touches `relay/`.
3. Build the app with `-Pdroidhands.apiUrl=https://api.droidhands.com`. In CI this comes
   from the repository variable `DROIDHANDS_API_URL`.

The same works for a self-hosted relay: set `ACCOUNTS_URL` to an API server of yours.

## Develop

```sh
npm test                    # type-check, then the tests in workerd (D1 included)
cp .dev.vars.example .dev.vars   # then fill in the Google client of step 1
npm run db:migrate:local && npm run dev   # http://localhost:8788
```

For a local relay that requires accounts, start `relay/` with `ACCOUNTS_URL=http://localhost:8788`.
Build the app with `-Pdroidhands.apiUrl=http://10.0.2.2:8788`, which is how the emulator reaches
this Mac.
