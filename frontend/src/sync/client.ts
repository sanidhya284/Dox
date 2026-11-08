import { Backoff } from './backoff';
import { Outbox, type OutboxEntry } from './outbox';
import { closeAction, isTerminalError } from './errors';

/**
 * The browser sync client: a TypeScript port of the Java reference client's algorithm
 * (backend/src/test/java/com/chatplatform/client/ReferenceClient.java, ADR-0008).
 *
 * - Stable clientMsgId outbox: resend after an ack timeout and after every reconnect, on the SAME id.
 * - Reconnect with full-jitter backoff; the close code decides (ADR-0008 table).
 * - Subscribe first (open the socket), then fetch what was committed before.
 * - Dedupe by message id; order by seq; a push that skips ahead of the contiguous seq fetches the gap.
 * - Jittered anti-entropy: compare GET /heads with the contiguous seq, fetch where behind.
 *
 * PostgreSQL is the truth; pushes are an optimisation. Socket, API, clock and randomness are injected,
 * so the algorithm is unit-tested with a fake socket and fake timers. The convergence-under-faults test
 * (ConvergenceTest) runs against the JAVA reference client, not this one.
 */

export interface Msg {
  id: string;
  conversationId: string;
  seq: number;
  senderId: string;
  clientMsgId: string | null;
  body: string;
  createdAt?: string;
}

export interface SocketLike {
  send(data: string): void;
  close(code?: number, reason?: string): void;
  onopen: (() => void) | null;
  onmessage: ((ev: { data: string }) => void) | null;
  onclose: ((ev: { code: number }) => void) | null;
}

export type ApiResult<T> = { ok: true; status: number; data: T } | { ok: false; status: number };

/** Authenticated REST; refreshes the access token once on a 401 (see api/http.ts). */
export interface Api {
  get<T>(path: string): Promise<ApiResult<T>>;
  post<T>(path: string, body?: unknown): Promise<ApiResult<T>>;
}

export type ClientState = 'running' | 'logged_out' | 'client_bug' | 'server_bug' | 'too_many_sockets' | 'stopped';

/** Deliberate defects for the tests' negative forms (each test must fail with its bug on). Never set in the app. */
export type Bug =
  | 'NO_DEDUPE'
  | 'FETCH_BEFORE_SUBSCRIBE'
  | 'NO_GAP_DETECTION'
  | 'NO_ANTI_ENTROPY'
  | 'NEW_ID_ON_RETRY'
  | 'LOOP_ON_PROTOCOL_ERROR';

export interface ClientConfig {
  ackTimeoutMs: number;
  antiEntropyMs: number;
  backoffBaseMs: number;
  backoffCapMs: number;
  tickMs: number;
  outboxCapacity: number;
}

/** Production defaults (ADR-0008). */
export const DEFAULT_CONFIG: ClientConfig = {
  ackTimeoutMs: 5000,
  antiEntropyMs: 30000,
  backoffBaseMs: 100,
  backoffCapMs: 10000,
  tickMs: 250,
  outboxCapacity: 1000,
};

export interface ClientDeps {
  api: Api;
  /** Opens /ws?ticket=... (the app builds the URL from location; tests pass a fake). */
  openSocket: (ticket: string) => SocketLike;
  userId: string;
  config?: Partial<ClientConfig>;
  now?: () => number;
  random?: () => number;
  newId?: () => string;
  bugs?: Bug[];
  /** Test hook between subscribe and fetch (the window that fetch-before-subscribe would lose). */
  betweenSubscribeAndFetch?: () => Promise<void> | void;
}

interface ConvState {
  contiguousSeq: number;
  byId: Map<string, Msg>;
  bySeq: Map<number, Msg>;
}

interface Head {
  conversationId: string;
  lastSeq: number;
}

interface Page {
  messages: Msg[];
  hasMore: boolean;
}

export class ChatClient {
  private readonly cfg: ClientConfig;
  private readonly now: () => number;
  private readonly random: () => number;
  private readonly bugs: Set<Bug>;
  private readonly outbox: Outbox;
  private readonly backoff: Backoff;
  private readonly convs = new Map<string, ConvState>();
  private readonly listeners = new Set<() => void>();

  private stateValue: ClientState = 'running';
  private socket: SocketLike | null = null;
  private generation = 0;
  private connected = false;
  private connecting = false;
  private reconnectAt = -1;
  private nextAntiEntropy = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private versionValue = 0;

  reconnects = 0;
  terminalErrors = 0;
  /** Sends the server refused for good (terminal error), for the UI. */
  readonly failedSends: OutboxEntry[] = [];

  constructor(private readonly deps: ClientDeps) {
    this.cfg = { ...DEFAULT_CONFIG, ...deps.config };
    this.now = deps.now ?? (() => Date.now());
    this.random = deps.random ?? Math.random;
    this.bugs = new Set(deps.bugs ?? []);
    this.outbox = new Outbox(this.cfg.outboxCapacity, deps.newId);
    this.backoff = new Backoff(this.cfg.backoffBaseMs, this.cfg.backoffCapMs, this.random);
  }

  // ───────────────────────────── public API ─────────────────────────────

  start(): this {
    this.nextAntiEntropy = this.now() + this.jittered(this.cfg.antiEntropyMs);
    this.timer = setInterval(() => void this.tick(), this.cfg.tickMs);
    void this.connectAndSync();
    return this;
  }

  stop(): void {
    this.setState('stopped');
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.closeSocket();
  }

  /** Queues a send; returns its clientMsgId, or null when the outbox is full. */
  send(conversationId: string, body: string): string | null {
    const entry = this.outbox.enqueue(conversationId, body);
    if (!entry) {
      return null;
    }
    this.changed();
    this.flushDue();
    return entry.clientMsgId;
  }

  /** Known messages of one conversation, ordered by seq. */
  messages(conversationId: string): Msg[] {
    const st = this.convs.get(conversationId);
    return st ? [...st.byId.values()].sort((a, b) => a.seq - b.seq) : [];
  }

  pending(conversationId: string): OutboxEntry[] {
    return this.outbox.pending().filter((e) => e.conversationId === conversationId);
  }

  contiguousSeq(conversationId: string): number {
    return this.convs.get(conversationId)?.contiguousSeq ?? 0;
  }

  conversationIds(): string[] {
    return [...this.convs.keys()];
  }

  get state(): ClientState {
    return this.stateValue;
  }

  get isConnected(): boolean {
    return this.connected;
  }

  /** Changes on every state change; for React's useSyncExternalStore. */
  get version(): number {
    return this.versionValue;
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  // ───────────────────────────── loop ─────────────────────────────

  private async tick(): Promise<void> {
    if (this.stateValue !== 'running') {
      return;
    }
    const now = this.now();
    if (!this.connected && !this.connecting && this.reconnectAt >= 0 && now >= this.reconnectAt) {
      this.reconnectAt = -1;
      this.reconnects++;
      await this.connectAndSync();
      return;
    }
    this.flushDue();
    if (!this.bugs.has('NO_ANTI_ENTROPY') && this.connected && now >= this.nextAntiEntropy) {
      this.nextAntiEntropy = now + this.jittered(this.cfg.antiEntropyMs);
      await this.syncAll();
    }
  }

  private async connectAndSync(): Promise<void> {
    if (this.stateValue !== 'running' || this.connecting) {
      return;
    }
    this.connecting = true;
    try {
      if (this.bugs.has('FETCH_BEFORE_SUBSCRIBE')) {
        await this.syncAll();
        await this.deps.betweenSubscribeAndFetch?.();
        await this.connect();
      } else {
        await this.connect(); // subscribe first: pushes start arriving now
        await this.deps.betweenSubscribeAndFetch?.();
        await this.syncAll(); // then fetch whatever was committed before
      }
      if (this.connected) {
        this.outbox.markAllDue(); // unacked sends are due immediately on the new socket
        this.flushDue();
      }
    } finally {
      this.connecting = false;
    }
  }

  private async connect(): Promise<void> {
    const t = await this.deps.api.post<{ ticket: string }>('/api/ws/ticket');
    if (!t.ok) {
      if (t.status === 401) {
        this.setState('logged_out'); // the refresh failed too: log in again
      } else {
        this.scheduleReconnect(); // 429, 5xx, network: back off
      }
      return;
    }
    const gen = ++this.generation;
    const socket = this.deps.openSocket(t.data.ticket);
    this.socket = socket;
    await new Promise<void>((resolve) => {
      socket.onopen = () => {
        if (gen === this.generation) {
          this.connected = true;
          this.backoff.reset();
          this.changed();
        }
        resolve();
      };
      socket.onmessage = (ev) => this.onFrame(ev.data, gen);
      socket.onclose = (ev) => {
        resolve(); // a failed handshake closes without opening
        this.onClosed(ev.code, gen);
      };
    });
  }

  private scheduleReconnect(): void {
    if (this.stateValue === 'running' && this.reconnectAt < 0) {
      this.reconnectAt = this.now() + this.backoff.nextDelay();
    }
  }

  private closeSocket(): void {
    const s = this.socket;
    this.socket = null;
    this.connected = false;
    this.generation++; // ignore anything the old socket still delivers
    s?.close(1000);
  }

  private onClosed(code: number, gen: number): void {
    if (gen !== this.generation) {
      return;
    }
    this.connected = false;
    this.socket = null;
    const action = closeAction(code);
    if (action === 'reconnect') {
      this.scheduleReconnect();
    } else if (action === 'client_bug' && this.bugs.has('LOOP_ON_PROTOCOL_ERROR')) {
      this.scheduleReconnect();
    } else {
      this.setState(action);
    }
    this.changed();
  }

  // ───────────────────────────── sync ─────────────────────────────

  /** For each conversation the server says I'm in: fetch forward if behind. */
  private async syncAll(): Promise<void> {
    const r = await this.deps.api.get<Head[]>('/api/conversations/heads');
    if (!r.ok) {
      return;
    }
    for (const head of r.data) {
      const st = this.conv(head.conversationId);
      if (head.lastSeq > st.contiguousSeq) {
        await this.fetchForward(head.conversationId);
      }
    }
    this.changed();
  }

  private async fetchForward(conversationId: string): Promise<void> {
    let after = this.conv(conversationId).contiguousSeq;
    for (;;) {
      const r = await this.deps.api.get<Page>(
        `/api/conversations/${conversationId}/messages?after=${after}&limit=100`);
      if (!r.ok) {
        return;
      }
      let last = after;
      for (const m of r.data.messages) {
        this.merge(m);
        last = m.seq;
      }
      if (!r.data.hasMore) {
        return;
      }
      after = last;
    }
  }

  private merge(msg: Msg): void {
    const st = this.conv(msg.conversationId);
    if (st.byId.has(msg.id) && !this.bugs.has('NO_DEDUPE')) {
      return;
    }
    const key = this.bugs.has('NO_DEDUPE') ? `${msg.id}#${st.byId.size}` : msg.id;
    st.byId.set(key, msg);
    if (!st.bySeq.has(msg.seq)) {
      st.bySeq.set(msg.seq, msg);
    }
    while (st.bySeq.has(st.contiguousSeq + 1)) {
      st.contiguousSeq++;
    }
    this.changed();
  }

  // ───────────────────────────── sending and receiving ─────────────────────────────

  private flushDue(): void {
    if (!this.connected || !this.socket) {
      return;
    }
    const now = this.now();
    for (const e of this.outbox.dueForRetry(now, this.cfg.ackTimeoutMs)) {
      let toSend = e;
      if (this.bugs.has('NEW_ID_ON_RETRY') && e.attempts > 0) {
        this.outbox.remove(e.clientMsgId);
        toSend = this.outbox.enqueue(e.conversationId, e.body) ?? e;
      }
      this.socket.send(JSON.stringify({
        v: 1, type: 'send', conversationId: toSend.conversationId, clientMsgId: toSend.clientMsgId, body: toSend.body,
      }));
      this.outbox.markSent(toSend.clientMsgId, now);
    }
  }

  private onFrame(text: string, gen: number): void {
    if (gen !== this.generation) {
      return; // from an old socket
    }
    let f: Record<string, unknown>;
    try {
      f = JSON.parse(text);
    } catch {
      return;
    }
    switch (f.type) {
      case 'ack': {
        const entry = this.outbox.remove(String(f.clientMsgId));
        if (entry) {
          this.merge({
            id: String(f.messageId), conversationId: entry.conversationId, seq: Number(f.seq),
            senderId: this.deps.userId, clientMsgId: entry.clientMsgId, body: entry.body,
            createdAt: f.createdAt as string | undefined,
          });
        }
        break;
      }
      case 'message': {
        const msg = f.message as Msg;
        const before = this.conv(msg.conversationId).contiguousSeq;
        this.merge(msg);
        if (!this.bugs.has('NO_GAP_DETECTION') && msg.seq > before + 1) {
          void this.fetchForward(msg.conversationId); // never guess: fetch the gap
        }
        break;
      }
      case 'error': {
        if (f.clientMsgId == null) {
          return;
        }
        const id = String(f.clientMsgId);
        if (isTerminalError(String(f.code))) {
          const dropped = this.outbox.remove(id);
          if (dropped) {
            this.terminalErrors++;
            this.failedSends.push(dropped);
          }
        } else {
          this.outbox.markDue(id); // retryable: due again on the next tick
        }
        this.changed();
        break;
      }
      default:
        // cursor frames and anything newer: not needed for message sync
    }
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private conv(id: string): ConvState {
    let st = this.convs.get(id);
    if (!st) {
      st = { contiguousSeq: 0, byId: new Map(), bySeq: new Map() };
      this.convs.set(id, st);
    }
    return st;
  }

  private jittered(ms: number): number {
    return ms * (0.5 + this.random());
  }

  private setState(s: ClientState): void {
    if (this.stateValue !== s) {
      this.stateValue = s;
      this.changed();
    }
  }

  private changed(): void {
    this.versionValue++;
    for (const l of this.listeners) {
      l();
    }
  }
}
