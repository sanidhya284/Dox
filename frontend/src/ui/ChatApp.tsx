import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import type { AuthSession } from '../api/auth';
import { ChatClient, type Api, type SocketLike } from '../sync/client';
import { ConversationsStore } from './conversations';
import { conversationFromHash, visiblePanes, type ConversationSummary, type ParticipantCursor } from './model';
import { Sidebar } from './Sidebar';
import { ConversationPane } from './ConversationPane';
import { NewConversationDialog } from './NewConversationDialog';

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

function useWidth(): number {
  const [w, setW] = useState(() => window.innerWidth);
  useEffect(() => {
    const on = () => setW(window.innerWidth);
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return w;
}

function useHashConversation(): [string | null, (id: string | null) => void] {
  const [id, setId] = useState(() => conversationFromHash(location.hash));
  useEffect(() => {
    const on = () => setId(conversationFromHash(location.hash));
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  const select = useCallback((next: string | null) => {
    if (next) {
      location.hash = `#/c/${next}`;
    } else if (location.hash) {
      history.pushState(null, '', location.pathname + location.search);
      setId(null);
    }
  }, []);
  return [id, select];
}

export function ChatApp({ auth, api, onLoggedOut, onGuestExpired }: {
  auth: AuthSession;
  api: Api;
  onLoggedOut: () => void;
  onGuestExpired: () => void;
}) {
  const me = auth.userId() ?? '';
  const storeRef = useRef<ConversationsStore | null>(null);
  const clientRef = useRef<ChatClient | null>(null);
  if (!storeRef.current) {
    storeRef.current = new ConversationsStore(api, (id) => clientRef.current?.forget(id));
  }
  const store = storeRef.current;
  const [client, setClient] = useState<ChatClient | null>(null);
  const [selected, select] = useHashConversation();
  const [dialog, setDialog] = useState<'dm' | 'group' | null>(null);
  const width = useWidth();

  useEffect(() => {
    const c = new ChatClient({
      api,
      openSocket: browserSocket,
      userId: me,
      onOtherFrame: (f) => {
        if (f.type === 'cursor') {
          store.applyCursorFrame(f as unknown as { conversationId: string } & ParticipantCursor);
        }
      },
    }).start();
    clientRef.current = c;
    setClient(c);
    return () => c.stop();
  }, [api, me, store]);

  const subscribeClient = useCallback((l: () => void) => (client ? client.subscribe(l) : () => {}), [client]);
  useSyncExternalStore(subscribeClient, () => client?.version ?? 0);
  const storeVersion = useRef(0);
  const subscribeStore = useCallback((l: () => void) => store.subscribe(() => {
    storeVersion.current++;
    l();
  }), [store]);
  useSyncExternalStore(subscribeStore, () => storeVersion.current);

  useEffect(() => {
    void store.load();
  }, [store]);

  // Who am I: login and refresh return only a token, and a new user has no conversation to read it from.
  const [profile, setProfile] = useState<{ username: string; isGuest: boolean } | null>(null);
  useEffect(() => {
    let cancelled = false;
    void api.get<{ username: string; isGuest: boolean }>('/api/users/me').then((r) => {
      if (!cancelled && r.ok) {
        setProfile({ username: r.data.username, isGuest: r.data.isGuest });
      }
    });
    return () => {
      cancelled = true;
    };
  }, [api]);

  // Ends of the session decided by the sync client.
  useEffect(() => {
    if (client?.state === 'logged_out') {
      onLoggedOut();
    } else if (client?.state === 'guest_expired') {
      onGuestExpired();
    }
  }, [client?.state, onLoggedOut, onGuestExpired]);

  // New messages anywhere: refresh the list (previews, order, unread), at most twice a second.
  const syncSignature = client ? client.conversationIds().map((id) => `${id}:${client.contiguousSeq(id)}`).join(',') : '';
  useEffect(() => {
    if (!client) {
      return;
    }
    void store.noticeIds(client.conversationIds());
    const t = setTimeout(() => void store.load(), 500);
    return () => clearTimeout(t);
  }, [client, store, syncSignature]);

  const list = store.list;
  const conversation: ConversationSummary | null = useMemo(
    () => list.find((c) => c.id === selected) ?? null, [list, selected]);

  // Opening a conversation loads its details (cursors). A 404 drops it and goes back to the list.
  useEffect(() => {
    if (!selected) {
      return;
    }
    let cancelled = false;
    void store.open(selected).then((c) => {
      if (!cancelled && c === null && store.isGone(selected)) {
        select(null);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [selected, store, select]);

  const isGuest = profile?.isGuest ?? false;
  const myUsername = profile?.username ?? '';
  const panes = visiblePanes(width, selected);

  async function logout() {
    client?.stop();
    await auth.logout();
    onLoggedOut();
  }

  function opened(c: ConversationSummary) {
    store.upsert(c);
    setDialog(null);
    select(c.id);
  }

  return (
    <div className="app">
      {panes.list && (
        <Sidebar
          me={me}
          myUsername={myUsername}
          isGuest={isGuest}
          conversations={store.list}
          loaded={store.loaded}
          selected={selected}
          onSelect={select}
          onNewMessage={() => setDialog('dm')}
          onNewGroup={() => setDialog('group')}
          onLogout={() => void logout()}
        />
      )}
      {panes.conversation && (
        <ConversationPane
          api={api}
          me={me}
          myUsername={myUsername}
          client={client}
          conversation={conversation}
          cursors={selected ? store.cursors.get(selected) ?? [] : []}
          showBack={!panes.list}
          onBack={() => select(null)}
          onRead={(id) => store.markRead(id)}
          isGuest={isGuest}
          onSignUp={() => void logout()}
        />
      )}
      {dialog && (
        <NewConversationDialog
          api={api}
          mode={dialog}
          isGuest={isGuest}
          onClose={() => setDialog(null)}
          onOpened={opened}
        />
      )}
    </div>
  );
}
