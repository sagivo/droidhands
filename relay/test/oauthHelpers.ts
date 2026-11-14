/**
 * Test helpers for one-tap pairing: an MCP-client-like OAuth driver (registration, PKCE,
 * authorize, polling, token exchange) and device-side pairing answers. Independent of the relay's
 * own OAuth code.
 */
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import type { Relay } from '../src/relay.js';
import { httpRequest, tokenFor, type FakeDevice, type Frame, type HttpResult } from './helpers.js';

export const REDIRECT_URI = 'http://localhost:33418/callback';

export interface Pkce {
  readonly verifier: string;
  readonly challenge: string;
}

export function newPkce(): Pkce {
  const verifier = randomBytes(32).toString('base64url');
  return { verifier, challenge: createHash('sha256').update(verifier).digest('base64url') };
}

export function postJson(url: string, value: unknown, headers: Record<string, string> = {}): Promise<HttpResult> {
  return httpRequest(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...headers },
    body: JSON.stringify(value),
  });
}

export function postForm(url: string, params: Record<string, string>, headers: Record<string, string> = {}): Promise<HttpResult> {
  return httpRequest(url, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', ...headers },
    body: new URLSearchParams(params).toString(),
  });
}

/** Registers a client and returns its client_id. */
export async function registerClient(
  relay: Relay,
  metadata: Record<string, unknown> = { client_name: 'Test Agent', redirect_uris: [REDIRECT_URI] },
): Promise<string> {
  const res = await postJson(`${relay.url}/oauth/register`, metadata);
  assert.equal(res.status, 201, res.body);
  return (JSON.parse(res.body) as { client_id: string }).client_id;
}

export interface AuthorizeParams {
  readonly clientId: string;
  readonly pkce: Pkce;
  readonly deviceId?: string | null;
  readonly redirectUri?: string | null;
  readonly state?: string;
  readonly extra?: Record<string, string>;
}

/** `/oauth/authorize?…` the way an MCP client builds it. */
export function authorizeUrl(relay: Relay, params: AuthorizeParams): string {
  const url = new URL(`${relay.url}/oauth/authorize`);
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', params.clientId);
  if (params.redirectUri !== null) url.searchParams.set('redirect_uri', params.redirectUri ?? REDIRECT_URI);
  url.searchParams.set('code_challenge', params.pkce.challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('state', params.state ?? 'state-123');
  if (params.deviceId !== null && params.deviceId !== undefined) {
    url.searchParams.set('resource', `${relay.url}/d/${params.deviceId}/mcp`);
  }
  for (const [name, value] of Object.entries(params.extra ?? {})) url.searchParams.set(name, value);
  return url.toString();
}

export interface WaitingPage {
  readonly res: HttpResult;
  /** Absolute JSON status URL. */
  readonly pollUrl: string;
  /** Absolute reloadable HTML URL. */
  readonly pageUrl: string;
  readonly displayCode: string;
}

/** Fetches the authorize URL and extracts the waiting page's data attributes. */
export async function openAuthorize(relay: Relay, params: AuthorizeParams): Promise<WaitingPage> {
  const res = await httpRequest(authorizeUrl(relay, params), { headers: { 'user-agent': 'TestBrowser/1.0' } });
  assert.equal(res.status, 200, res.body);
  return parseWaitingPage(res, relay.url);
}

/** Extracts the page's data; its root-relative URLs are resolved against [base]. */
export function parseWaitingPage(res: HttpResult, base: string): WaitingPage {
  const attr = (name: string): string => {
    const match = new RegExp(`${name}="([^"]*)"`).exec(res.body);
    assert.ok(match?.[1] !== undefined, `waiting page has ${name}`);
    return new URL(match[1].replace(/&amp;/g, '&'), base).toString();
  };
  const code = /<div class="code" id="code">([A-Z0-9]{4})<\/div>/.exec(res.body)?.[1];
  assert.ok(code !== undefined, 'waiting page shows the display code');
  return { res, pollUrl: attr('data-poll'), pageUrl: attr('data-page'), displayCode: code };
}

export interface PairStatus {
  readonly status: 'pending' | 'approved' | 'denied' | 'expired';
  readonly redirect?: string;
  readonly expiresAt: number;
}

export async function pollStatus(pollUrl: string): Promise<PairStatus> {
  const res = await httpRequest(pollUrl);
  assert.equal(res.status, 200, res.body);
  return JSON.parse(res.body) as PairStatus;
}

/** Polls until the pairing leaves `pending`. */
export async function pollUntilDecided(pollUrl: string): Promise<PairStatus> {
  const deadline = Date.now() + 5_000;
  for (;;) {
    const status = await pollStatus(pollUrl);
    if (status.status !== 'pending') return status;
    if (Date.now() > deadline) throw new Error('pairing still pending');
    await new Promise((r) => setTimeout(r, 10));
  }
}

export interface PairRequestFrame extends Frame {
  type: 'pair_request';
  id: string;
  clientName: string;
  redirectHost: string;
  displayCode: string;
  remoteAddress: string;
  userAgent?: string;
  expiresAt: number;
}

export interface Approval {
  readonly accessToken: string;
  readonly tokenId: string;
}

/** Answers the next pair_request with an approval and a fresh token for the device. */
export async function approveNext(device: FakeDevice): Promise<Approval & { request: PairRequestFrame }> {
  const request = await device.nextFrame<PairRequestFrame>('pair_request');
  const accessToken = tokenFor(device.identity.deviceId);
  const tokenId = accessToken.split('.')[2] as string;
  device.send({ type: 'pair_response', id: request.id, approved: true, accessToken, tokenId });
  return { request, accessToken, tokenId };
}

export async function denyNext(device: FakeDevice, reason = 'denied'): Promise<PairRequestFrame> {
  const request = await device.nextFrame<PairRequestFrame>('pair_request');
  device.send({ type: 'pair_response', id: request.id, approved: false, reason });
  return request;
}

/** The authorization response parameters of a redirect. */
export function redirectParams(redirect: string): URLSearchParams {
  const url = new URL(redirect);
  assert.equal(`${url.origin}${url.pathname}`, REDIRECT_URI);
  return url.searchParams;
}

export function exchange(relay: Relay, params: Record<string, string>): Promise<HttpResult> {
  return postForm(`${relay.url}/oauth/token`, { grant_type: 'authorization_code', redirect_uri: REDIRECT_URI, ...params });
}

/** Runs a complete flow up to an approved redirect; returns everything needed to exchange. */
export async function approvedCode(
  relay: Relay,
  device: FakeDevice,
  clientId?: string,
): Promise<{ clientId: string; pkce: Pkce; code: string; approval: Approval; pairingId: string }> {
  const id = clientId ?? (await registerClient(relay));
  const pkce = newPkce();
  const page = await openAuthorize(relay, { clientId: id, pkce, deviceId: device.identity.deviceId });
  const approval = await approveNext(device);
  const status = await pollUntilDecided(page.pollUrl);
  assert.equal(status.status, 'approved');
  const code = redirectParams(status.redirect as string).get('code') as string;
  return { clientId: id, pkce, code, approval, pairingId: approval.request.id };
}
