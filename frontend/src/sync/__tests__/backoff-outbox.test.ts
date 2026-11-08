import { describe, expect, it } from 'vitest';
import { Backoff } from '../backoff';
import { Outbox } from '../outbox';
import { closeAction, isTerminalError } from '../errors';

describe('Backoff (full jitter)', () => {
  it('ceiling doubles from base and stops at cap', () => {
    const b = new Backoff(100, 10_000);
    expect([0, 1, 2, 3, 6, 7, 60].map((a) => b.ceiling(a))).toEqual([100, 200, 400, 800, 6400, 10_000, 10_000]);
  });

  it('delay is random in [0, ceiling) and the attempt resets', () => {
    const b = new Backoff(100, 10_000, () => 0.999);
    expect(b.nextDelay()).toBeLessThan(100);
    expect(b.nextDelay()).toBeLessThan(200);
    expect(b.nextDelay()).toBeGreaterThan(390);
    b.reset();
    expect(new Backoff(100, 10_000, () => 0).nextDelay()).toBe(0);
    expect(b.attempts()).toBe(0);
  });

  it('refuses nonsense bounds', () => {
    expect(() => new Backoff(0, 10)).toThrow();
    expect(() => new Backoff(100, 10)).toThrow();
  });
});

describe('Outbox', () => {
  it('keeps the id across retries and is due only after the ack timeout', () => {
    let n = 0;
    const o = new Outbox(10, () => `id${++n}`);
    const e = o.enqueue('c', 'hi')!;
    expect(o.dueForRetry(0, 400).map((x) => x.clientMsgId)).toEqual(['id1']);
    o.markSent(e.clientMsgId, 1000);
    expect(o.dueForRetry(1300, 400)).toEqual([]);
    expect(o.dueForRetry(1401, 400).map((x) => x.clientMsgId)).toEqual(['id1']);
    o.markDue(e.clientMsgId);
    expect(o.dueForRetry(1001, 400).map((x) => x.attempts)).toEqual([1]);
    expect(o.remove('id1')?.body).toBe('hi');
    expect(o.size()).toBe(0);
  });
});

describe('error and close classification', () => {
  it('terminal error codes drop the send; unknown codes retry', () => {
    expect(isTerminalError('VALIDATION_ERROR')).toBe(true);
    expect(isTerminalError('IDEMPOTENCY_KEY_CONFLICT')).toBe(true);
    expect(isTerminalError('INTERNAL_ERROR')).toBe(false);
    expect(isTerminalError('SOMETHING_NEW')).toBe(false);
  });

  it('unknown close codes reconnect', () => {
    expect(closeAction(4999)).toBe('reconnect');
    expect(closeAction(1006)).toBe('reconnect');
  });
});
