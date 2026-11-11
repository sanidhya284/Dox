import type { Api, ApiResult, Msg, SocketLike } from '../client';

/**
 * In-memory stand-in for the backend: REST (heads, ?after= paging, tickets) and sockets, with the same
 * semantics the real server guarantees: idempotent sends by (sender, clientMsgId), contiguous seq,
 * ack only to the sender's socket, pushes to every other socket. Faults: drop the next pushes or acks,
 * close a socket with any code, refuse tickets.
 */
export class FakeServer {
  readonly rows = new Map<string, Msg[]>();
  private readonly byClientMsgId = new Map<string, Msg>();
  readonly sockets = new Set<FakeSocket>();
  dropPushes = 0;
  dropAcks = 0;
  tickets = 0;
  ticketStatus = 200;
  /** The next N sends are refused with `refuseCode` (RATE_LIMITED carries retryAfterMs); nothing is stored. */
  rateLimitNext = 0;
  retryAfterMs = 0;
  refuseCode = 'RATE_LIMITED';
  /** Every send frame received, with the fake clock's time (to check when retries happen). */
  readonly sendLog: { clientMsgId: string; at: number }[] = [];
  /** Every GET path, in order. */
  readonly gets: string[] = [];
  private readonly removed = new Set<string>();
  private nextId = 1;

  constructor(readonly conversations: string[]) {
    for (const c of conversations) {
      this.rows.set(c, []);
    }
  }

  /** A message committed by someone else (over REST, say): stored, then pushed to every socket. */
  commit(conversationId: string, body: string, senderId = 'other-user'): Msg {
    const msg = this.store(conversationId, body, senderId, null);
    for (const s of this.sockets) {
      this.push(s, msg);
    }
    return msg;
  }

  private store(conversationId: string, body: string, senderId: string, clientMsgId: string | null): Msg {
    const list = this.rows.get(conversationId)!;
    const msg: Msg = { id: `m${this.nextId++}`, conversationId, seq: list.length + 1, senderId, clientMsgId, body };
    list.push(msg);
    return msg;
  }

  private push(s: FakeSocket, msg: Msg): void {
    if (this.dropPushes > 0) {
      this.dropPushes--;
      return;
    }
    s.deliver({ v: 1, type: 'message', message: msg });
  }

  /** The conversation is deleted (account removal): heads no longer list it, its history is 404. */
  remove(conversationId: string): void {
    this.removed.add(conversationId);
  }

  receive(from: FakeSocket, text: string): void {
    const f = JSON.parse(text);
    if (f.type !== 'send') {
      return;
    }
    this.sendLog.push({ clientMsgId: f.clientMsgId, at: Date.now() });
    if (this.rateLimitNext > 0) {
      this.rateLimitNext--;
      from.deliver({ v: 1, type: 'error', clientMsgId: f.clientMsgId, code: this.refuseCode, message: 'refused',
        ...(this.refuseCode === 'RATE_LIMITED' ? { retryAfterMs: this.retryAfterMs } : {}) });
      return;
    }
    const key = `${from.userId}|${f.clientMsgId}`;
    let msg = this.byClientMsgId.get(key);
    const duplicate = msg !== undefined;
    if (!msg) {
      msg = this.store(f.conversationId, f.body, from.userId, f.clientMsgId);
      this.byClientMsgId.set(key, msg);
    }
    if (this.dropAcks > 0) {
      this.dropAcks--;
    } else {
      from.deliver({ v: 1, type: 'ack', clientMsgId: f.clientMsgId, messageId: msg.id, seq: msg.seq });
    }
    if (!duplicate) {
      for (const s of this.sockets) {
        if (s !== from) {
          this.push(s, msg);
        }
      }
    }
  }

  api(): Api {
    const ok = <T>(data: T): Promise<ApiResult<T>> => Promise.resolve({ ok: true, status: 200, data });
    return {
      get: <T>(path: string): Promise<ApiResult<T>> => {
        this.gets.push(path);
        if (path === '/api/conversations/heads') {
          return ok(this.conversations.filter((c) => !this.removed.has(c))
            .map((c) => ({ conversationId: c, lastSeq: this.rows.get(c)!.length }))) as Promise<ApiResult<T>>;
        }
        const m = path.match(/^\/api\/conversations\/([^/]+)\/messages\?after=(\d+)&limit=(\d+)$/);
        if (m && this.removed.has(m[1])) {
          return Promise.resolve({ ok: false, status: 404 });
        }
        if (m) {
          const all = this.rows.get(m[1])!.filter((x) => x.seq > Number(m[2]));
          const page = all.slice(0, Number(m[3]));
          return ok({ messages: page, hasMore: all.length > page.length }) as Promise<ApiResult<T>>;
        }
        return Promise.resolve({ ok: false, status: 404 });
      },
      post: <T>(path: string): Promise<ApiResult<T>> => {
        if (path === '/api/ws/ticket') {
          this.tickets++;
          if (this.ticketStatus !== 200) {
            return Promise.resolve({ ok: false, status: this.ticketStatus });
          }
          return ok({ ticket: `t${this.tickets}` }) as Promise<ApiResult<T>>;
        }
        return Promise.resolve({ ok: false, status: 404 });
      },
    };
  }

  openSocket(userId: string): (ticket: string) => SocketLike {
    return () => {
      const s = new FakeSocket(this, userId);
      setTimeout(() => {
        this.sockets.add(s);
        s.onopen?.();
      }, 1);
      return s;
    };
  }

  /** The server closes every socket with this code (deploy 1001, lifetime 4001, theft 4002, ...). */
  closeAll(code: number): void {
    for (const s of [...this.sockets]) {
      s.serverClose(code);
    }
  }
}

export class FakeSocket implements SocketLike {
  onopen: (() => void) | null = null;
  onmessage: ((ev: { data: string }) => void) | null = null;
  onclose: ((ev: { code: number }) => void) | null = null;
  readonly sent: string[] = [];
  private open = true;

  constructor(private readonly server: FakeServer, readonly userId: string) {}

  send(data: string): void {
    if (!this.open) {
      return;
    }
    this.sent.push(data);
    setTimeout(() => this.server.receive(this, data), 1);
  }

  deliver(frame: unknown): void {
    if (this.open) {
      const data = JSON.stringify(frame);
      setTimeout(() => this.onmessage?.({ data }), 1);
    }
  }

  close(code = 1000): void {
    this.serverClose(code);
  }

  serverClose(code: number): void {
    if (!this.open) {
      return;
    }
    this.open = false;
    this.server.sockets.delete(this);
    setTimeout(() => this.onclose?.({ code }), 1);
  }
}
