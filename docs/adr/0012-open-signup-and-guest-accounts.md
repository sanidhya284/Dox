# ADR-0012 — Open Sign-Up, Guest Accounts and the Product UI's Read Model

**Status:** In progress (Phase 6b; steps 1 and 2 of 5 written)  **Date:** 2026-11-11  **Phase:** 6b

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

## Decision 4: open sign-up, with a global cap (step 2)
- **`registration.mode` defaults to `open`** (env `REGISTRATION_MODE`). `invite` stays available as a switch, with its tests ([ADR-0011](0011-deployment-and-pre-deploy-hardening.md) Decision 2, amended).
- **Limits on sign-up:** the per-IP limit (5/h) as before, plus a **global** limit across all IPs (`ratelimit.signup-global-per-hour`, 200). It **fails closed**: past it, every sign-up gets `429 RATE_LIMITED` with `Retry-After` until the hour's window ends. The counter `chat.signups{outcome=created|capped}` shows when it bites. An attempt that passes the cap but then fails (a taken username) still uses budget: simpler, and it errs towards closed.
- **Reserved usernames:** `guest-*`, `deleted-*` (any case) and `dox-bot` get `400 VALIDATION_ERROR` ("reserved"), so a real account can never collide with, or impersonate, a system account.
- **Not covered:** no email verification and no CAPTCHA. Distributed sign-up spam is bounded only by the global cap.

## Decision 5: "Try the demo" guest accounts (step 2)
- **`POST /api/auth/guest`** (allowed `Origin` required, like the other cookie endpoints) creates `guest-<10 hex>` with display name "Guest XXXX", `is_guest = true` and **no usable password**, then logs it in with the normal refresh cookie. `UserResponse.isGuest` labels it.
- **Seeded content, private to the guest:** a welcome DM from the bot and a group "Your demo group" with only the guest and the bot. **Guests never join shared conversations,** so nobody else's conversation list changes (tested: 25 guests in a row each get the welcome DM, and demo1's list is unchanged). The group contains only the bot, not the demo accounts, for exactly that reason.
- **Limits:** 3 guests per IP per hour (`ratelimit.guest-per-ip-per-hour`); at most **1000 live guests** (`guest.max-live`), beyond which `429 DEMO_BUSY` (the UI points to sign-up). The live count is checked, not locked, so concurrent requests can overshoot by a few. Gauge: `chat.guests.live`.
- **Restrictions:** guests can't create groups (`403 GUEST_NOT_ALLOWED`); they can start DMs. Step 3 gives them lower send and DM limits.

## Decision 6: the welcome bot (step 2)
- **`dox-bot`**, `is_bot = true`, no usable password, created idempotently at startup in every profile. Search never returns it.
- **Its messages use `MessageService.sendAsSystem`:** the same transaction, row lock and seq code as any send; only the per-user limits are skipped. They count as `chat.messages.sent{channel=system}`.
- **Reply:** when someone writes in a conversation the bot is in, the bot replies with one fixed text, **at most once per 10 s per conversation**, then marks the message read. It reacts after commit (`@TransactionalEventListener(AFTER_COMMIT)`), on its own single thread with a bounded queue; a full queue drops the reaction (best effort, never back-pressure on sends). It replies before it moves its read cursor, so "read cursor at seq N" means message N is fully handled.

## Decision 7: guest cleanup and account removal: anonymize, don't delete history (step 2)
- **Every 15 minutes** (`guest.cleanup-interval`, own thread), guests older than 24 h (`guest.ttl`) are removed, **one transaction per guest**, claimed with `SELECT ... FOR UPDATE SKIP LOCKED`: two instances never remove the same guest and never wait on each other (tested by holding a row lock from another connection).
- **The removal step** (`AccountRemovalService.remove`, shared with the runbook's "remove an abusive account"):
  1. a conversation is **deleted** (messages first; participants cascade) only when **no other active human remains** in it: every other member who hasn't left is the bot, a demo account, or an already removed account. A live guest counts as a human (amended 2026-11-11 after review; the first rule also deleted DMs with live guests);
  2. every other conversation is **left**, and its messages **stay**, attributed to the removed account. Deleting them would leave seq gaps and break every other member's gap detection and history ([ADR-0008](0008-client-sync-protocol.md)); `messages.sender_id` also references the user;
  3. the user row is **anonymized**: username `deleted-<32 hex of the id>` (unique, since it's derived from the id; the prefix is reserved), email `deleted-<hex>@deleted.invalid`, display name "Deleted guest" or "Deleted user", a password hash BCrypt never matches, `deleted_at` set;
  4. refresh tokens and WebSocket tickets are revoked, and after commit realtime closes the user's sockets through **one "close user" path** (`SessionRevocationListener.closeUser`, which Phase 4 routes to every instance): **`4008 guest expired`** for guests. (An operator removal runs in a separate process and can't close sockets: Decision 9.)
- **Access tokens** already issued stay valid until they expire (up to 15 minutes), but the account is in no conversation any more, so they can read and send nothing.
- **A DM between two guests:** when the first expires, the second is still a live human, so the DM is kept and the first guest is anonymized there; it's deleted when the second expires too.

## Decision 8: login can't tell guests, the bot or removed accounts from unknown names (step 2)
Login looks up only accounts that are not guests, not bots and not removed. Every other username takes the unknown-user path, which now runs **one BCrypt comparison against a dummy hash** (a real BCrypt(12) hash made at startup), so a refusal costs the same whether the name is unknown, a guest, the bot, removed, or a real account with a wrong password, and the 401 body is identical (`LoginEquivalenceTest` spies on the encoder: exactly one comparison, the same dummy hash, the same error).

## Decision 9: removing an abusive account is an explicit one-off admin process (amended 2026-11-11 after review)
- **How:** `--remove-account=<username>` with profiles `prod,admin` (runbook 9a). Without the admin profile, `main()` refuses with exit `2` before Spring starts, so the live app can never be told to do it by accident.
- **Admin mode** (`application-admin.yml`, `AdminFlywayConfig`): no web server, the cleanup job and demo seeding are not created, the bot isn't seeded, heartbeats aren't scheduled, a 2-connection pool, and **Flyway validates instead of migrating**, so an image that doesn't match the live schema refuses to start (exit `1`). It exits on its own: `0` removed, `3` no such live account.
- **Limitation:** it's a separate process, so it **can't close the user's sockets** on the live app (that needs the Phase 4 cross-instance "close user" path or an admin endpoint). The sockets end within about 30 minutes (lifetime `4001`, and the access token can't be refreshed), and meanwhile the account is in no conversation.

## Consequences
- **V7 is additive:** `users.is_guest`, `users.is_bot`, `users.deleted_at`, the `user_blocks` table, and two indexes. The previous release still runs against it (ADR-0011 Decision 10).
- **Tests:** `UserSearchTest`, `ConversationReadModelTest`, `GuestAccountTest`, `GuestCleanupTest`, `BotReplyTest`, `LoginEquivalenceTest`, `SignupCapTest`, and the search and guest limits in `RateLimitTest`. Each guarantee has a mutation shown to fail it.
- **New error codes:** `403 GUEST_NOT_ALLOWED`, `429 DEMO_BUSY`. **New close code:** `4008` ([ADR-0008](0008-client-sync-protocol.md) table).
- **Runbook:** "Remove an abusive account" in [AWS-DEPLOY-RUNBOOK.md](../AWS-DEPLOY-RUNBOOK.md) runs the same removal step (`--remove-account=<username>`).
