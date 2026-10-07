# Architecture — Milestone 0

> Status: **Phase 0 — Project Skeleton**
> Last updated: 2026-10-07

---

## System Overview

```
┌─────────────────────────────────────────────┐
│               React Frontend                │
│  (placeholder — not yet implemented)        │
│  Port: 3000 (planned)                       │
└───────────────┬────────────────────────────-┘
                │  HTTP/REST (JSON)
                ▼
┌─────────────────────────────────────────────┐
│          Spring Boot Backend                │
│  Java 21 · Spring Boot 3.3 · Gradle         │
│  Port: 8080                                 │
│                                             │
│  ┌─────────────────────────────────────┐   │
│  │  Spring Web (Tomcat embedded)       │   │
│  │  REST Controllers (Phase 1+)        │   │
│  └──────────────┬──────────────────────┘   │
│                 │                           │
│  ┌──────────────▼──────────────────────┐   │
│  │  Service Layer (Phase 1+)           │   │
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
│  Migrations: Flyway                         │
└─────────────────────────────────────────────┘
```

---

## Component Responsibilities

### React Frontend (placeholder)
- Not yet implemented.
- Will communicate with the backend exclusively over HTTP REST (Phase 1).
- WebSocket upgrade will be introduced in a later milestone.

### Spring Boot Backend
The backend is a **modular monolith** — a single deployable JAR that contains
all application logic, organised into distinct Java packages that enforce
module boundaries without the operational overhead of separate services.

| Package | Responsibility |
|---|---|
| `com.chatplatform` | Application entry point and Spring configuration |
| `com.chatplatform.health` | Custom Actuator health indicators |
| `com.chatplatform.user` | (Phase 1) User domain |
| `com.chatplatform.workspace` | (Phase 1) Workspace domain |
| `com.chatplatform.channel` | (Phase 1) Channel domain |
| `com.chatplatform.message` | (Phase 1) Message domain |

Each future package will own its own controllers, services, and repository
classes. No shared mutable state crosses package boundaries — cross-package
calls go through service interfaces.

### HikariCP
- A bounded, reusable pool of JDBC connections.
- Application threads (Tomcat default: 200) ≠ DB connections (pool max: 10).
- A thread that needs the DB borrows a connection, runs its query, then returns it.
- If all 10 connections are in use, arriving threads wait up to 30 s before failing.
- This prevents the backend from overwhelming PostgreSQL's `max_connections`.

### Flyway
- Tracks and runs SQL migration files in `src/main/resources/db/migration/`.
- Files must follow the naming convention: `V{version}__{description}.sql`
- Flyway records which migrations have run in the `flyway_schema_history` table.
- **Never modify a migration file after it has been applied** — Flyway will refuse to start.

### Spring Boot Actuator
- Exposes `GET /actuator/health` — returns JSON with the application's health status.
- Currently reports: Spring's built-in datasource check + our custom `DatabaseHealthIndicator`.
- Returns HTTP 200 (UP) or 503 (DOWN).

---

## Configuration Strategy

All environment-specific configuration is externalised via environment variables:

| Variable | Default (dev) | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/chatplatform` | JDBC connection string |
| `DB_USER` | `chatplatform` | PostgreSQL username |
| `DB_PASSWORD` | `chatplatform` | PostgreSQL password |
| `SPRING_PROFILES_ACTIVE` | (not set) | Set to `dev` for verbose SQL logging |

This follows the [12-Factor App](https://12factor.net/config) principle: **configuration lives in the environment, not in the code**.

---

## What Is Not Yet Implemented

| Feature | Planned Milestone |
|---|---|
| User accounts & authentication | Phase 1 |
| Workspaces, channels, messages | Phase 1 |
| WebSocket for real-time delivery | Phase 2 |
| Redis for presence/pub-sub | Phase 3 |
| Kafka for durable event streaming | Phase 4 |
| Horizontal scaling / load balancer | Phase 5 |
| Kubernetes / container orchestration | Phase 5+ |
