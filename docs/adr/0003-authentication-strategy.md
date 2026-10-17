# ADR-0003 — Authentication Strategy: Stateless JWT + Stored Refresh Tokens

**Status:** Accepted
**Date:** 2026-10-08 (amended 2026-10-17)
**Phase:** 1 — REST Core

---

## Context

The application needs to authenticate users on every request without requiring a database lookup per request, and needs sessions that can be revoked (logout, stolen token detection).

Three broad options exist:

| Option | Per-request DB hit | Revocable | Complexity |
|---|---|---|---|
| Opaque session tokens (full DB) | Yes | Yes | Low |
| Stateless JWT only | No | No (until expiry) | Low |
| Stateless JWT + stored refresh token | No (access token) | Yes (via refresh revocation) | Medium |

---

## Decision 1 — Access Token: Stateless JWT (15-minute TTL)

A JSON Web Token signed with HMAC-SHA256 (`HS256`) using a server-side secret (`JWT_SECRET`). The token encodes the user's UUID in the `sub` claim and carries an expiry (`exp`).

**Validation is pure computation** — the server verifies the signature and checks the expiry with no database access. This means an access token cannot be individually revoked before its `exp`. A stolen access token is valid for up to 15 minutes.

**The secret fails closed.** The application refuses to start if `jwt.secret` is missing, blank, shorter than 32 bytes, or equal to the old built-in default. Local development uses the `dev` profile, which supplies a dev-only secret. Production must set `JWT_SECRET`.

**Why 15 minutes?**
- Long enough that clients do not need to refresh constantly (one refresh per session typical).
- Short enough to limit the blast radius of a stolen token.
- Token blacklisting (Redis) would eliminate this window but introduces distributed state — a Phase 4 decision.

**Payload:**
```json
{ "sub": "uuid", "iat": 1234567890, "exp": 1234568790 }
```

No roles, email, or username in the token. The `sub` (UUID) is enough for the application to load the user from the database when richer data is needed.

---

## Decision 2 — Refresh Token: Stored SHA-256 Hash with Family-Based Rotation

### Why the raw token is never stored

The `refresh_tokens` table stores `SHA-256(rawToken)`, not the raw token. If the database is compromised, an attacker cannot use the hashed values to authenticate — SHA-256 is a one-way function. The raw token exists only in the HTTP response and the client's secure storage.

### Why SHA-256 and not BCrypt for token hashing

Refresh tokens are high-entropy random UUIDs (128 bits), not passwords. BCrypt is designed to slow down dictionary attacks against low-entropy inputs (passwords). Against a random UUID, BCrypt provides no security benefit over SHA-256 but adds ≈300 ms latency per refresh operation. SHA-256 is appropriate here.

### Token rotation

Each use of a refresh token revokes it and issues a new one. The new token inherits the same `family_id` (the UUID of the initial login event).

```
Login → token1 (family: F1)
Use token1 → revoke token1, issue token2 (family: F1)
Use token2 → revoke token2, issue token3 (family: F1)
```

**Rotation is atomic.** A token is claimed with a single conditional `UPDATE ... WHERE revoked_at IS NULL AND expires_at > now RETURNING *`. The row lock makes concurrent requests with the same token queue up, so exactly one wins and the rest find it already revoked. An earlier SELECT-then-UPDATE version let five concurrent requests all succeed.

### Theft detection via `family_id`

If a revoked token is presented:

```
Attacker steals token1 (already revoked when legitimate client used it)
Attacker presents token1 → server sees it is revoked
Server revokes ALL tokens with family_id = F1
Both attacker and legitimate user are now logged out
```

Without `family_id`, a replayed revoked token would just return a 401. With `family_id`, it triggers a cascade revocation of the entire session lineage — signalling a possible theft event and protecting the user.

---

## Decision 3 — Password Hashing: BCrypt (cost 12)

**Rejected: SHA-256, MD5, PBKDF2**
Fast hash functions allow GPU-based dictionary attacks at billions of operations per second. BCrypt is deliberately slow — cost factor 12 ≈ 300 ms per hash. An attacker with the database file gets at most a few thousand guesses per second.

**Why not Argon2id?**
Argon2id is the current gold standard (memory-hard, resistant to ASIC attacks). Spring Security supports it via `Argon2PasswordEncoder`. However:
- BCrypt at strength 12 is still considered secure for current hardware.
- BCrypt is Spring Security's native `BCryptPasswordEncoder` — zero additional dependencies.
- The migration path to Argon2 is straightforward: add `spring-security-crypto`, change the `PasswordEncoder` bean, existing BCrypt hashes continue to work (Spring's `DelegatingPasswordEncoder` handles mixed hash formats).

This upgrade is recommended as a future hardening milestone.

---

## Decision 4 — IDOR Protection: 404 for Inaccessible Conversations

A request by User C for a conversation between Users A and B returns `404 NOT FOUND`, not `403 FORBIDDEN`. This applies to every conversation and message endpoint, including sending, and nothing is persisted.

**Why not 403?**
Returning 403 confirms that the conversation exists. An attacker could enumerate conversation UUIDs and use 403 responses to map the graph of existing conversations. 404 reveals nothing about existence.

This is the standard defense against **Insecure Direct Object Reference (IDOR)** attacks in REST APIs.

---

## Consequences

- **Access token non-revocability:** A user who changes their password or is suspended will still have valid access tokens for up to 15 minutes. Mitigation: short TTL. Full mitigation requires a token blacklist (Redis, Phase 4).
- **Refresh token one-time use:** Clients must treat refresh tokens as single-use. A client that fails to store the new refresh token after a successful rotation will be locked out of their session.
- **Family revocation on theft:** A false positive is possible — if a legitimate client's refresh request fails mid-flight (network error after the server committed but before the client received the response), the client retries with a now-revoked token and triggers family revocation, logging everyone in that session out. This is an acceptable tradeoff: a false positive causes inconvenience (re-login); a false negative leaves a stolen session active.
- **Revocation must survive the 401:** the refresh methods use `noRollbackFor = ApiException.class`. Without it the family revocation was rolled back when the exception propagated, and a replayed token left the legitimate token valid. An integration test covers this.
- **Browser storage is undecided:** whether the refresh token lives in an HttpOnly cookie (needing CSRF/Origin protection) or in memory is decided in Phase 2, when the React client starts.
