import { useCallback, useEffect, useRef, useState, useSyncExternalStore, type FormEvent } from 'react';
import type { AuthSession } from '../api/auth';
import { ChatClient, type Api, type SocketLike } from '../sync/client';
import { MessageList } from './MessageList';

interface Conversation {
  id: string;
  type: string;
  title: string | null;
  unreadCount: number;
}

/** A browser WebSocket behind the small interface the sync client uses (tests use a fake). */
function browserSocket(ticket: string): SocketLike {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${scheme}://${location.host}/ws?ticket=${encodeURIComponent(ticket)}`);
  const s: SocketLike = {
    onopen: null,
    onmessage: null,
    onclose: null,
    send: (d) => ws.send(d),
    close: (code, reason) => ws.close(code, reason),
  };
  ws.onopen = () => s.onopen?.();
  ws.onmessage = (ev) => s.onmessage?.({ data: String(ev.data) });
  ws.onclose = (ev) => s.onclose?.({ code: ev.code });
  return s;
}

const STATE_TEXT: Record<string, string> = {
  too_many_sockets: 'Too many open tabs or devices for this account. Close one, then reload this page.',
  client_bug: 'The app hit a protocol error and stopped syncing. Reload the page.',
  server_bug: 'The server rejected the connection. Reload the page.',
};

export function ChatView({ auth, api, onLoggedOut }: { auth: AuthSession; api: Api; onLoggedOut: () => void }) {
  const me = auth.userId() ?? '';
  const [client, setClient] = useState<ChatClient | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [draft, setDraft] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const lastCursor = useRef(new Map<string, number>());

  useEffect(() => {
    const c = new ChatClient({ api, openSocket: browserSocket, userId: me }).start();
    setClient(c);
    return () => c.stop();
  }, [api, me]);

  const subscribe = useCallback((l: () => void) => (client ? client.subscribe(l) : () => {}), [client]);
  useSyncExternalStore(subscribe, () => client?.version ?? 0);

  const loadConversations = useCallback(async () => {
    const r = await api.get<Conversation[]>('/api/conversations');
    if (r.ok) {
      setConversations(r.data);
      setSelected((s) => s ?? r.data[0]?.id ?? null);
    }
  }, [api]);

  useEffect(() => {
    void loadConversations();
  }, [loadConversations]);

  // A conversation the client learned about from /heads but the list doesn't show yet: reload the list.
  const known = new Set(conversations.map((c) => c.id));
  const unknown = client?.conversationIds().some((id) => !known.has(id)) ?? false;
  useEffect(() => {
    if (unknown) {
      void loadConversations();
    }
  }, [unknown, loadConversations]);

  useEffect(() => {
    if (client?.state === 'logged_out') {
      onLoggedOut();
    }
  }, [client?.state, onLoggedOut]);

  // Read cursor: tell the server how far this user has read the open conversation (debounced).
  const readSeq = selected && client ? client.contiguousSeq(selected) : 0;
  useEffect(() => {
    if (!selected || readSeq === 0 || (lastCursor.current.get(selected) ?? 0) >= readSeq) {
      return;
    }
    const t = setTimeout(() => {
      lastCursor.current.set(selected, readSeq);
      void api.post(`/api/conversations/${selected}/cursor`, { kind: 'read', seq: readSeq });
    }, 1000);
    return () => clearTimeout(t);
  }, [api, selected, readSeq]);

  function send(e: FormEvent) {
    e.preventDefault();
    const body = draft.trim();
    if (!client || !selected || !body) {
      return;
    }
    if (client.send(selected, body) === null) {
      setNotice('Too many unsent messages. Wait for the connection to come back.');
      return;
    }
    setNotice(null);
    setDraft('');
  }

  async function logout() {
    client?.stop();
    await auth.logout();
    onLoggedOut();
  }

  const stateText = client ? STATE_TEXT[client.state] : undefined;
  return (
    <div className="chat">
      <aside>
        <header>
          <strong>Conversations</strong>
          <button type="button" className="link" onClick={() => void logout()}>Log out</button>
        </header>
        <ul aria-label="Conversations">
          {conversations.map((c) => (
            <li key={c.id}>
              <button type="button" className={c.id === selected ? 'active' : ''} onClick={() => setSelected(c.id)}>
                {c.title ?? (c.type === 'DIRECT' ? 'Direct message' : 'Untitled group')}
                {c.unreadCount > 0 && c.id !== selected && <span className="badge">{c.unreadCount}</span>}
              </button>
            </li>
          ))}
        </ul>
        <p className="note">Demo data is public. Don't post anything personal.</p>
      </aside>
      <section>
        <div className={`status ${client?.isConnected ? 'ok' : 'warn'}`} role="status">
          {stateText ?? (client?.isConnected ? 'Connected' : 'Reconnecting…')}
        </div>
        {selected && client ? (
          <MessageList
            messages={client.messages(selected)}
            pending={client.pending(selected)}
            failed={client.failedSends.filter((f) => f.conversationId === selected)}
            me={me}
          />
        ) : (
          <p className="center">No conversation selected.</p>
        )}
        {notice && <p role="alert" className="error">{notice}</p>}
        <form className="composer" onSubmit={send}>
          <input aria-label="Message" value={draft} onChange={(e) => setDraft(e.target.value)} maxLength={4000}
            placeholder="Write a message" disabled={!selected} />
          <button type="submit" disabled={!selected || !draft.trim()}>Send</button>
        </form>
      </section>
    </div>
  );
}
