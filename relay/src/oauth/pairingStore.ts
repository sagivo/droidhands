import { createHash, randomBytes, randomInt } from 'node:crypto';
import { constantTimeEqual } from '../encoding.js';
import { deviceTag, type Logger } from '../logger.js';
import { NoticeOutbox } from './noticeOutbox.js';
import { parsePairResponse, type PairRequestFrame, type RelayPairFrame } from './pairingFrames.js';
import { buildRedirect, redirectHost } from './redirectUris.js';

/**
 * In-memory state of one-tap pairings (docs/PROTOCOL.md §5.3-5.4): pending pairings waiting for
 * the phone, decided ones the browser can still poll, and authorization codes holding the issued
 * access token until the client exchanges them.
 *
 * Synchronous and timer-free: time comes from [PairingStoreOptions.now] and expirations happen in
 * [PairingStore.sweep] (called periodically by the relay) or lazily when a record is read. Memory is
 * bounded by [PairingLimits].
 */

/** What the browser asked for in `/oauth/authorize`, after validation. */
export interface AuthorizationRequest {
  readonly deviceId: string;
  readonly clientId: string;
  readonly clientName: string;
  readonly redirectUri: string;
  /** False when the client omitted `redirect_uri` (allowed when it registered exactly one). */
  readonly redirectUriProvided: boolean;
  readonly codeChallenge: string;
  readonly state: string | undefined;
  /** `{relay}` as seen by the browser; becomes the `iss` response parameter. */
  readonly issuer: string;
  /**
   * True when the request did not name the device (no `resource`) and the relay picked the only
   * online one (ALLOW_IMPLICIT_DEVICE). The waiting page then does not show the phone's name.
   */
  readonly implicitDevice?: boolean;
}

/** Who started the pairing (shown on the phone, used for per-IP limits). */
export interface PairingOrigin {
  readonly ipKey: string;
  readonly remoteAddress: string;
  readonly userAgent: string | undefined;
}

export type PairingStatus = 'pending' | 'approved' | 'denied' | 'expired';

/** Read-only view of a pairing for the HTTP layer. */
export interface PairingView {
  readonly id: string;
  readonly deviceId: string;
  readonly pageToken: string;
  readonly displayCode: string;
  readonly clientName: string;
  readonly redirectHost: string;
  readonly expiresAt: number;
  readonly status: PairingStatus;
  /** Where the browser goes next; set once the pairing is decided. Contains the code if approved. */
  readonly redirect: string | null;
  /** See [AuthorizationRequest.implicitDevice]. */
  readonly implicitDevice: boolean;
}

/** An authorization code's binding (§5.3 step 8), handed to the token endpoint exactly once. */
export interface CodeGrant {
  readonly pairingId: string;
  readonly deviceId: string;
  readonly clientId: string;
  readonly redirectUri: string;
  readonly redirectUriProvided: boolean;
  readonly codeChallenge: string;
  readonly issuer: string;
  readonly tokenId: string;
  /** The device-issued token. Never log it; dropped by [PairingStore.finishExchange]. */
  accessToken: string;
  readonly expiresAt: number;
}

export type CreateFailure =
  /** The device already has the maximum number of pending pairings. */
  | 'device_busy'
  /** The device reached its hourly pairing budget. */
  | 'device_hourly'
  /** This client IP already has the maximum number of pending pairings. */
  | 'ip_busy'
  /** This client IP reached its hourly pairing budget. */
  | 'ip_hourly'
  /** The relay-wide cap on tracked pairings is reached. */
  | 'capacity'
  /** The pair_request could not be delivered. */
  | 'offline';

export type CreateResult =
  | { readonly ok: true; readonly pairing: PairingView; readonly reused: boolean }
  | { readonly ok: false; readonly reason: CreateFailure };

export interface PairingLimits {
  readonly maxPendingPerDevice: number;
  readonly maxPerDevicePerHour: number;
  readonly maxPendingPerIp: number;
  readonly maxPerIpPerHour: number;
  /** Pending plus decided-but-still-pollable pairings, relay-wide. */
  readonly maxRecords: number;
  /** Undelivered pair_complete/pair_abandoned notices kept per offline device. */
  readonly maxNoticesPerDevice: number;
  /** Offline devices with undelivered notices. */
  readonly maxNoticeDevices: number;
}

export const DEFAULT_PAIRING_LIMITS: PairingLimits = Object.freeze({
  maxPendingPerDevice: 3,
  maxPerDevicePerHour: 20,
  maxPendingPerIp: 5,
  maxPerIpPerHour: 30,
  maxRecords: 1_000,
  maxNoticesPerDevice: 16,
  maxNoticeDevices: 1_000,
});

/** The channel a frame went out on (a device connection); compared by identity only. */
export type Channel = object;

export interface PairingStoreOptions {
  /** How long the phone has to answer (§5.3: 180 s). */
  readonly ttlMs: number;
  /** Authorization code lifetime (§5.3: 120 s). */
  readonly codeTtlMs: number;
  /** How long a decided pairing stays pollable; at least [codeTtlMs]. Default 10 min. */
  readonly retainMs?: number;
  /** How long undelivered notices wait for their device to reconnect. Default 1 h. */
  readonly noticeTtlMs?: number;
  readonly limits?: Partial<PairingLimits>;
  readonly now?: () => number;
  /** Sends [frame] to the device's live connection; returns that connection, or null if offline. */
  readonly deliver: (deviceId: string, frame: RelayPairFrame) => Channel | null;
  readonly logger: Logger;
}

interface PairingRecord {
  readonly id: string;
  readonly request: AuthorizationRequest;
  readonly origin: PairingOrigin;
  readonly pageToken: string;
  readonly displayCode: string;
  readonly redirectHost: string;
  readonly expiresAt: number;
  /** The connection the pair_request was last sent on; moves to a newer one on reconnect. */
  channel: Channel;
  status: PairingStatus;
  redirect: string | null;
  decidedAt: number;
  /** The approved token's id, once approved. */
  tokenId: string | null;
}

const HOUR_MS = 3_600_000;

/** Alphabet of the display code: no 0/O, 1/I. */
const DISPLAY_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const DISPLAY_CODE_LENGTH = 4;

export class PairingStore {
  private readonly records = new Map<string, PairingRecord>();
  /** Keyed by SHA-256(code), so raw codes are never stored. */
  private readonly codes = new Map<string, CodeGrant>();
  /** Creation times within the last hour, per `d:<deviceId>` / `i:<ipKey>`. */
  private readonly history = new Map<string, number[]>();
  private readonly notices: NoticeOutbox;
  private readonly limits: PairingLimits;
  private readonly retainMs: number;
  private readonly now: () => number;

  constructor(private readonly options: PairingStoreOptions) {
    this.limits = { ...DEFAULT_PAIRING_LIMITS, ...options.limits };
    this.retainMs = Math.max(options.retainMs ?? 600_000, options.codeTtlMs);
    this.now = options.now ?? Date.now;
    this.notices = new NoticeOutbox({
      maxPerDevice: this.limits.maxNoticesPerDevice,
      maxDevices: this.limits.maxNoticeDevices,
      ttlMs: options.noticeTtlMs ?? HOUR_MS,
      now: this.now,
    });
  }

  /** Tracked pairings (pending and decided). */
  get size(): number {
    return this.records.size;
  }

  /** Authorization codes not yet exchanged or expired. */
  get codeCount(): number {
    return this.codes.size;
  }

  /**
   * Starts a pairing and sends `pair_request` to the device. A repeated request for the same
   * flow (same client, redirect URI, PKCE challenge and state, e.g. a page reload) returns the
   * existing pairing while it is pending or approved, instead of prompting the phone again.
   */
  create(request: AuthorizationRequest, origin: PairingOrigin): CreateResult {
    this.sweep();
    const now = this.now();
    const existing = this.findSameFlow(request);
    if (existing !== undefined) return { ok: true, pairing: view(existing), reused: true };

    const deviceKey = `d:${request.deviceId}`;
    const ipKey = `i:${origin.ipKey}`;
    const pending = [...this.records.values()].filter((r) => r.status === 'pending');
    if (pending.filter((r) => r.request.deviceId === request.deviceId).length >= this.limits.maxPendingPerDevice) {
      return { ok: false, reason: 'device_busy' };
    }
    if (this.recent(deviceKey, now) >= this.limits.maxPerDevicePerHour) return { ok: false, reason: 'device_hourly' };
    if (pending.filter((r) => r.origin.ipKey === origin.ipKey).length >= this.limits.maxPendingPerIp) {
      return { ok: false, reason: 'ip_busy' };
    }
    if (this.recent(ipKey, now) >= this.limits.maxPerIpPerHour) return { ok: false, reason: 'ip_hourly' };
    if (this.records.size >= this.limits.maxRecords) return { ok: false, reason: 'capacity' };

    const prompt = {
      id: newPairingId(),
      request,
      origin,
      displayCode: newDisplayCode(),
      redirectHost: redirectHost(request.redirectUri),
      expiresAt: now + this.options.ttlMs,
    };
    const channel = this.options.deliver(request.deviceId, pairRequestFrame(prompt));
    if (channel === null) return { ok: false, reason: 'offline' };

    const record: PairingRecord = {
      ...prompt,
      pageToken: randomBytes(16).toString('base64url'),
      channel,
      status: 'pending',
      redirect: null,
      decidedAt: 0,
      tokenId: null,
    };
    this.records.set(record.id, record);
    this.remember(deviceKey, now);
    this.remember(ipKey, now);
    this.options.logger.info('pairing requested', { device: deviceTag(request.deviceId), ip: origin.remoteAddress });
    return { ok: true, pairing: view(record), reused: false };
  }

  /** The pairing [id] if [pageToken] matches (constant-time), with expiry applied; else null. */
  poll(id: string, pageToken: string): PairingView | null {
    const record = this.records.get(id);
    if (record === undefined || !constantTimeEqual(record.pageToken, pageToken)) return null;
    this.expireIfDue(record, this.now());
    return view(record);
  }

  /**
   * Handles a `pair_response` frame from [deviceId]. An approval for a pairing that is no longer
   * pending (expired, unknown, or another device's) is answered with `pair_abandoned` so the phone
   * deletes the token nobody will receive.
   */
  handleResponse(deviceId: string, frame: Record<string, unknown>): void {
    const response = parsePairResponse(frame, deviceId);
    const tag = deviceTag(deviceId);
    if (response === null) {
      this.options.logger.debug('ignored malformed pair_response', { device: tag });
      return;
    }
    const record = this.records.get(response.id);
    const now = this.now();
    if (record !== undefined) this.expireIfDue(record, now);
    if (record === undefined || record.request.deviceId !== deviceId || record.status !== 'pending') {
      const tokenId = response.kind === 'denied' ? null : response.tokenId;
      // A repeated frame for the token already handed to the relay must not delete that token.
      if (tokenId !== null && tokenId === record?.tokenId && record.request.deviceId === deviceId) return;
      if (tokenId !== null) this.notify(deviceId, { type: 'pair_abandoned', id: response.id, tokenId });
      this.options.logger.info('late or unknown pair_response', { device: tag, approved: response.kind !== 'denied' });
      return;
    }

    const { request } = record;
    if (response.kind !== 'approved') {
      if (response.kind === 'invalid') {
        this.options.logger.warn('device approved a pairing with an invalid token', { device: tag });
        if (response.tokenId !== null) this.notify(deviceId, { type: 'pair_abandoned', id: record.id, tokenId: response.tokenId });
      }
      const description =
        response.kind === 'denied' && response.reason === 'timeout'
          ? 'Nobody answered the request on the phone in time'
          : response.kind === 'denied' && response.reason === 'busy'
            ? 'The phone was busy with another request; try again'
            : 'The request was denied on the phone';
      this.decide(record, 'denied', denialRedirect(request, description), now);
      this.options.logger.info('pairing denied', { device: tag, reason: response.kind === 'denied' ? response.reason : 'invalid' });
      return;
    }

    const code = randomBytes(32).toString('base64url');
    this.codes.set(hashCode(code), {
      pairingId: record.id,
      deviceId,
      clientId: request.clientId,
      redirectUri: request.redirectUri,
      redirectUriProvided: request.redirectUriProvided,
      codeChallenge: request.codeChallenge,
      issuer: request.issuer,
      tokenId: response.tokenId,
      accessToken: response.accessToken,
      expiresAt: now + this.options.codeTtlMs,
    });
    record.tokenId = response.tokenId;
    this.decide(record, 'approved', buildRedirect(request.redirectUri, { code, state: request.state, iss: request.issuer }), now);
    this.options.logger.info('pairing approved', { device: tag });
  }

  /**
   * Removes and returns the grant for [code], or null when it is unknown, used or expired. The code
   * is consumed whatever happens next: call [finishExchange] with the outcome.
   */
  takeCode(code: string): CodeGrant | null {
    const key = hashCode(code);
    const grant = this.codes.get(key);
    if (grant === undefined) return null;
    this.codes.delete(key);
    if (this.now() >= grant.expiresAt) {
      this.finishExchange(grant, false);
      return null;
    }
    return grant;
  }

  /**
   * Tells the device whether the agent got the token (`pair_complete`) or not (`pair_abandoned`,
   * so the phone deletes it), and drops the token from memory.
   */
  finishExchange(grant: CodeGrant, succeeded: boolean): void {
    grant.accessToken = '';
    this.notify(
      grant.deviceId,
      succeeded
        ? { type: 'pair_complete', id: grant.pairingId, tokenId: grant.tokenId }
        : { type: 'pair_abandoned', id: grant.pairingId, tokenId: grant.tokenId },
    );
    this.options.logger.info(succeeded ? 'pairing completed' : 'pairing abandoned', { device: deviceTag(grant.deviceId) });
  }

  /**
   * A device connection closed: its open prompts are gone, so pairings sent over [channel] that are
   * still pending can never be approved. They end as expired.
   */
  connectionClosed(channel: Channel): void {
    const now = this.now();
    for (const record of this.records.values()) {
      if (record.status === 'pending' && record.channel === channel) {
        this.decide(record, 'expired', denialRedirect(record.request, 'The phone disconnected before answering'), now);
      }
    }
  }

  /**
   * A device (re)connected on [channel]: delivers notices that could not be sent while it was
   * offline, and re-sends its still-pending pair_requests (same id, code and expiry) that went out
   * on an older connection. That connection may have been dead without the relay noticing yet, and
   * the phone drops its prompts when a connection ends; once moved, its close no longer expires them.
   */
  deviceConnected(deviceId: string, channel: Channel): void {
    for (const frame of this.notices.drain(deviceId)) this.notify(deviceId, frame);
    const now = this.now();
    for (const record of this.records.values()) {
      if (record.request.deviceId !== deviceId || record.channel === channel) continue;
      this.expireIfDue(record, now);
      if (record.status !== 'pending') continue;
      const sent = this.options.deliver(deviceId, pairRequestFrame(record));
      if (sent !== null) record.channel = sent;
    }
  }

  /**
   * Applies every due expiration: pending pairings past their TTL (→ `pair_cancel`), codes never
   * exchanged (→ `pair_abandoned`), retention of decided pairings, hourly counters and stale notices.
   */
  sweep(): void {
    const now = this.now();
    for (const [id, record] of this.records) {
      this.expireIfDue(record, now);
      if (record.status !== 'pending' && now - record.decidedAt >= this.retainMs) this.records.delete(id);
    }
    for (const [key, grant] of this.codes) {
      if (now >= grant.expiresAt) {
        this.codes.delete(key);
        this.finishExchange(grant, false);
      }
    }
    for (const [key, times] of this.history) {
      const kept = times.filter((t) => now - t < HOUR_MS);
      if (kept.length === 0) this.history.delete(key);
      else this.history.set(key, kept);
    }
    this.notices.sweep();
  }

  /**
   * Relay shutdown: cancels open prompts, abandons unexchanged tokens on connected devices, and
   * forgets everything (including the tokens held in memory).
   */
  shutdown(): void {
    for (const record of this.records.values()) {
      if (record.status === 'pending') this.options.deliver(record.request.deviceId, { type: 'pair_cancel', id: record.id });
    }
    for (const grant of this.codes.values()) {
      grant.accessToken = '';
      this.options.deliver(grant.deviceId, { type: 'pair_abandoned', id: grant.pairingId, tokenId: grant.tokenId });
    }
    this.records.clear();
    this.codes.clear();
    this.history.clear();
    this.notices.clear();
  }

  private expireIfDue(record: PairingRecord, now: number): void {
    if (record.status !== 'pending' || now < record.expiresAt) return;
    const redirect = denialRedirect(record.request, 'The request expired before it was approved on the phone');
    this.decide(record, 'expired', redirect, record.expiresAt);
    this.options.deliver(record.request.deviceId, { type: 'pair_cancel', id: record.id });
    this.options.logger.info('pairing expired', { device: deviceTag(record.request.deviceId) });
  }

  private decide(record: PairingRecord, status: Exclude<PairingStatus, 'pending'>, redirect: string, now: number): void {
    record.status = status;
    record.redirect = redirect;
    record.decidedAt = now;
  }

  private findSameFlow(request: AuthorizationRequest): PairingRecord | undefined {
    for (const record of this.records.values()) {
      const r = record.request;
      if (
        (record.status === 'pending' || record.status === 'approved') &&
        r.deviceId === request.deviceId &&
        r.clientId === request.clientId &&
        r.redirectUri === request.redirectUri &&
        r.codeChallenge === request.codeChallenge &&
        r.state === request.state
      ) {
        return record;
      }
    }
    return undefined;
  }

  /** Sends a completion notice now, or queues it until the device reconnects. */
  private notify(deviceId: string, frame: RelayPairFrame): void {
    if (this.options.deliver(deviceId, frame) === null) this.notices.queue(deviceId, frame);
  }

  private recent(key: string, now: number): number {
    return (this.history.get(key) ?? []).filter((t) => now - t < HOUR_MS).length;
  }

  private remember(key: string, now: number): void {
    const times = (this.history.get(key) ?? []).filter((t) => now - t < HOUR_MS);
    times.push(now);
    this.history.set(key, times);
  }
}

function view(record: PairingRecord): PairingView {
  return {
    id: record.id,
    deviceId: record.request.deviceId,
    pageToken: record.pageToken,
    displayCode: record.displayCode,
    clientName: record.request.clientName,
    redirectHost: record.redirectHost,
    expiresAt: record.expiresAt,
    status: record.status,
    redirect: record.redirect,
    implicitDevice: record.request.implicitDevice === true,
  };
}

/** The `pair_request` frame that prompts the phone (§5.3 step 5). */
function pairRequestFrame(
  prompt: Pick<PairingRecord, 'id' | 'request' | 'origin' | 'displayCode' | 'redirectHost' | 'expiresAt'>,
): PairRequestFrame {
  return {
    type: 'pair_request',
    id: prompt.id,
    clientName: prompt.request.clientName,
    redirectHost: prompt.redirectHost,
    displayCode: prompt.displayCode,
    remoteAddress: prompt.origin.remoteAddress,
    ...(prompt.origin.userAgent === undefined ? {} : { userAgent: prompt.origin.userAgent }),
    expiresAt: prompt.expiresAt,
  };
}

function denialRedirect(request: AuthorizationRequest, description: string): string {
  return buildRedirect(request.redirectUri, {
    error: 'access_denied',
    error_description: description,
    state: request.state,
    iss: request.issuer,
  });
}

/** `p_` + 128 random bits. */
function newPairingId(): string {
  return `p_${randomBytes(16).toString('base64url')}`;
}

function newDisplayCode(): string {
  let code = '';
  for (let i = 0; i < DISPLAY_CODE_LENGTH; i++) code += DISPLAY_CODE_ALPHABET[randomInt(DISPLAY_CODE_ALPHABET.length)];
  return code;
}

function hashCode(code: string): string {
  return createHash('sha256').update(code, 'utf8').digest('hex');
}
