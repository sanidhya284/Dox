import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MessageList } from '../MessageList';

describe('MessageList renders bodies as text, never HTML', () => {
  it('an HTML payload shows up as literal characters and creates no element', () => {
    const evil = '<img src=x onerror="window.pwned=1"><b>bold</b>';
    const { container } = render(
      <MessageList
        me="me"
        messages={[{ id: 'm1', conversationId: 'c', seq: 1, senderId: 'other', clientMsgId: null, body: evil }]}
        pending={[{ clientMsgId: 'p1', conversationId: 'c', body: '<script>x()</script>', attempts: 0, lastSentAt: 0 }]}
        failed={[]}
      />,
    );
    expect(screen.getByText(evil)).toBeTruthy();
    expect(screen.getByText('<script>x()</script>')).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
  });
});
