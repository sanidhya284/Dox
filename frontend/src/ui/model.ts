/**
 * Pure view logic for the chat UI (ADR-0012), kept out of components so it is unit-tested directly:
 * names, list previews, message grouping and day separators, read receipts, and the phone/desktop switch.
 */

export interface Participant {
  id: string;
  username: string;
  displayName: string;
  isGuest: boolean;
  isBot: boolean;
  isDeleted: boolean;
  left: boolean;
}

export interface LastMessage {
  senderId: string;
  preview: string;
  createdAt: string;
  seq: number;
}

export interface ParticipantCursor {
  userId: string;
  lastDeliveredSeq: number;
  lastReadSeq: number;
}

export interface ConversationSummary {
  id: string;
  type: 'DIRECT' | 'GROUP' | string;
  title: string | null;
  unreadCount: number;
  lastMessageAt: string | null;
  createdAt: string;
  lastSeq: number;
  participants: Participant[];
  lastMessage: LastMessage | null;
  participantCursors?: ParticipantCursor[] | null;
}

export interface MessageLike {
  id: string;
  seq: number;
  senderId: string;
  body: string;
  createdAt?: string;
}

/** The other person in a DM (whoever isn't me), or null in a group. */
export function dmPeer(c: ConversationSummary, me: string): Participant | null {
  if (c.type !== 'DIRECT') {
    return null;
  }
  return c.participants.find((p) => p.id !== me) ?? null;
}

/** What the list and header call a conversation. */
export function conversationName(c: ConversationSummary, me: string): string {
  if (c.type === 'DIRECT') {
    return dmPeer(c, me)?.displayName ?? 'Direct message';
  }
  return c.title?.trim() || 'Untitled group';
}

/**
 * The terminal-style label: "#weekend-hike" for a group (title lower-cased, spaces to dashes), "@ben" for a DM.
 * Display only: the server never sees it.
 */
export function channelLabel(c: ConversationSummary, me: string): string {
  if (c.type === 'DIRECT') {
    const peer = dmPeer(c, me);
    return peer ? `@${peer.username}` : '@direct';
  }
  const slug = (c.title ?? '').trim().toLowerCase().replace(/\s+/g, '-');
  return `#${slug || 'untitled'}`;
}

/** A stable nick colour (0..5) per username, so one person keeps one colour everywhere. */
export function nickColor(username: string): number {
  let h = 0;
  for (const ch of username) {
    h = (h * 31 + (ch.codePointAt(0) ?? 0)) % 6;
  }
  return h;
}

/** "Ana, Ben and 3 others" for a group's subtitle; members who left are not listed. */
export function memberSummary(c: ConversationSummary, me: string): string {
  const others = c.participants.filter((p) => !p.left && p.id !== me).map((p) => p.displayName);
  if (others.length === 0) {
    return 'Only you';
  }
  if (others.length <= 3) {
    return 'You, ' + others.join(', ');
  }
  return `You, ${others.slice(0, 2).join(', ')} and ${others.length - 2} others`;
}

/** Up to two letters for the avatar. Works for any script (uses code points, not UTF-16 units). */
export function initials(name: string): string {
  const words = name.trim().split(/[\s._-]+/).filter(Boolean);
  const first = (w: string) => Array.from(w)[0] ?? '';
  const letters = words.length >= 2 ? first(words[0]) + first(words[1]) : Array.from(words[0] ?? '?').slice(0, 2).join('');
  return letters.toUpperCase() || '?';
}

/** The list preview: "You: hi" for my own last message, "Ana: hi" in a group, plain text in a DM. */
export function previewLine(c: ConversationSummary, me: string): string {
  const m = c.lastMessage;
  if (!m) {
    return 'No messages yet';
  }
  if (m.senderId === me) {
    return `You: ${m.preview}`;
  }
  if (c.type === 'GROUP') {
    const who = c.participants.find((p) => p.id === m.senderId)?.displayName ?? 'Someone';
    return `${who}: ${m.preview}`;
  }
  return m.preview;
}

/** Display name of a sender, robust to members who left or were removed. */
export function senderName(participants: Participant[], senderId: string, me: string): string {
  if (senderId === me) {
    return 'You';
  }
  return participants.find((p) => p.id === senderId)?.displayName ?? 'Unknown';
}

const DAY = 24 * 60 * 60 * 1000;

function startOfDay(t: Date): number {
  return new Date(t.getFullYear(), t.getMonth(), t.getDate()).getTime();
}

/** "Today", "Yesterday", or a date, in the viewer's local time zone. */
export function dayLabel(iso: string, now: Date = new Date()): string {
  const d = new Date(iso);
  const diff = Math.round((startOfDay(now) - startOfDay(d)) / DAY);
  if (diff === 0) {
    return 'Today';
  }
  if (diff === 1) {
    return 'Yesterday';
  }
  return d.toLocaleDateString(undefined, {
    weekday: 'short', day: 'numeric', month: 'short', ...(d.getFullYear() !== now.getFullYear() ? { year: 'numeric' } : {}),
  });
}

/** "14:05" today, "Mon" this week, else a short date: for the conversation list. */
export function listTime(iso: string | null, now: Date = new Date()): string {
  if (!iso) {
    return '';
  }
  const d = new Date(iso);
  const diff = Math.round((startOfDay(now) - startOfDay(d)) / DAY);
  if (diff === 0) {
    return clock(iso);
  }
  if (diff < 7) {
    return d.toLocaleDateString(undefined, { weekday: 'short' });
  }
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

/** 24-hour HH:MM in the viewer's time zone: always 5 characters, so the log's time column lines up. */
export function clock(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
}

/** One rendered row of the message view. */
export type Row<M extends MessageLike> =
  | { kind: 'day'; key: string; label: string }
  | { kind: 'message'; key: string; message: M; firstOfGroup: boolean; lastOfGroup: boolean };

/** Messages close together from one sender form a group; only the first shows the name. */
export const GROUP_GAP_MS = 5 * 60 * 1000;

/**
 * Day separators and sender groups, for messages already ordered by seq. A message without a timestamp
 * (shouldn't happen for stored ones) joins the current day and group.
 */
export function buildRows<M extends MessageLike>(messages: M[], now: Date = new Date()): Row<M>[] {
  const rows: Row<M>[] = [];
  let lastDay = '';
  let prev: M | null = null;
  for (const m of messages) {
    const at = m.createdAt ? new Date(m.createdAt) : null;
    const day = at ? String(startOfDay(at)) : lastDay;
    if (day && day !== lastDay) {
      rows.push({ kind: 'day', key: `day-${day}`, label: dayLabel(m.createdAt!, now) });
      lastDay = day;
      prev = null;
    }
    const continues = prev !== null && prev.senderId === m.senderId
      && (!at || !prev.createdAt || at.getTime() - new Date(prev.createdAt).getTime() < GROUP_GAP_MS);
    if (continues) {
      const last = rows[rows.length - 1];
      if (last.kind === 'message') {
        last.lastOfGroup = false;
      }
    }
    rows.push({ kind: 'message', key: m.id, message: m, firstOfGroup: !continues, lastOfGroup: true });
    prev = m;
  }
  return rows;
}

/**
 * "Read by …" under my newest message: every other current member (the bot included) whose read cursor has
 * reached it. Members who left or were removed don't count. Empty when nobody has read it yet.
 */
export function readBy(myLastSeq: number, cursors: ParticipantCursor[], participants: Participant[], me: string):
  string[] {
  if (myLastSeq <= 0) {
    return [];
  }
  const active = new Map(participants.filter((p) => !p.left && !p.isDeleted && p.id !== me).map((p) => [p.id, p]));
  return cursors
    .filter((c) => c.userId !== me && c.lastReadSeq >= myLastSeq && active.has(c.userId))
    .map((c) => active.get(c.userId)!.displayName)
    .sort((a, b) => a.localeCompare(b));
}

/** The receipt line: "Read", "Read by Ana", "Read by Ana and Ben", "Read by 4 people". */
export function receiptText(names: string[], isDirect: boolean): string | null {
  if (names.length === 0) {
    return null;
  }
  if (isDirect) {
    return 'Read';
  }
  if (names.length === 1) {
    return `Read by ${names[0]}`;
  }
  if (names.length === 2) {
    return `Read by ${names[0]} and ${names[1]}`;
  }
  return `Read by ${names.length} people`;
}

/** Applies a live cursor frame to a cursor list (never moves a cursor back). */
export function applyCursor(cursors: ParticipantCursor[], frame: ParticipantCursor): ParticipantCursor[] {
  const found = cursors.some((c) => c.userId === frame.userId);
  if (!found) {
    return [...cursors, frame];
  }
  return cursors.map((c) => c.userId !== frame.userId ? c : {
    userId: c.userId,
    lastDeliveredSeq: Math.max(c.lastDeliveredSeq, frame.lastDeliveredSeq),
    lastReadSeq: Math.max(c.lastReadSeq, frame.lastReadSeq),
  });
}

export const PHONE_MAX_WIDTH = 640;

/**
 * Which panes show. On a phone (< 640 px) the list and the conversation are separate screens; a selected
 * conversation replaces the list, and Back returns to it. On wider screens both show side by side.
 */
export function visiblePanes(width: number, selected: string | null): { list: boolean; conversation: boolean } {
  if (width >= PHONE_MAX_WIDTH) {
    return { list: true, conversation: true };
  }
  return selected ? { list: false, conversation: true } : { list: true, conversation: false };
}

/** The open conversation lives in the URL hash (#/c/<id>), so Back works and reloads keep it. */
export function conversationFromHash(hash: string): string | null {
  const m = hash.match(/^#\/c\/([0-9a-fA-F-]{36})$/);
  return m ? m[1] : null;
}

/**
 * Debounces a function: only the last call within `ms` runs. `cancel` drops a pending call (unmount).
 * Used for search-as-you-type (250 ms), so typing "alice" makes one request, not five.
 */
export function debounce<A extends unknown[]>(fn: (...args: A) => void, ms: number):
  { call: (...args: A) => void; cancel: () => void } {
  let t: ReturnType<typeof setTimeout> | null = null;
  return {
    call: (...args: A) => {
      if (t) {
        clearTimeout(t);
      }
      t = setTimeout(() => {
        t = null;
        fn(...args);
      }, ms);
    },
    cancel: () => {
      if (t) {
        clearTimeout(t);
        t = null;
      }
    },
  };
}
