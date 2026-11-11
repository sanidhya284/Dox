import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MessageList } from '../MessageList';
import type { ConversationSummary } from '../model';

const conversation: ConversationSummary = {
  id: 'c', type: 'GROUP', title: 'Team', unreadCount: 0, lastMessageAt: null, createdAt: '2026-11-11T08:00:00Z',
  lastSeq: 2,
  participants: [
    { id: 'me', username: 'me', displayName: 'Me', isGuest: false, isBot: false, isDeleted: false, left: false },
    { id: 'other', username: 'ana', displayName: 'Ana', isGuest: false, isBot: false, isDeleted: false, left: false },
  ],
  lastMessage: null,
};

describe('MessageList renders bodies as text, never HTML', () => {
  it('an HTML payload shows up as literal characters and creates no element', () => {
    const evil = '<img src=x onerror="window.pwned=1"><b>bold</b>';
    const { container } = render(
      <MessageList
        me="me"
        conversation={conversation}
        messages={[{ id: 'm1', conversationId: 'c', seq: 1, senderId: 'other', clientMsgId: null, body: evil,
          createdAt: '2026-11-11T09:00:00Z' }]}
        pending={[{ clientMsgId: 'p1', conversationId: 'c', body: '<script>x()</script>', attempts: 0, lastSentAt: 0 }]}
        failed={[]}
        cursors={[]}
        onRetry={() => {}}
      />,
    );
    expect(screen.getByText(evil)).toBeTruthy();
    expect(screen.getByText('<script>x()</script>')).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
  });
});

describe('MessageList states', () => {
  const mine = { id: 'm2', conversationId: 'c', seq: 2, senderId: 'me', clientMsgId: 'x', body: 'hello',
    createdAt: '2026-11-11T09:01:00Z' };

  it('shows the sender name in a group, "Read by" under my last message, and sending / failed states', () => {
    const retried: string[] = [];
    render(
      <MessageList
        me="me"
        conversation={conversation}
        messages={[{ id: 'm1', conversationId: 'c', seq: 1, senderId: 'other', clientMsgId: null, body: 'hi',
          createdAt: '2026-11-11T09:00:00Z' }, mine]}
        pending={[]}
        failed={[{ clientMsgId: 'f1', conversationId: 'c', body: 'oops', attempts: 1, lastSentAt: 0,
          code: 'VALIDATION_ERROR' }]}
        cursors={[{ userId: 'other', lastDeliveredSeq: 2, lastReadSeq: 2 }]}
        onRetry={(id) => retried.push(id)}
      />,
    );
    expect(screen.getByText('<ana>')).toBeTruthy();
    expect(screen.getByText('✓✓ read by Ana')).toBeTruthy();
    expect(screen.getByText(/not sent/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'retry' }));
    expect(retried).toEqual(['f1']);
  });

  it('a send refused as RECIPIENT_UNAVAILABLE says why and offers no Retry', () => {
    render(
      <MessageList me="me" conversation={conversation} messages={[]} pending={[]} cursors={[]} onRetry={() => {}}
        failed={[{ clientMsgId: 'f1', conversationId: 'c', body: 'x', attempts: 1, lastSentAt: 0,
          code: 'RECIPIENT_UNAVAILABLE' }]} />,
    );
    expect(screen.getByText(/can't receive your messages/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'retry' })).toBeNull();
  });
});
