import type { Api } from '../sync/client';
import type { ConversationSummary, ParticipantCursor } from './model';
import { applyCursor } from './model';

/**
 * The conversation list and the open conversation's details (ADR-0012). The server's list is the truth;
 * this store adds the two rules that keep the UI from looping:
 * - a conversation that answers 404 (deleted by account removal, or never mine) is dropped and remembered as
 *   gone: it's never requested again and never comes back from a stale reload;
 * - an id the sync client knows but the list doesn't (a DM someone just started with me) reloads the list
 *   AT MOST ONCE per id, so an id the server will never list can't cause a reload loop.
 */
export class ConversationsStore {
  list: ConversationSummary[] = [];
  /** participantCursors of conversations whose details were loaded, updated live by cursor frames. */
  readonly cursors = new Map<string, ParticipantCursor[]>();
  private readonly gone = new Set<string>();
  private readonly reloadedFor = new Set<string>();
  private readonly listeners = new Set<() => void>();
  loaded = false;

  constructor(private readonly api: Api, private readonly onGone: (id: string) => void = () => {}) {}

  subscribe(l: () => void): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }

  isGone(id: string): boolean {
    return this.gone.has(id);
  }

  async load(): Promise<void> {
    const r = await this.api.get<ConversationSummary[]>('/api/conversations');
    if (r.ok) {
      this.list = r.data.filter((c) => !this.gone.has(c.id));
      this.loaded = true;
      this.emit();
    }
  }

  /** Reloads the list once for ids the sync client knows but the list doesn't (never twice for one id). */
  async noticeIds(ids: string[]): Promise<void> {
    const known = new Set(this.list.map((c) => c.id));
    const fresh = ids.filter((id) => !known.has(id) && !this.gone.has(id) && !this.reloadedFor.has(id));
    if (fresh.length === 0) {
      return;
    }
    fresh.forEach((id) => this.reloadedFor.add(id));
    await this.load();
  }

  /** Loads one conversation's details (cursors). A 404 drops it for good; returns null then. */
  async open(id: string): Promise<ConversationSummary | null> {
    if (this.gone.has(id)) {
      return null;
    }
    const r = await this.api.get<ConversationSummary>(`/api/conversations/${id}`);
    if (!r.ok) {
      if (r.status === 404) {
        this.drop(id);
      }
      return null;
    }
    this.cursors.set(id, r.data.participantCursors ?? []);
    this.upsert(r.data);
    return r.data;
  }

  /** Adds or replaces a conversation (one I just created, or fresh details). */
  upsert(c: ConversationSummary): void {
    if (this.gone.has(c.id)) {
      return;
    }
    const i = this.list.findIndex((x) => x.id === c.id);
    const merged = i >= 0 ? { ...this.list[i], ...c, participantCursors: undefined } : c;
    this.list = i >= 0 ? this.list.map((x, j) => (j === i ? merged : x)) : [merged, ...this.list];
    this.emit();
  }

  /** The conversation is gone for me: remove it from the list, remember that, tell the sync client. */
  drop(id: string): void {
    this.gone.add(id);
    this.list = this.list.filter((c) => c.id !== id);
    this.cursors.delete(id);
    this.onGone(id);
    this.emit();
  }

  applyCursorFrame(frame: { conversationId: string } & ParticipantCursor): void {
    const current = this.cursors.get(frame.conversationId);
    if (current) {
      this.cursors.set(frame.conversationId, applyCursor(current, frame));
      this.emit();
    }
  }

  /** Locally clears the unread badge of the open conversation (the server learns via the read cursor). */
  markRead(id: string): void {
    if (this.list.some((c) => c.id === id && c.unreadCount > 0)) {
      this.list = this.list.map((c) => (c.id === id ? { ...c, unreadCount: 0 } : c));
      this.emit();
    }
  }

  private emit(): void {
    for (const l of this.listeners) {
      l();
    }
  }
}
