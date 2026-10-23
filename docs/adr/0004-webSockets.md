# ADR 0004: WebSocket Connection Tickets Are Stored in Postgres, Not Redis or In-Memory

## Status
Accepted

## Context

Phase 2 introduces WebSocket-based real-time messaging. The WS handshake needs
an authentication step: a client must prove it's an authenticated user before
the connection upgrade completes. We use a short-lived, single-use ticket,
minted via an authenticated REST call and redeemed during the handshake.

This system is designed to scale horizontally (Phase 4+): multiple backend
instances behind a load balancer. A ticket minted by the instance that serves
the REST call may need to be redeemed by a *different* instance — whichever
one accepts the client's WS connection. This means ticket state cannot live
in a single instance's memory; it must be visible to every instance.

Three options were considered for where ticket state lives:

1. In-memory map (`ConcurrentHashMap` per instance)
2. Postgres table
3. Redis (string keys with TTL)

## Decision

Tickets are stored in a Postgres table (`ws_tickets`), consumed atomically via
`DELETE ... RETURNING user_id`.

## Alternatives Considered

**In-memory map.** Simplest to implement, but breaks immediately under
horizontal scaling — a ticket minted on instance A is invisible to instance C.
Since multi-instance deployment is an explicit, planned stage of this project
(not a hypothetical), building the single-instance version first would mean
deliberately building something known to need a rewrite, for no learning
benefit: the failure mode is already fully understood before writing any
code, so there's nothing left to discover by hitting it.

**Redis.** A natural fit for expiring keys and would work correctly across
instances. Rejected for a specific architectural reason, not convenience:
elsewhere in this system, Redis is scoped to functionality that is explicitly
allowed to degrade or be briefly wrong — Pub/Sub message routing and presence
both fail soft by design (see ADR on cross-instance routing, Phase 4/5).
Authentication is not in that category. If Redis stored tickets and Redis
went down, *no new WebSocket connection could be established on any
instance*, including an instance with zero cross-instance traffic — a hard
outage caused by a component this system otherwise treats as optional.
Introducing Redis as a second hard dependency, scoped inconsistently with
every other use of Redis in the system, was judged not worth the latency
benefit at this stage.

**Postgres.** Already the system's one accepted hard dependency — every
other feature already assumes Postgres must be up. Storing tickets here adds
no new failure mode; it stays inside the risk surface already accepted.
`DELETE ... RETURNING` gives atomic, single-use consumption for free, and is
the same primitive already used for refresh-token rotation (Phase 1),
reusing an established, already-tested pattern rather than inventing a new
one.

## Consequences

- Two extra lightweight Postgres round-trips per connection attempt (insert
  on issue, delete-returning on consume) — not on the message hot path, so
  negligible at current scale.
- Expired rows require cleanup. Current approach: opportunistic cleanup on
  each `issue()` call (delete expired rows for that user). A scheduled sweep
  for global cleanup is a known gap, acceptable for now, tracked as future
  work rather than a correctness issue.
- The decision is deliberately cheap to reverse: ticket persistence sits
  behind a `TicketService` interface (`issue`, `consume`). If connection-
  establishment-rate benchmarking (Phase 8) ever shows ticket consumption as
  a bottleneck under high reconnect churn, swapping in a Redis-backed
  implementation is a single-class change, not a system-wide refactor.
- Verified by mutation testing: replacing the atomic `DELETE ... RETURNING`
  with a non-atomic `SELECT` caused exactly the tests dependent on one-time
  consumption semantics to fail
  (`issuedTicketConsumesToTheRightUserExactlyOnce`,
  `concurrentConsumeOfOneTicketYieldsExactlyOneSuccess`,
  `twoTicketsForOneUserAreIndependent`), confirming the atomicity property
  this decision depends on is actually under test, not assumed.

## Revisit When

Connection-establishment rate (not message throughput) is measured as a
bottleneck, and Postgres round-trip cost on ticket issue/consume is shown to
be the limiting factor — not before.