import { describe, expect, it } from 'vitest';
import type { Api, ApiResult } from '../../sync/client';
import { ConversationsStore } from '../conversations';
import type { ConversationSummary } from '../model';

const summary = (id: string): ConversationSummary => ({
  id, type: 'GROUP', title: id, unreadCount: 0, lastMessageAt: null, createdAt: '2026-11-11T08:00:00Z', lastSeq: 0,
  participants: [], lastMessage: null,
});

/** A fake API: the list returns `listed`; details of an id in `deleted` are 404. Records every GET. */
function fakeApi(listed: string[], deleted: Set<string>) {
  const gets: string[] = [];
  const api: Api = {
    get: async <T>(path: string): Promise<ApiResult<T>> => {
      gets.push(path);
      if (path === '/api/conversations') {
        return { ok: true, status: 200, data: listed.filter((id) => !deleted.has(id)).map(summary) as T };
      }
      const id = path.split('/').pop()!;
      return deleted.has(id) ? { ok: false, status: 404, code: 'CONVERSATION_NOT_FOUND' }
        : { ok: true, status: 200, data: { ...summary(id), participantCursors: [] } as T };
    },
    post: async <T>(): Promise<ApiResult<T>> => ({ ok: false, status: 404 }),
  };
  return { api, gets };
}

describe('a conversation that answers 404 is dropped from the list, with no retry loop', () => {
  it('a 404 on open removes it, tells the sync client, and it is never requested again', async () => {
    const deleted = new Set<string>();
    const { api, gets } = fakeApi(['a', 'b'], deleted);
    const goneFromClient: string[] = [];
    const store = new ConversationsStore(api, (id) => goneFromClient.push(id));
    await store.load();
    expect(store.list.map((c) => c.id)).toEqual(['a', 'b']);

    deleted.add('b'); // removed by the guest cleanup while the list still shows it
    expect(await store.open('b')).toBeNull();
    expect(store.list.map((c) => c.id)).toEqual(['a']);
    expect(goneFromClient).toEqual(['b']);

    for (let i = 0; i < 5; i++) {
      await store.open('b');
      await store.noticeIds(['b']); // the sync client may still mention it
    }
    expect(gets.filter((g) => g === '/api/conversations/b')).toHaveLength(1);
    expect(store.list.map((c) => c.id)).toEqual(['a']);
  });

  it('a stale list that still contains a dropped conversation does not bring it back', async () => {
    const { api } = fakeApi(['a', 'b'], new Set());
    const store = new ConversationsStore(api);
    await store.load();
    store.drop('b');
    await store.load(); // this server still lists it (a race with the deletion)
    expect(store.list.map((c) => c.id)).toEqual(['a']);
  });

  it('an id the list lacks reloads the list once, never in a loop', async () => {
    const { api, gets } = fakeApi(['a'], new Set());
    const store = new ConversationsStore(api);
    await store.load();
    for (let i = 0; i < 5; i++) {
      await store.noticeIds(['never-listed']);
    }
    expect(gets.filter((g) => g === '/api/conversations')).toHaveLength(2); // the first load + one reload
  });
});
