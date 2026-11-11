import { useEffect, useRef } from 'react';
import type { FailedSend, Msg } from '../sync/client';
import type { OutboxEntry } from '../sync/outbox';
import {
  buildRows, clock, nickColor, readBy, receiptText, type ConversationSummary, type ParticipantCursor,
} from './model';

const FAILED_TEXT: Record<string, string> = {
  RECIPIENT_UNAVAILABLE: 'not sent: this person can\'t receive your messages.',
  VALIDATION_ERROR: 'not sent: the message was refused.',
  CONVERSATION_NOT_FOUND: 'not sent: this conversation no longer exists.',
};

/**
 * The conversation as an IRC-style log: [time] <nick> text. Consecutive lines from one sender show the nick once.
 * Message bodies are user input and are rendered as React TEXT only: React escapes them, so `<img onerror=...>`
 * shows up as characters, never as an element. Lint forbids dangerouslySetInnerHTML.
 */
export function MessageList({ me, conversation, messages, pending, failed, cursors, onRetry }: {
  me: string;
  conversation: ConversationSummary;
  messages: Msg[];
  pending: OutboxEntry[];
  failed: FailedSend[];
  cursors: ParticipantCursor[];
  onRetry: (clientMsgId: string) => void;
}) {
  const end = useRef<HTMLDivElement>(null);
  const rows = buildRows(messages);
  const byId = new Map(conversation.participants.map((p) => [p.id, p]));
  const myUsername = byId.get(me)?.username ?? 'you';
  const myLast = [...messages].reverse().find((m) => m.senderId === me);
  const receipt = myLast
    ? receiptText(readBy(myLast.seq, cursors, conversation.participants, me), conversation.type === 'DIRECT')
    : null;

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' });
  }, [messages.length, pending.length, failed.length]);

  const nick = (senderId: string) => {
    const p = byId.get(senderId);
    const name = senderId === me ? myUsername : p?.username ?? 'unknown';
    const cls = p?.isBot ? 'nick-bot' : `nick-${nickColor(name)}`;
    return { name, cls, title: p?.displayName ?? name };
  };

  return (
    <div className="log" role="log" aria-label="Messages" aria-live="polite">
      {messages.length === 0 && pending.length === 0 && <p className="empty">-- no messages yet. say hello. --</p>}
      <ol>
        {rows.map((r) => {
          if (r.kind === 'day') {
            return <li key={r.key} className="day">── {r.label.toLowerCase()} ──</li>;
          }
          const m = r.message;
          const mine = m.senderId === me;
          const n = nick(m.senderId);
          const isLastMine = mine && m.id === myLast?.id && pending.length === 0;
          return (
            <li key={r.key} className={`line${mine ? ' mine' : ''}${r.firstOfGroup ? ' first' : ''}`}>
              <time className="ts" dateTime={m.createdAt}>{m.createdAt ? `[${clock(m.createdAt)}]` : ''}</time>
              {r.firstOfGroup
                ? <span className={`nick ${n.cls}`} title={n.title}>&lt;{n.name}&gt;</span>
                : <span className="nick cont"><span className="sr-only">{n.name}</span><span aria-hidden="true">·</span></span>}
              <span className="text">
                {m.body}
                {mine && (isLastMine && receipt
                  ? <span className="ack read">✓✓ {receipt.replace(/^Read/, 'read')}</span>
                  : <span className="ack" title="stored">✓</span>)}
              </span>
            </li>
          );
        })}
        {pending.map((e) => (
          <li key={e.clientMsgId} className="line mine first pending">
            <span className="ts">[--:--]</span>
            <span className={`nick nick-${nickColor(myUsername)}`}>&lt;{myUsername}&gt;</span>
            <span className="text">
              {e.body}
              <span className="ack">{e.notBefore ? '… waiting (rate limit)' : '… sending'}</span>
            </span>
          </li>
        ))}
        {failed.map((e) => (
          <li key={e.clientMsgId} className="line mine first failed">
            <span className="ts err">[ ✗ ]</span>
            <span className={`nick nick-${nickColor(myUsername)}`}>&lt;{myUsername}&gt;</span>
            <span className="text">
              <span className="body">{e.body}</span>
              <span className="ack err">
                {' '}{FAILED_TEXT[e.code] ?? 'not sent.'}{' '}
                {e.code !== 'RECIPIENT_UNAVAILABLE' && (
                  <button type="button" className="link" onClick={() => onRetry(e.clientMsgId)}>retry</button>
                )}
              </span>
            </span>
          </li>
        ))}
      </ol>
      <div ref={end} />
    </div>
  );
}
