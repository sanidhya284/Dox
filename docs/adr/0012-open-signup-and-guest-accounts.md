# ADR-0012 — Open Sign-Up, Guest Accounts and the Product UI's Read Model

**Status:** In progress (Phase 6b; step 1 of 5 written)  **Date:** 2026-11-11  **Phase:** 6b

## Context
dox-chat.in is the first thing a visitor sees. Until now it was invite-only, and the UI could show neither sender names nor a conversation list with previews, nor start a DM. Phase 6b opens sign-up, adds a one-click guest demo, and gives the UI what it needs, with the abuse controls an open demo requires. This ADR is written step by step; later steps add the sign-up, guest and abuse-limit decisions.

## Decision 1: user search for starting DMs (step 1)
- **Endpoint:** `GET /api/users/search?q=` (authenticated).
  - a case-insensitive **username prefix**;
  - **2 to 50 characters** after trimming, otherwise `400 VALIDATION_ERROR`;
  - at most **20 results**, ordered by username;
  - **30 requests per minute per user** (`ratelimit.search-per-user-per-minute`).
- **Returns** `{id, username, displayName, isGuest}`. **Never an email**: the query doesn't even select it.
- **Excludes:**
  - the searcher;
  - anonymized accounts (`deleted_at` set);
  - bot accounts;
  - **users who blocked the searcher**, so a blocked user can't find the blocker.
- **Wildcards:** `%`, `_` and `\` in the query are escaped, so they match literally (`LIKE ... ESCAPE '\'`).
- **Index:** `idx_users_username_prefix` on `lower(username::text) text_pattern_ops` (V7). `UserSearchTest` runs EXPLAIN on the exact query the app uses (`UserRepository.SEARCH_SQL`). With sequential and bitmap scans off, the plan is `Index Scan using idx_users_username_prefix`, with the range condition `lower(username) ~>=~ 'ab' AND ~<~ 'ac'`. That shows the index can serve the query; it's not a performance claim.
- **Why prefix and not substring:** a prefix can use a b-tree index, while substring search would need a trigram index (`pg_trgm`). It also makes enumeration slower: an attacker learns at most 20 names per 2-character prefix, at 30 queries a minute.

## Decision 2: names and previews in conversation responses (step 1)
- **`participants`:** every member who **ever** joined: `{id, username, displayName, isGuest, isBot, isDeleted, left}`.
  - Members who left and anonymized accounts stay in the list, so their old messages still render a name.
  - The names come from `UserService` in one batched query; the conversation domain doesn't read the users table itself.
- **`lastMessage`:** `{senderId, preview, createdAt, seq}`, or `null` for an empty conversation.
  - **One query for the whole list,** joined on `m.seq = c.last_seq`: an exact lookup on the unique `(conversation_id, seq)` index, with no sort and no scan of the history.
  - **The preview** is cut at **120 Unicode code points** plus "…", so it never splits an emoji's surrogate pair.
- **Additive:** both fields are new, so clients that ignore unknown fields (the post-deploy check, an older UI) are unaffected.

## Decision 3: all rate-limit values in one place (step 1)
`ratelimit.*` binds to `RateLimitProperties`, whose defaults are the production values. The dev, test and bench profiles raise them. Step 5 adds a test that pins the production values.

## Consequences
- **V7 is additive:** `users.is_guest`, `users.is_bot`, `users.deleted_at`, the `user_blocks` table, and two indexes. The previous release still runs against it (ADR-0011 Decision 10).
- **Tests:** `UserSearchTest`, `ConversationReadModelTest`, and the search limit in `RateLimitTest`. Each guarantee has a mutation shown to fail it.
