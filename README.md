# Chat Platform

A real-time collaborative communication platform.

> **Milestone 0 — Project Skeleton**
> This repository is at its earliest stage. No messaging logic has been implemented yet.

---

## Stack

| Layer | Technology |
|---|---|
| Frontend | React (placeholder) |
| Backend | Java 21, Spring Boot 3.3, Gradle |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Connection pool | HikariCP |
| Observability | Spring Boot Actuator |

---

## Repository Structure

```
chatplatform/
├── backend/          Spring Boot backend (Java 21)
├── frontend/         React frontend (placeholder)
├── docs/             Architecture docs and ADRs
│   └── adr/          Architectural Decision Records
├── load-tests/       Load testing scripts (future)
├── deploy/           Docker Compose and deployment configs
└── .github/
    └── workflows/    CI/CD pipelines
```

---

## Quick Start

### Prerequisites
- Docker (for PostgreSQL)
- Java 21+

### 1. Start PostgreSQL

```bash
cd deploy
docker compose up -d
```

### 2. Run the backend

```bash
cd backend
./gradlew bootRun
```

The backend starts on **http://localhost:8080**.

### 3. Verify health

```bash
curl http://localhost:8080/actuator/health | jq
```

Expected:

```json
{
  "status": "UP",
  "components": {
    "database": {
      "status": "UP",
      "details": { "connection": "ok" }
    },
    ...
  }
}
```

---

## Development

### Run tests

```bash
cd backend
./gradlew test
```

Tests use an H2 in-memory database — no PostgreSQL required.

### Reset the database

```bash
cd deploy
docker compose down -v   # removes the postgres_data volume
docker compose up -d
```

---

## Documentation

- [Architecture](docs/architecture.md)
- [ADR 0001 — Modular Monolith](docs/adr/0001-modular-monolith.md)

---

## Milestones

| Milestone | Status | Description |
|---|---|---|
| **0 — Skeleton** | ✅ Done | Project structure, Spring Boot, PostgreSQL, CI |
| **1 — Core Domain** | ⏳ Next | Users, workspaces, channels, messages |
| **2 — Real-time** | 🔲 Future | WebSocket delivery |
| **3 — Presence** | 🔲 Future | Online status, Redis |
| **4 — Scale** | 🔲 Future | Kafka, horizontal scaling |
