# ADR 0001: Start with a Modular Monolith Instead of Microservices

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-07 |
| **Deciders** | Backend team |
| **Milestone** | 0 — Project Skeleton |

---

## Context

We are building a real-time collaborative communication platform. Before choosing
an architectural style, we need to reason about where we are in the product
lifecycle and what our actual constraints are.

### Current situation
- Team size: small (1–3 engineers at this stage)
- Domain model: not yet fully understood
- Scale requirements: unknown / not yet measured
- Deployment infrastructure: minimal
- Operational maturity: early

---

## Decision

**We will build a modular monolith for the first several milestones.**

The codebase is organised as a single Spring Boot application with clearly
separated Java packages per domain (`user`, `auth`, `conversation`, `message`).
Package boundaries are enforced by convention — cross-package calls go through
service interfaces, never directly to another package's repository.

---

## Rationale

### Why not microservices from day one?

Microservices are a solution to **organisational and scaling problems that we
do not yet have**. Starting with them imposes costs that are not justified:

| Problem with premature microservices | Impact |
|---|---|
| **Distributed transactions** | Eventual consistency for operations that should be atomic (e.g. creating a user + their default workspace) |
| **Network latency on every internal call** | A request that would be a method call in a monolith becomes an HTTP roundtrip |
| **Operational overhead** | Each service needs its own deployment pipeline, health monitoring, logging aggregation, and service discovery |
| **Team bandwidth** | A small team spends most of its time on infrastructure, not on the messaging domain |
| **Premature API contracts** | Service boundaries that seemed obvious at design time frequently turn out to be wrong once you understand the domain |

Martin Fowler's "MonolithFirst" pattern describes this failure mode precisely:
> "Don't start a new project with microservices, even if you're sure your
> application will be big enough to make it worthwhile." — martinfowler.com/bliki/MonolithFirst.html

### Why is a *modular* monolith better than a flat monolith?

A flat monolith accumulates coupling over time — any class can call any other
class, which makes it impossible to extract a service later without untangling
a hairball.

By structuring the monolith into packages with explicit boundaries *now*, we:
1. Understand the domain model clearly before drawing service lines
2. Can extract a package into a microservice later *with confidence*, because
   the API surface is already defined
3. Get all the simplicity benefits of a monolith while retaining the option
   to decompose

### When would we reconsider?

We would re-evaluate this decision if:
- A specific domain (e.g., real-time message delivery) needs independent
  deployment cadence from the rest of the system
- A single domain's load profile diverges dramatically from the others
  (e.g., message delivery vs. user profile management)
- The team grows large enough that multiple teams own different domains and
  need independent release pipelines
- We have profiling data showing that a specific component is the bottleneck

---

## Consequences

**Positive:**
- Fast initial development (no distributed systems overhead)
- Simple local development environment (`docker compose up -d` for PostgreSQL, then `./gradlew bootRun`)
- Atomic database transactions across domain boundaries
- Easy refactoring of domain model without changing APIs
- Clear evolutionary path toward microservices if/when needed

**Negative:**
- Requires discipline to maintain package boundaries (no enforcement by the compiler)
- A single deployment unit — if one module's code causes an OutOfMemoryError, the whole application restarts
- Technology lock-in per module (all modules must be Java 21 + Spring)

**Mitigations:**
- Architecture tests (ArchUnit) could enforce package boundary rules programmatically; not implemented yet.
- The HikariCP pool and Flyway migrations are already designed to be compatible with an eventual extraction
