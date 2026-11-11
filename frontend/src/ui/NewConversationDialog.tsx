import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import type { Api } from '../sync/client';
import { debounce, nickColor, type ConversationSummary } from './model';

interface UserResult {
  id: string;
  username: string;
  displayName: string;
  isGuest: boolean;
}

const ERRORS: Record<string, string> = {
  RATE_LIMITED: 'you\'ve started a lot of conversations this hour. try again later.',
  USER_NOT_FOUND: 'that person isn\'t available.',
  GUEST_NOT_ALLOWED: 'guests can\'t create groups. sign up to start your own.',
  VALIDATION_ERROR: 'check the name and members.',
};

/** "New message" (DM by username search) and "New group" (title and a member picker), ADR-0012. */
export function NewConversationDialog({ api, mode, isGuest, onClose, onOpened }: {
  api: Api;
  mode: 'dm' | 'group';
  isGuest: boolean;
  onClose: () => void;
  onOpened: (c: ConversationSummary) => void;
}) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<UserResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [picked, setPicked] = useState<UserResult[]>([]);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const dialog = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const latest = useRef('');

  const search = useMemo(() => debounce(async (q: string) => {
    latest.current = q;
    const r = await api.get<UserResult[]>(`/api/users/search?q=${encodeURIComponent(q)}`);
    if (latest.current !== q) {
      return; // a newer query is on its way
    }
    setSearching(false);
    if (r.ok) {
      setResults(r.data);
    } else {
      setResults([]);
      setError(r.code === 'RATE_LIMITED' ? 'searching too fast. wait a moment.' : null);
    }
  }, 250), [api]);

  useEffect(() => () => search.cancel(), [search]);
  useEffect(() => input.current?.focus(), [mode]);
  useEffect(() => {
    const onKey = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  function onQuery(q: string) {
    setQuery(q);
    setError(null);
    if (q.trim().length < 2) {
      search.cancel();
      latest.current = '';
      setResults([]);
      setSearching(false);
      return;
    }
    setSearching(true);
    search.call(q.trim());
  }

  async function openDm(u: UserResult) {
    setBusy(true);
    const r = await api.post<ConversationSummary>('/api/conversations/direct', { otherUserId: u.id });
    setBusy(false);
    if (r.ok) {
      onOpened(r.data);
    } else {
      setError(ERRORS[r.code ?? ''] ?? 'that didn\'t work. try again.');
    }
  }

  async function createGroup(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    const r = await api.post<ConversationSummary>('/api/conversations/group',
      { title: title.trim(), memberIds: picked.map((p) => p.id) });
    setBusy(false);
    if (r.ok) {
      onOpened(r.data);
    } else {
      setError(ERRORS[r.code ?? ''] ?? 'that didn\'t work. try again.');
    }
  }

  const titleId = `new-${mode}-title`;
  const shown = results.filter((u) => !picked.some((p) => p.id === u.id));

  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={dialog}>
        <header className="window-bar">
          <h2 id={titleId} className="accent">{mode === 'dm' ? '/dm' : '/group'}</h2>
          <button type="button" className="cmd cmd-quiet" onClick={onClose} aria-label="Close">[esc]</button>
        </header>

        <div className="dialog-body">
        {mode === 'group' && isGuest ? (
          <p className="notice">guests can&apos;t create groups. sign up for a free account to start your own.</p>
        ) : (
          <form className="stack" onSubmit={(e) => (mode === 'group' ? void createGroup(e) : e.preventDefault())}>
            {mode === 'group' && (
              <label className="field">
                <span>name:</span>
                <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={100} required />
              </label>
            )}
            {mode === 'group' && picked.length > 0 && (
              <ul className="chips" aria-label="Members">
                {picked.map((u) => (
                  <li key={u.id} className="chip">
                    @{u.username}
                    <button type="button" aria-label={`Remove ${u.displayName}`}
                      onClick={() => setPicked(picked.filter((p) => p.id !== u.id))}>✕</button>
                  </li>
                ))}
              </ul>
            )}
            <label className="field">
              <span>{mode === 'dm' ? 'to:' : 'add people:'}</span>
              <input ref={input} type="search" value={query} onChange={(e) => onQuery(e.target.value)}
                placeholder="search username…" autoComplete="off" aria-describedby={`${titleId}-hint`} />
            </label>
            <p id={`${titleId}-hint`} className="hint">type at least 2 letters of a username.</p>
            <ul className="results" aria-label="People" aria-busy={searching}>
              {shown.map((u) => (
                <li key={u.id}>
                  <button type="button" className="result" disabled={busy}
                    onClick={() => (mode === 'dm' ? void openDm(u) : (setPicked([...picked, u]), onQuery('')))}>
                    <span className={`nick-${nickColor(u.username)}`}>@{u.username}</span>
                    <span className="dim">{u.displayName}</span>
                    {u.isGuest && <span className="tag">guest</span>}
                  </button>
                </li>
              ))}
            </ul>
            {query.trim().length >= 2 && !searching && shown.length === 0 && <p className="empty">-- no one found --</p>}
            {error && <p role="alert" className="error">error: {error}</p>}
            {mode === 'group' && (
              <button type="submit" className="cmd cmd-primary" disabled={busy || picked.length === 0 || !title.trim()}>
                [ create group{picked.length > 0 ? ` with ${picked.length}` : ''} ]
              </button>
            )}
          </form>
        )}
        </div>
      </div>
    </div>
  );
}
