# Architecture — Phase 1: REST Core

> Status: **Phase 1 — REST Core (complete)**
> Last updated: 2026-10-08

---

## System Overview

```
┌─────────────────────────────────────────────┐
│               React Frontend                │
│  (placeholder — not yet implemented)        │
│  Port: 3000 (planned)                       │
└───────────────┬─────────────────────────────┘
                │  HTTP/REST (JSON)
                │  Authorization: Bearer <JWT>
                ▼
┌─────────────────────────────────────────────┐
│          Spring Boot Backend                │
│  Java 21 · Spring Boot 3.3 · Gradle         │
│  Port: 8080                                 │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  JwtAuthFilter (OncePerRequestFilter│   │
│  │  validates Bearer token every req)  │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  REST Controllers                   │   │
│  │  /api/auth  /api/conversations      │   │
│  │  /api/conversations/{id}/messages   │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  Service Layer                      │   │
│  │  AuthService · ConversationService  │   │
│  │  MessageService · TokenService      │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  Repository Layer — JdbcClient      │   │
│  │  (Spring JDBC, explicit SQL)        │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  HikariCP Connection Pool           │   │
│  │  max: 10 connections                │   │
│  └──────────────┬──────────────────────┘   │
└─────────────────┼───────────────────────────┘
                  │  JDBC
                  ▼
┌─────────────────────────────────────────────┐
│             PostgreSQL 16                   │
│  Port: 5432 (Docker Compose locally)        │
│  Migrations: Flyway (V1–V5)                 │
│                                             │
│  Tables: users, refresh_tokens,             │
│    conversations, conversation_participants,│
│    messages                                 │
└─────────────────────────────────────────────┘
```

---

## Package Structure

```
com.chatplatform/
  shared/
    exception/   ApiException, ErrorCode  — domain errors
    web/         GlobalExceptionHandler, ErrorResponse
    security/    JwtService, SecurityConstants
    util/        RequestIdFilter           — requestId in MDC + ThreadLocal
  user/
    repository/  User entity, UserRepository
    service/     UserService
    dto/         UserResponse
  auth/
    controller/  AuthController            — /api/auth/**
    service/     AuthService, TokenService
    repository/  RefreshToken, RefreshTokenRepository
    security/    JwtAuthFilter, SecurityConfig
    dto/         RegisterRequest, LoginRequest, RefreshRequest, AuthResponse, TokenPair
  conversation/
    controller/  ConversationController    — /api/conversations/**
    service/     ConversationService
    repository/  Conversation, ConversationParticipant,
                 ConversationRepository, ParticipantRepository
    dto/         CreateDirectRequest, CreateGroupRequest, ConversationResponse
  message/
    controller/  MessageController         — /api/conversations/{id}/messages
    service/     MessageService
    repository/  Message, MessageRepository
    dto/         SendMessageRequest, MessageResponse, MessagePage
  health/
    DatabaseHealthChecker, DatabaseHealthIndicator
```

**Modular monolith boundary rule:** No repository class is called by another domain's service. Cross-domain access goes through a service method (e.g. `ConversationService.requireParticipant` called by `MessageService`).

---

## Component Responsibilities

### JwtAuthFilter
Runs before every request. Extracts `Authorization: Bearer <token>`, validates the HMAC-SHA256 signature and expiry, and populates `SecurityContextHolder` with the user's UUID. If the token is absent or invalid, the request reaches Spring Security with no principal and is rejected (401) for any protected route.

### SecurityConfig
- CSRF disabled (stateless API — no sessions, no cookies).
- Session management: `STATELESS` — Spring never creates an `HttpSession`.
- Public routes: `/api/auth/**`, `/actuator/**`.
- All other routes require a valid JWT.
- BCrypt cost factor 12 (≈ 300 ms per hash on modern hardware).

### AuthService / TokenService
- Registration: BCrypt hash → INSERT user → issue token pair.
- Login: lookup by username (citext — case-insensitive) → BCrypt verify → issue token pair.
- Refresh: SHA-256(rawToken) lookup → revoke old → issue new in same family.
- Theft detection: if a revoked token is presented, the entire `family_id` is revoked (see ADR-0003).

### ConversationService
- DM uniqueness enforced by `direct_key = min(uid1,uid2) + ":" + max(uid1,uid2)` with a DB `UNIQUE` constraint.
- IDOR protection: inaccessible conversations return `404`, not `403` (see ADR-0002).

### MessageService
- Sequence allocation: `UPDATE conversations SET last_seq = last_seq + 1 RETURNING last_seq`. The row-level lock serialises concurrent senders. `MAX(seq)+1` is unsafe under concurrency (see ADR-0002).
- Idempotency: `client_msg_id` is client-generated. `UNIQUE(sender_id, client_msg_id)` on the messages table is the correctness mechanism. `ON CONFLICT DO NOTHING RETURNING *` handles concurrent duplicates without application-level locking.
- Pagination: keyset (`WHERE seq < :cursor ORDER BY seq DESC LIMIT :n`) — O(LIMIT) cost regardless of conversation size.

### HikariCP
- Bounded pool: max 10 connections.
- Application threads (Tomcat default: 200) ≠ DB connections.
- Threads wait up to 30 s for a free connection before failing with a timeout exception.

### Flyway
- Migrations in `src/main/resources/db/migration/`.
- Naming: `V{version}__{description}.sql`.
- **Never edit a migration after it has been applied to any environment.**

| Version | Contents |
|---|---|
| V1 | Enable `pgcrypto` extension |
| V2 | Enable `citext` extension; create `users` table |
| V3 | Create `refresh_tokens` table |
| V4 | Create `conversation_type` enum, `conversations`, `conversation_participants` |
| V5 | Create `messages` table |

---

## Configuration

All environment-specific configuration is externalised via environment variables (12-Factor App, factor III):

| Variable | Default (dev) | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/chatplatform` | JDBC connection string |
| `DB_USER` | `chatplatform` | PostgreSQL username |
| `DB_PASSWORD` | `chatplatform` | PostgreSQL password |
| `JWT_SECRET` | (insecure dev default — **must** be overridden in production) | HMAC-SHA256 signing key for JWTs. Must be ≥ 32 bytes. |
| `SPRING_PROFILES_ACTIVE` | (not set) | Set to `dev` for verbose SQL logging |

> ⚠️ **Production requirement:** `JWT_SECRET` must be set to a cryptographically random string of at least 32 bytes. The dev default is intentionally weak and will cause JJWT to warn if used.

---

## API Surface (Phase 1)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | ❌ | Register; returns user + token pair |
| POST | `/api/auth/login` | ❌ | Login; returns token pair |
| POST | `/api/auth/refresh` | ❌ | Rotate refresh token |
| POST | `/api/auth/logout` | ❌ | Revoke refresh token |
| POST | `/api/conversations/direct` | ✅ | Create or get direct conversation |
| POST | `/api/conversations/group` | ✅ | Create group conversation |
| GET  | `/api/conversations` | ✅ | List user's conversations |
| GET  | `/api/conversations/{id}` | ✅ | Get one conversation |
| POST | `/api/conversations/{id}/messages` | ✅ | Send message (idempotent) |
| GET  | `/api/conversations/{id}/messages` | ✅ | Paginate messages (keyset) |
| GET  | `/actuator/health` | ❌ | Health check |

---

## What Is Not Yet Implemented

| Feature | Planned Phase |
|---|---|
| WebSocket for real-time delivery | Phase 2 |
| Redis for presence / pub-sub | Phase 3 |
| Kafka for durable event streaming | Phase 4 |
| Horizontal scaling / load balancer | Phase 5 |
| Kubernetes / container orchestration | Phase 5+ |

