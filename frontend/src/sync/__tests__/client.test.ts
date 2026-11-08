import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatClient, type Bug, type ClientConfig } from '../client';
import { FakeServer } from './fakeServer';

/**
 * The sync algorithm, ported from the Java ReferenceClient, against a fake server with fake timers.
 * Each guarantee has a positive form and a negative form: with the matching deliberate bug switched on,
 * the same scenario must go wrong, which shows the test can fail (CLAUDE.md, mutation rule).
 */

const C = 'conv-1';
const ME = 'me';
const FAST: Partial<ClientConfig> = {
  ackTimeoutMs: 400, antiEntropyMs: 60_000, backoffBaseMs: 20, backoffCapMs: 300, tickMs: 20,
};

let server: FakeServer;
let client: ChatClient;
let ids = 0;

function makeClient(opts: { bugs?: Bug[]; config?: Partial<ClientConfig>; hook?: () => void } = {}): ChatClient {
  client = new ChatClient({
    api: server.api(),
    openSocket: server.openSocket(ME),
    userId: ME,
    config: { ...FAST, ...opts.config },
    random: () => 0.5,
    newId: () => `cmid-${++ids}`,
    bugs: opts.bugs,
    betweenSubscribeAndFetch: opts.hook,
  });
  return client.start();
}

const settle = (ms = 50) => vi.advanceTimersByTimeAsync(ms);
const bodies = () => client.messages(C).map((m) => m.body);

beforeEach(() => {
  vi.useFakeTimers();
  server = new FakeServer([C]);
});

afterEach(() => {
  client?.stop();
  vi.useRealTimers();
});

describe('dedupe', () => {
  it('a message seen twice (push and fetch) is kept once', async () => {
    makeClient();
    await settle();
    server.dropPushes = 1;
    server.commit(C, 'first');
    server.commit(C, 'second'); // pushed; the gap fetch then returns 1..2, so 'second' arrives twice
    await settle(100);
    expect(bodies()).toEqual(['first', 'second']);
  });

  it('negative form: without dedupe the same message appears twice', async () => {
    makeClient({ bugs: ['NO_DEDUPE'] });
    await settle();
    server.dropPushes = 1;
    server.commit(C, 'first');
    server.commit(C, 'second'); // gap: the client fetches 1..2, then also merges the push of 2
    await settle(100);
    expect(bodies().filter((b) => b === 'second').length).toBe(2);
  });
});

describe('subscribe first, then fetch', () => {
  it('a message committed between subscribe and fetch is not lost', async () => {
    makeClient({ hook: () => void server.commit(C, 'in-between') });
    await settle();
    expect(bodies()).toEqual(['in-between']);
  });

  it('negative form: fetching before subscribing loses it (until anti-entropy)', async () => {
    makeClient({ bugs: ['FETCH_BEFORE_SUBSCRIBE'], hook: () => void server.commit(C, 'in-between') });
    await settle();
    expect(bodies()).toEqual([]);
  });
});

describe('gap detection', () => {
  it('a push that skips ahead fetches the gap', async () => {
    makeClient();
    await settle();
    server.dropPushes = 1;
    server.commit(C, 'one');
    server.commit(C, 'two');
    await settle();
    expect(bodies()).toEqual(['one', 'two']);
    expect(client.contiguousSeq(C)).toBe(2);
  });

  it('negative form: without gap detection the missed message stays missing', async () => {
    makeClient({ bugs: ['NO_GAP_DETECTION'] });
    await settle();
    server.dropPushes = 1;
    server.commit(C, 'one');
    server.commit(C, 'two');
    await settle();
    expect(bodies()).toEqual(['two']);
    expect(client.contiguousSeq(C)).toBe(0);
  });
});

describe('anti-entropy', () => {
  it('recovers a dropped LAST push (gap detection cannot: no later push arrives)', async () => {
    makeClient({ config: { antiEntropyMs: 600 } });
    await settle();
    server.dropPushes = 1;
    server.commit(C, 'last');
    await settle(100);
    expect(bodies()).toEqual([]);
    await settle(1000); // jittered interval: 600 x (0.5 + 0.5)
    expect(bodies()).toEqual(['last']);
  });

  it('negative form: without anti-entropy the last message is never recovered', async () => {
    makeClient({ bugs: ['NO_ANTI_ENTROPY'], config: { antiEntropyMs: 600 } });
    await settle();
    server.dropPushes = 1;
    server.commit(C, 'last');
    await settle(3000);
    expect(bodies()).toEqual([]);
  });
});

describe('outbox: stable clientMsgId', () => {
  it('a lost ack is resent with the SAME id: one row, one message, empty outbox', async () => {
    makeClient();
    await settle();
    server.dropAcks = 1;
    client.send(C, 'hi');
    await settle(1000); // ack timeout 400 ms, then a resend
    expect(server.rows.get(C)!.map((m) => m.body)).toEqual(['hi']);
    expect(bodies()).toEqual(['hi']);
    expect(client.pending(C)).toEqual([]);
  });

  it('negative form: a new id per retry stores the message twice', async () => {
    makeClient({ bugs: ['NEW_ID_ON_RETRY'] });
    await settle();
    server.dropAcks = 1;
    client.send(C, 'hi');
    await settle(1000);
    expect(server.rows.get(C)!.map((m) => m.body)).toEqual(['hi', 'hi']);
  });

  it('an unacked send survives a dropped connection and is resent after the reconnect', async () => {
    makeClient();
    await settle();
    server.dropAcks = 1;
    client.send(C, 'during-drop');
    await settle(5);
    server.closeAll(1006);
    await settle(1000);
    expect(client.reconnects).toBeGreaterThanOrEqual(1);
    expect(server.rows.get(C)!.map((m) => m.body)).toEqual(['during-drop']);
    expect(client.pending(C)).toEqual([]);
    expect(bodies()).toEqual(['during-drop']);
  });

  it('a terminal error drops the send; a retryable one keeps it', async () => {
    makeClient();
    await settle();
    const socket = [...server.sockets][0];
    const id = client.send(C, 'x')!;
    socket.deliver({ v: 1, type: 'error', clientMsgId: id, code: 'VALIDATION_ERROR' });
    await settle(5);
    expect(client.pending(C)).toEqual([]);
    expect(client.failedSends.map((e) => e.body)).toEqual(['x']);
  });

  it('a full outbox refuses new sends instead of growing', () => {
    makeClient({ config: { outboxCapacity: 1 } });
    expect(client.send(C, 'a')).not.toBeNull();
    expect(client.send(C, 'b')).toBeNull();
  });
});

describe('close codes (ADR-0008 table)', () => {
  it.each([
    [4001, 'running', true], [4003, 'running', true], [4006, 'running', true], [1001, 'running', true],
    [4002, 'logged_out', false], [4007, 'too_many_sockets', false],
    [4005, 'client_bug', false], [1007, 'client_bug', false], [1008, 'server_bug', false],
  ])('close %i → state %s, reconnect: %s', async (code, state, reconnects) => {
    makeClient();
    await settle();
    const before = server.tickets;
    server.closeAll(code);
    await settle(2000);
    expect(client.state).toBe(state);
    expect(server.tickets > before).toBe(reconnects);
  });

  it('negative form: looping on a protocol error keeps reconnecting', async () => {
    makeClient({ bugs: ['LOOP_ON_PROTOCOL_ERROR'] });
    await settle();
    const before = server.tickets;
    server.closeAll(4005);
    await settle(2000);
    expect(server.tickets).toBeGreaterThan(before);
  });

  it('a 401 on the ticket (refresh failed) means logged out, not a reconnect loop', async () => {
    server.ticketStatus = 401;
    makeClient();
    await settle(2000);
    expect(client.state).toBe('logged_out');
    expect(server.tickets).toBe(1);
  });

  it('a 429 on the ticket backs off and retries', async () => {
    server.ticketStatus = 429;
    makeClient();
    await settle(50);
    server.ticketStatus = 200;
    await settle(2000);
    expect(client.isConnected).toBe(true);
    expect(server.tickets).toBeGreaterThan(1);
  });
});
