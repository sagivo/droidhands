import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createLogger } from '../src/logger.js';
import { createRelay } from '../src/relay.js';
import { connectDevice, connectReady, newIdentity, postMcp, tokenFor } from './helpers.js';

test('logs (even at debug) never contain tokens, secrets, access keys or bodies', async () => {
  const lines: string[] = [];
  const accessKey = 'access-key-that-must-not-leak';
  const relay = await createRelay(
    { port: 0, host: '127.0.0.1', accessKey, allowTokenInUrl: true, requestTimeoutMs: 100 },
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
