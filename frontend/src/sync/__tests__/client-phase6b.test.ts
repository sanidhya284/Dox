import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatClient, type Bug, type ClientConfig } from '../client';
import { FakeServer } from './fakeServer';

/**
 * Phase 6b additions to the sync core (ADR-0012), same style as client.test.ts: a fake server, fake timers,
 * and a negative form (a deliberate bug flag) wherever a guarantee could silently stop holding.
 */

const C = 'conv-1';
const ME = 'me';
const FAST: Partial<ClientConfig> = {
  ackTimeoutMs: 400, antiEntropyMs: 60_000, backoffBaseMs: 20, backoffCapMs: 300, tickMs: 20,
};

let server: FakeServer;
let client: ChatClient;
let ids = 0;

function makeClient(opts: { bugs?: Bug[]; config?: Partial<ClientConfig>; onOtherFrame?: (f: unknown) => void } = {}) {
  client = new ChatClient({
    api: server.api(),
    openSocket: server.openSocket(ME),
    userId: ME,
    config: { ...FAST, ...opts.config },
    random: () => 0.5,
    newId: () => `cmid-${++ids}`,
    bugs: opts.bugs,
    onOtherFrame: opts.onOtherFrame,
  });
  return client.start();
}

const settle = (ms = 50) => vi.advanceTimersByTimeAsync(ms);
const push = (seq: number, body: string) => [...server.sockets][0].deliver({
  v: 1, type: 'message', message: { id: `m${seq}`, conversationId: C, seq, senderId: 'x', clientMsgId: null, body },
});
const historyFetches = () => server.gets.filter((g) => g.startsWith(`/api/conversations/${C}/`)).length;

beforeEach(() => {
  vi.useFakeTimers();
  server = new FakeServer([C]);
});

afterEach(() => {
  client?.stop();
  vi.useRealTimers();
});

describe('RATE_LIMITED waits retryAfterMs', () => {
  async function refusedOnce(bugs: Bug[] = []): Promise<string> {
    makeClient({ bugs, config: { ackTimeoutMs: 10_000 } });
    await settle();
    server.rateLimitNext = 1;
    server.retryAfterMs = 3000;
    const id = client.send(C, 'later')!;
    await settle(20); // sent and refused
    return id;
  }

  it('keeps the send, holds it for retryAfterMs, then resends it with the SAME id', async () => {
    const id = await refusedOnce();
    await settle(2500);
    expect(server.sendLog.map((e) => e.clientMsgId)).toEqual([id]); // nothing before 3 s
    expect(client.pending(C).map((e) => e.clientMsgId)).toEqual([id]);
    await settle(1000);
    expect(server.sendLog.map((e) => e.clientMsgId)).toEqual([id, id]);
    expect(server.rows.get(C)!.map((m) => m.body)).toEqual(['later']);
    expect(client.pending(C)).toEqual([]);
  });

  it('a reconnect does not cut the wait short', async () => {
    const id = await refusedOnce();
    server.closeAll(1006);
    await settle(1500); // reconnected meanwhile (backoff is short)
    expect(client.isConnected).toBe(true);
    expect(server.sendLog.filter((e) => e.clientMsgId === id)).toHaveLength(1);
    await settle(2000);
    expect(server.sendLog.filter((e) => e.clientMsgId === id)).toHaveLength(2);
  });

  it('negative form: ignoring retryAfterMs resends on the next tick', async () => {
    await refusedOnce(['IGNORE_RETRY_AFTER']);
    await settle(200);
    expect(server.sendLog.length).toBeGreaterThan(1);
  });
});

describe('RECIPIENT_UNAVAILABLE is terminal', () => {
  it('drops the send into failedSends with its code and never resends it', async () => {
    makeClient();
    await settle();
    server.rateLimitNext = 1;
    server.refuseCode = 'RECIPIENT_UNAVAILABLE';
    const id = client.send(C, 'blocked?')!;
    await settle(2000);
    expect(client.pending(C)).toEqual([]);
    expect(client.failedSends.map((f) => [f.body, f.code])).toEqual([['blocked?', 'RECIPIENT_UNAVAILABLE']]);
    expect(server.sendLog.filter((e) => e.clientMsgId === id)).toHaveLength(1);
  });

  it('retryFailed sends the same body again as a NEW send', async () => {
    makeClient();
    await settle();
    server.rateLimitNext = 1;
    server.refuseCode = 'VALIDATION_ERROR';
    const id = client.send(C, 'again')!;
    await settle(20);
    expect(client.failedSends.map((f) => f.clientMsgId)).toEqual([id]);
    const second = client.retryFailed(id);
    expect(second).not.toBeNull();
    expect(second).not.toBe(id);
    await settle(100);
    expect(client.failedSends).toEqual([]);
    expect(client.messages(C).map((m) => m.body)).toContain('again');
  });
});

describe('a conversation that is gone (404) is dropped without a retry loop', () => {
  it('a 404 on its history forgets it; later pushes and anti-entropy rounds never fetch it again', async () => {
    makeClient({ config: { antiEntropyMs: 300 } });
    await settle();
    server.commit(C, 'one');
    await settle();
    expect(client.conversationIds()).toEqual([C]);

    server.remove(C);
    push(5, 'skips ahead'); // a gap: the client fetches and gets 404
    await settle(50);
    expect(client.conversationIds()).toEqual([]);
    expect(client.isGone(C)).toBe(true);
    const after404 = historyFetches();

    push(9, 'late push');
    await settle(3000); // several anti-entropy rounds
    expect(historyFetches()).toBe(after404);
    expect(client.messages(C)).toEqual([]);
  });

  it('negative form: without forgetting, every later push fetches the 404 again', async () => {
    makeClient({ bugs: ['NO_FORGET_ON_404'] });
    await settle();
    server.remove(C);
    for (const seq of [5, 9, 13]) {
      push(seq, 'push');
      await settle(50);
    }
    expect(historyFetches()).toBeGreaterThan(1);
  });
});

describe('onOtherFrame', () => {
  it('passes cursor frames to the UI', async () => {
    const seen: unknown[] = [];
    makeClient({ onOtherFrame: (f) => seen.push(f) });
    await settle();
    const cursor = { v: 1, type: 'cursor', conversationId: C, userId: 'u2', lastDeliveredSeq: 3, lastReadSeq: 2 };
    [...server.sockets][0].deliver(cursor);
    await settle(5);
    expect(seen).toEqual([cursor]);
  });
});
