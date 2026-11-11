import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import type { Api, ChatClient } from '../sync/client';
import { MessageList } from './MessageList';
import { channelLabel, dmPeer, memberSummary, type ConversationSummary, type ParticipantCursor } from './model';

const STATE_TEXT: Record<string, string> = {
  too_many_sockets: 'too many open tabs or devices for this account. close one, then reload this page.',
  client_bug: 'the app hit a protocol error and stopped syncing. reload the page.',
  server_bug: 'the server refused the connection. reload the page.',
};

/** ● connected, ◌ connecting / reconnecting (attempt n). Stop states are a banner instead. */
function Connection({ client }: { client: ChatClient }) {
  if (client.isConnected) {
    return <span className="conn ok" role="status">● connected</span>;
  }
  return (
    <span className="conn wait" role="status">
      {client.reconnects === 0 ? '◌ connecting…' : `◌ reconnecting (attempt ${client.reconnects})`}
    </span>
  );
}

export function ConversationPane({
  api, me, myUsername, client, conversation, cursors, showBack, onBack, onRead, isGuest, onSignUp,
}: {
  api: Api;
  me: string;
  myUsername: string;
  client: ChatClient | null;
  conversation: ConversationSummary | null;
  cursors: ParticipantCursor[];
  showBack: boolean;
  onBack: () => void;
  onRead: (id: string) => void;
  isGuest: boolean;
  onSignUp: () => void;
}) {
  const [draft, setDraft] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<Set<string>>(new Set());
  const lastCursor = useRef(new Map<string, number>());
  const id = conversation?.id ?? null;
  const peer = conversation ? dmPeer(conversation, me) : null;

  useEffect(() => {
    setNotice(null);
    setDraft('');
  }, [id]);

  useEffect(() => {
    if (!peer || peer.isBot) {
      return;
    }
    let cancelled = false;
    void api.get<{ id: string }[]>('/api/users/blocked').then((r) => {
      if (!cancelled && r.ok) {
        setBlocked(new Set(r.data.map((u) => u.id)));
      }
    });
    return () => {
      cancelled = true;
    };
  }, [api, peer]);

  // Read cursor: tell the server how far I've read the open conversation (debounced, only when visible).
  const readSeq = id && client ? client.contiguousSeq(id) : 0;
  useEffect(() => {
    if (!id || readSeq === 0 || (lastCursor.current.get(id) ?? 0) >= readSeq) {
      return;
    }
    const t = setTimeout(() => {
      if (document.visibilityState !== 'visible') {
        return;
      }
      lastCursor.current.set(id, readSeq);
      onRead(id);
      void api.post(`/api/conversations/${id}/cursor`, { kind: 'read', seq: readSeq });
    }, 800);
    return () => clearTimeout(t);
  }, [api, id, readSeq, onRead]);

  if (!conversation || !client) {
    return (
      <section className="pane pane-empty">
        <p>-- pick a channel, or start one with [+ dm] --</p>
      </section>
    );
  }

  const label = channelLabel(conversation, me);
  const failed = client.failedSends.filter((f) => f.conversationId === conversation.id);
  const iBlocked = peer ? blocked.has(peer.id) : false;
  const unavailable = peer?.isDeleted || iBlocked || failed.some((f) => f.code === 'RECIPIENT_UNAVAILABLE');
  const stop = STATE_TEXT[client.state];

  function send(e?: FormEvent) {
    e?.preventDefault();
    const body = draft.trim();
    if (!client || !conversation || !body) {
      return;
    }
    if (client.send(conversation.id, body) === null) {
      setNotice('too many unsent messages. wait for the connection to come back.');
      return;
    }
    setNotice(null);
    setDraft('');
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      send();
    }
  }

  async function toggleBlock() {
    if (!peer || !api.del) {
      return;
    }
    const r = iBlocked ? await api.del(`/api/users/${peer.id}/block`) : await api.post(`/api/users/${peer.id}/block`);
    if (r.ok) {
      const next = new Set(blocked);
      if (iBlocked) {
        next.delete(peer.id);
      } else {
        next.add(peer.id);
      }
      setBlocked(next);
    } else {
      setNotice('that didn\'t work. try again.');
    }
  }

  let subtitle: string;
  if (peer) {
    subtitle = peer.isBot ? 'demo bot' : peer.isDeleted ? 'deleted account'
      : `${peer.displayName}${peer.isGuest ? ' · guest' : ''}`;
  } else {
    subtitle = memberSummary(conversation, me);
  }

  return (
    <section className="pane" aria-label={`Conversation ${label}`}>
      <header className="topbar">
        {showBack && (
          <button type="button" className="cmd cmd-quiet" onClick={onBack} aria-label="Back to conversations">‹ back</button>
        )}
        <div className="topbar-title">
          <h1><span className="dim">dox ▸ </span>{label}</h1>
          <p>{subtitle}</p>
        </div>
        <Connection client={client} />
        {peer && !peer.isBot && !peer.isDeleted && (
          <button type="button" className="cmd cmd-quiet" onClick={() => void toggleBlock()}>
            {iBlocked ? '/unblock' : '/block'}
          </button>
        )}
      </header>
      {stop && <div className="banner banner-error" role="alert">{stop}</div>}
      <MessageList
        me={me}
        conversation={conversation}
        messages={client.messages(conversation.id)}
        pending={client.pending(conversation.id)}
        failed={failed}
        cursors={cursors}
        onRetry={(clientMsgId) => client.retryFailed(clientMsgId)}
      />
      {notice && <p role="alert" className="error pad">error: {notice}</p>}
      {isGuest && conversation.type === 'GROUP' && conversation.participants.some((p) => p.isBot) && (
        <p className="hint pad">
          guests can&apos;t create groups. <button type="button" className="link" onClick={onSignUp}>sign up</button> to
          start your own.
        </p>
      )}
      {unavailable ? (
        <p className="composer-off" role="status">
          {iBlocked ? '-- you blocked this person. /unblock to send messages. --'
            : peer?.isDeleted ? '-- this account was deleted. you can read the history, but not reply. --'
              : '-- this person can\'t receive your messages. --'}
        </p>
      ) : (
        <form className="composer" onSubmit={send}>
          <label className="prompt" htmlFor="composer-input">{myUsername || 'you'}@dox:~$</label>
          <textarea id="composer-input" rows={1} value={draft} maxLength={4000} placeholder={`message ${label}`}
            aria-label={`Message ${label}`}
            onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey} />
          <button type="submit" className="cmd" disabled={!draft.trim()}>[send ⏎]</button>
        </form>
      )}
    </section>
  );
}
