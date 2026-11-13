import assert from 'node:assert/strict';
import { test } from 'node:test';
import { RateLimiter } from '../src/rateLimit.js';

function limiter(overrides: Partial<ConstructorParameters<typeof RateLimiter>[0]> = {}) {
  const clock = { now: 1_000_000 };
  const rl = new RateLimiter({
    windowMs: 60_000,
    maxRequests: 5,
    maxAuthFailures: 3,
    blockMs: 60_000,
    now: () => clock.now,
    ...overrides,
  });
  return { rl, clock };
}

test('allows up to maxRequests per window, then refuses until the window rolls over', () => {
  const { rl, clock } = limiter();
  for (let i = 0; i < 5; i++) assert.equal(rl.hit('a').allowed, true);
  const refused = rl.hit('a');
  assert.equal(refused.allowed, false);
  assert.equal(!refused.allowed && refused.reason, 'rate');
  assert.equal(!refused.allowed && refused.retryAfterSec, 60);
  assert.equal(rl.hit('b').allowed, true, 'other keys are independent');
  clock.now += 30_000;
  assert.equal(rl.hit('a').allowed, false);
  clock.now += 30_000;
  assert.equal(rl.hit('a').allowed, true);
});

test('auth failures beyond the budget block the key for blockMs', () => {
  const { rl, clock } = limiter({ maxRequests: 1000 });
  for (let i = 0; i < 3; i++) rl.recordAuthFailure('a');
  assert.equal(rl.check('a').allowed, true, 'exactly at the budget is still allowed');
  rl.recordAuthFailure('a');
  const blocked = rl.hit('a');
  assert.equal(blocked.allowed, false);
  assert.equal(!blocked.allowed && blocked.reason, 'blocked');
  assert.equal(rl.check('a').allowed, false);
  clock.now += 59_000;
  assert.equal(rl.hit('a').allowed, false);
  clock.now += 1_000;
  assert.equal(rl.hit('a').allowed, true);
  assert.equal(rl.check('a').allowed, true);
});

test('further failures while blocked do not extend the block indefinitely', () => {
  const { rl, clock } = limiter({ maxRequests: 1000 });
  for (let i = 0; i < 4; i++) rl.recordAuthFailure('a');
  clock.now += 30_000;
  rl.recordAuthFailure('a');
  clock.now += 30_000;
  assert.equal(rl.check('a').allowed, true);
});

test('sweep drops idle entries but keeps blocked ones', () => {
  const { rl, clock } = limiter();
  rl.hit('idle');
  clock.now += 30_000;
  for (let i = 0; i < 4; i++) rl.recordAuthFailure('bad');
  clock.now += 30_000;
  rl.sweep();
  assert.equal(rl.size, 1, 'idle entry dropped, blocked entry kept');
  clock.now += 30_000;
  rl.sweep();
  assert.equal(rl.size, 0);
});

test('memory is bounded by maxEntries (fails open for new keys)', () => {
  const { rl, clock } = limiter({ maxEntries: 3, maxRequests: 1 });
  for (const key of ['a', 'b', 'c']) rl.hit(key);
  assert.equal(rl.size, 3);
  assert.equal(rl.hit('d').allowed, true);
  assert.equal(rl.hit('d').allowed, true, 'untracked key is not limited');
  assert.equal(rl.size, 3);
  clock.now += 60_000;
  assert.equal(rl.hit('e').allowed, true);
  assert.equal(rl.size, 1, 'a full table is swept before giving up');
});
