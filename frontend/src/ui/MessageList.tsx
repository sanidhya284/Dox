import type { Msg } from '../sync/client';
import type { OutboxEntry } from '../sync/outbox';

/**
 * Message bodies are user input and are rendered as React TEXT only: React escapes them, so
 * `<img onerror=...>` shows up as characters, never as an element. Lint forbids dangerouslySetInnerHTML.
 */
export function MessageList({ messages, pending, failed, me }: {
  messages: Msg[];
  pending: OutboxEntry[];
  failed: OutboxEntry[];
  me: string;
}) {
  return (
    <ol className="messages" aria-label="Messages">
      {messages.map((m) => (
        <li key={m.id} className={m.senderId === me ? 'mine' : ''}>
          <span className="sender">{m.senderId === me ? 'You' : `User ${m.senderId.slice(0, 8)}`}</span>
          <span className="body">{m.body}</span>
        </li>
      ))}
      {pending.map((e) => (
        <li key={e.clientMsgId} className="mine pending">
          <span className="sender">You · sending…</span>
          <span className="body">{e.body}</span>
        </li>
      ))}
      {failed.map((e) => (
        <li key={e.clientMsgId} className="mine failed">
          <span className="sender">You · not sent</span>
          <span className="body">{e.body}</span>
        </li>
      ))}
    </ol>
  );
}
