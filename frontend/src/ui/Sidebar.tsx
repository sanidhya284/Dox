import { useState } from 'react';
import { channelLabel, conversationName, listTime, previewLine, type ConversationSummary } from './model';

/** The channel list: #groups and @dms, unread counts, the last line of each. */
export function Sidebar({
  me, myUsername, isGuest, conversations, loaded, selected, onSelect, onNewMessage, onNewGroup, onLogout,
}: {
  me: string;
  myUsername: string;
  isGuest: boolean;
  conversations: ConversationSummary[];
  loaded: boolean;
  selected: string | null;
  onSelect: (id: string) => void;
  onNewMessage: () => void;
  onNewGroup: () => void;
  onLogout: () => void;
}) {
  const [filter, setFilter] = useState('');
  const q = filter.trim().toLowerCase();
  const shown = q
    ? conversations.filter((c) => `${channelLabel(c, me)} ${conversationName(c, me)}`.toLowerCase().includes(q))
    : conversations;

  return (
    <aside className="sidebar" aria-label="Conversations">
      <header className="side-head">
        <span className="logo">dox</span>
        <button type="button" className="cmd cmd-quiet" onClick={onLogout}>{isGuest ? '/quit demo' : '/logout'}</button>
      </header>
      {myUsername && (
        <p className="whoami">{myUsername}@dox{isGuest && <span className="tag">guest</span>}</p>
      )}
      {isGuest && <p className="guest-note">demo account: deleted with its chats after 24 hours.</p>}
      <div className="cmds">
        <button type="button" className="cmd" onClick={onNewMessage}>[+ dm]</button>
        <button type="button" className="cmd" onClick={onNewGroup}>[+ group]</button>
      </div>
      <label className="filter">
        <span className="sr-only">Filter conversations</span>
        <input type="search" placeholder="/filter" value={filter} onChange={(e) => setFilter(e.target.value)} />
      </label>
      <ul className="chan-list">
        {shown.map((c) => {
          const unread = c.unreadCount > 0 && c.id !== selected;
          return (
            <li key={c.id}>
              <button type="button" className={`chan${c.id === selected ? ' active' : ''}${unread ? ' unread' : ''}`}
                aria-current={c.id === selected ? 'true' : undefined} onClick={() => onSelect(c.id)}
                title={conversationName(c, me)}>
                <span className="chan-name">{channelLabel(c, me)}</span>
                <time className="chan-time">{listTime(c.lastMessage?.createdAt ?? c.lastMessageAt)}</time>
                <span className="chan-preview">
                  <span>{previewLine(c, me)}</span>
                  {unread && (
                    <span className="unread-count" aria-label={`${c.unreadCount} unread`}>
                      ({c.unreadCount > 99 ? '99+' : c.unreadCount})
                    </span>
                  )}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
      {loaded && conversations.length === 0 && <p className="empty">no conversations yet. start one with [+ dm].</p>}
      {loaded && conversations.length > 0 && shown.length === 0 && <p className="empty">nothing matches.</p>}
    </aside>
  );
}
