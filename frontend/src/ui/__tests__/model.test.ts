import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  applyCursor, buildRows, channelLabel, conversationFromHash, conversationName, debounce, initials, memberSummary,
  nickColor,
  previewLine, readBy, receiptText, visiblePanes, type ConversationSummary, type Participant,
} from '../model';

const ME = 'me';
const p = (id: string, displayName: string, extra: Partial<Participant> = {}): Participant => ({
  id, username: displayName.toLowerCase(), displayName, isGuest: false, isBot: false, isDeleted: false, left: false,
  ...extra,
});
const conv = (extra: Partial<ConversationSummary>): ConversationSummary => ({
  id: 'c1', type: 'GROUP', title: 'Team', unreadCount: 0, lastMessageAt: null, createdAt: '2026-11-11T08:00:00Z',
  lastSeq: 0, participants: [p(ME, 'Me'), p('a', 'Ana'), p('b', 'Ben')], lastMessage: null, ...extra,
});

describe('names and previews', () => {
  it('a DM is named after the other person; a group by its title', () => {
    expect(conversationName(conv({ type: 'DIRECT', title: null, participants: [p(ME, 'Me'), p('a', 'Ana')] }), ME))
      .toBe('Ana');
    expect(conversationName(conv({}), ME)).toBe('Team');
    expect(conversationName(conv({ title: '  ' }), ME)).toBe('Untitled group');
  });

  it('previews say who wrote the last message', () => {
    const last = { senderId: 'a', preview: 'hi', createdAt: '2026-11-11T08:00:00Z', seq: 1 };
    expect(previewLine(conv({ lastMessage: last }), ME)).toBe('Ana: hi');
    expect(previewLine(conv({ lastMessage: { ...last, senderId: ME } }), ME)).toBe('You: hi');
    expect(previewLine(conv({ type: 'DIRECT', lastMessage: last }), ME)).toBe('hi');
    expect(previewLine(conv({}), ME)).toBe('No messages yet');
  });

  it('terminal labels: #slug for a group, @username for a DM', () => {
    expect(channelLabel(conv({ title: 'Weekend  Hike' }), ME)).toBe('#weekend-hike');
    expect(channelLabel(conv({ title: null }), ME)).toBe('#untitled');
    expect(channelLabel(conv({ type: 'DIRECT', participants: [p(ME, 'Me'), p('a', 'Ana')] }), ME)).toBe('@ana');
  });

  it('a nick keeps one of six colours', () => {
    expect(nickColor('ben')).toBe(nickColor('ben'));
    expect(new Set(['ana', 'ben', 'cyrus', 'dana', 'eli', 'fay', 'gus'].map(nickColor)).size).toBeGreaterThan(2);
    expect(['ana', 'ben', '😀'].every((u) => nickColor(u) >= 0 && nickColor(u) < 6)).toBe(true);
  });

  it('member summary leaves out members who left', () => {
    expect(memberSummary(conv({ participants: [p(ME, 'Me'), p('a', 'Ana'), p('b', 'Ben', { left: true })] }), ME))
      .toBe('You, Ana');
  });

  it('initials use code points, so an emoji or accented name never splits', () => {
    expect(initials('Ana Lopez')).toBe('AL');
    expect(initials('guest-1a2b')).toBe('G1');
    expect(initials('😀 smile')).toBe('😀S');
    expect(initials('')).toBe('?');
  });
});

describe('message rows: day separators and sender groups', () => {
  const now = new Date('2026-11-11T12:00:00');
  const m = (id: string, senderId: string, at: string) => ({ id, seq: Number(id), senderId, body: id, createdAt: at });

  it('groups consecutive messages of one sender within 5 minutes, and separates days', () => {
    const rows = buildRows([
      m('1', 'a', '2026-11-10T09:00:00'),
      m('2', 'a', '2026-11-11T09:00:00'),
      m('3', 'a', '2026-11-11T09:03:00'),
      m('4', 'b', '2026-11-11T09:04:00'),
      m('5', 'b', '2026-11-11T09:20:00'),
    ], now);
    expect(rows.map((r) => (r.kind === 'day' ? `[${r.label}]`
      : `${r.message.id}${r.firstOfGroup ? '^' : ''}${r.lastOfGroup ? '$' : ''}`)))
      .toEqual(['[Yesterday]', '1^$', '[Today]', '2^', '3$', '4^$', '5^$']);
  });
});

describe('read receipts', () => {
  const participants = [p(ME, 'Me'), p('a', 'Ana'), p('b', 'Ben'), p('c', 'Cy', { left: true })];

  it('lists the current members whose read cursor reached my newest message', () => {
    const cursors = [
      { userId: ME, lastDeliveredSeq: 9, lastReadSeq: 9 },
      { userId: 'a', lastDeliveredSeq: 7, lastReadSeq: 7 },
      { userId: 'b', lastDeliveredSeq: 7, lastReadSeq: 6 },
      { userId: 'c', lastDeliveredSeq: 9, lastReadSeq: 9 },
    ];
    expect(readBy(7, cursors, participants, ME)).toEqual(['Ana']);
    expect(readBy(6, cursors, participants, ME)).toEqual(['Ana', 'Ben']);
    expect(readBy(8, cursors, participants, ME)).toEqual([]);
  });

  it('receipt text', () => {
    expect(receiptText([], false)).toBeNull();
    expect(receiptText(['Ana'], true)).toBe('Read');
    expect(receiptText(['Ana', 'Ben'], false)).toBe('Read by Ana and Ben');
    expect(receiptText(['A', 'B', 'C'], false)).toBe('Read by 3 people');
  });

  it('a live cursor frame moves a cursor forward, never back', () => {
    const start = [{ userId: 'a', lastDeliveredSeq: 5, lastReadSeq: 5 }];
    expect(applyCursor(start, { userId: 'a', lastDeliveredSeq: 7, lastReadSeq: 6 }))
      .toEqual([{ userId: 'a', lastDeliveredSeq: 7, lastReadSeq: 6 }]);
    expect(applyCursor(start, { userId: 'a', lastDeliveredSeq: 3, lastReadSeq: 2 })).toEqual(start);
    expect(applyCursor(start, { userId: 'b', lastDeliveredSeq: 1, lastReadSeq: 1 })).toHaveLength(2);
  });
});

describe('phone and desktop layout', () => {
  it('on a phone the list and the conversation are separate screens; on desktop both show', () => {
    expect(visiblePanes(390, null)).toEqual({ list: true, conversation: false });
    expect(visiblePanes(390, 'c1')).toEqual({ list: false, conversation: true });
    expect(visiblePanes(1280, 'c1')).toEqual({ list: true, conversation: true });
    expect(visiblePanes(640, null)).toEqual({ list: true, conversation: true });
  });

  it('the open conversation comes from the hash, only for a well-formed id', () => {
    const id = '123e4567-e89b-12d3-a456-426614174000';
    expect(conversationFromHash(`#/c/${id}`)).toBe(id);
    expect(conversationFromHash('#/c/<script>')).toBeNull();
    expect(conversationFromHash('')).toBeNull();
  });
});

describe('search debounce', () => {
  afterEach(() => vi.useRealTimers());

  it('typing quickly makes one call, with the last value, 250 ms after the last keystroke', () => {
    vi.useFakeTimers();
    const calls: string[] = [];
    const d = debounce((q: string) => calls.push(q), 250);
    for (const q of ['a', 'al', 'ali', 'alic', 'alice']) {
      d.call(q);
      vi.advanceTimersByTime(100);
    }
    expect(calls).toEqual([]);
    vi.advanceTimersByTime(150);
    expect(calls).toEqual(['alice']);
  });

  it('cancel drops the pending call (the dialog closed)', () => {
    vi.useFakeTimers();
    const calls: string[] = [];
    const d = debounce((q: string) => calls.push(q), 250);
    d.call('bo');
    d.cancel();
    vi.advanceTimersByTime(1000);
    expect(calls).toEqual([]);
  });
});
