import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createLogger } from '../src/logger.js';
import { createRelay } from '../src/relay.js';
import { connectDevice, connectReady, newIdentity, postMcp, testRelaySecret, tokenFor } from './helpers.js';
import { approveNext, exchange, newPkce, openAuthorize, pollUntilDecided, registerClient } from './oauthHelpers.js';

test('logs (even at debug) never contain tokens, secrets, access keys or bodies', async () => {
  const lines: string[] = [];
  const accessKey = 'access-key-that-must-not-leak';
  const relay = await createRelay(
    { port: 0, host: '127.0.0.1', accessKey, allowTokenInUrl: true, requestTimeoutMs: 100, relaySecret: testRelaySecret() },
    { logger: createLogger('debug', (line) => lines.push(line)) },
  );
  try {
    const device = await connectReady(relay, { hello: { accessKey } });
    const token = tokenFor(device.identity.deviceId);
    const requestBody = '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"text":"private-request-text"}}';
    const responseBody = '{"jsonrpc":"2.0","id":1,"result":{"text":"private-response-text"}}';

    const viaHeader = postMcp(relay, { token, body: requestBody });
    const r1 = await device.nextRequest();
    device.respond(r1.id, 200, responseBody, { 'content-type': 'application/json' });
    await viaHeader;

    const viaPath = postMcp(relay, { path: `/mcp/${token}`, body: requestBody });
    const r2 = await device.nextRequest();
    device.respond(r2.id, 401, responseBody);
    await viaPath;

    await postMcp(relay, { token, body: requestBody }); // times out -> 504
    await postMcp(relay, { token: `${token}x`, body: requestBody }); // malformed -> 401
    device.ws.send('not json private-frame-text');

    const impostor = newIdentity();
    const bad = await connectDevice(relay, { hello: { accessKey: 'wrong-key-also-secret', deviceId: impostor.deviceId } });
    await bad.closed;
    const wrongKey = await connectDevice(relay, { hello: { accessKey: 'wrong-key-also-secret' } });
    await wrongKey.closed;

    const output = lines.join('\n');
    assert.ok(lines.length > 5, 'something was logged');
    assert.match(output, new RegExp(`"device":"${device.identity.deviceId.slice(0, 6)}"`));
    for (const secret of [
      token,
      token.split('.')[3] as string,
      device.identity.secret,
      bad.identity.secret,
      wrongKey.identity.secret,
      device.identity.deviceId,
      accessKey,
      'wrong-key-also-secret',
      'private-request-text',
      'private-response-text',
      'private-frame-text',
    ]) {
      assert.ok(!output.includes(secret), `log output leaked ${secret.slice(0, 12)}…`);
    }
    for (const line of lines) assert.doesNotThrow(() => JSON.parse(line), 'every line is JSON');
  } finally {
    await relay.close();
  }
});

test('logs of a full pairing never contain codes, tokens, verifiers, page tokens, state or the relay secret', async () => {
  const lines: string[] = [];
  const relaySecret = testRelaySecret();
  const relay = await createRelay(
    { port: 0, host: '127.0.0.1', relaySecret },
    { logger: createLogger('debug', (line) => lines.push(line)) },
  );
  try {
    const device = await connectReady(relay, { hello: { deviceName: 'Private Phone Name' } });
    const clientId = await registerClient(relay);
    const pkce = newPkce();
    const state = 'private-state-value';
    const page = await openAuthorize(relay, { clientId, pkce, deviceId: device.identity.deviceId, state });
    const { accessToken } = await approveNext(device);
    const status = await pollUntilDecided(page.pollUrl);
    const code = new URL(status.redirect as string).searchParams.get('code') as string;
    await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier });
    await exchange(relay, { code, client_id: clientId, code_verifier: pkce.verifier }); // reuse -> logged failure
    await device.nextFrame('pair_complete');

    const output = lines.join('\n');
    assert.match(output, /"msg":"token issued"/);
    assert.match(output, /"msg":"token request rejected"/);
    const pageToken = new URL(page.pollUrl).searchParams.get('t') as string;
    for (const secret of [
      accessToken,
      accessToken.split('.')[3] as string,
      code,
      pkce.verifier,
      pkce.challenge,
      pageToken,
      state,
      relaySecret,
      clientId,
      'Private Phone Name',
      device.identity.deviceId,
    ]) {
      assert.ok(!output.includes(secret), `log output leaked ${secret.slice(0, 12)}…`);
    }
  } finally {
    await relay.close();
  }
});
