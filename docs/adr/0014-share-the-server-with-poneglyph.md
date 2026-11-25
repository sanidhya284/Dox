# ADR-0014 — Share the Server with Poneglyph (an `edge` network and one site block)

**Status:** Accepted  **Date:** 2026-11-26  **Phase:** ops

## Context
- **What's being added:** Poneglyph is a separate project (its own repo, ADRs 0035 and 0036 there) that will run on this server. It's the only option under the AWS project's $35 monthly spend limit, which would otherwise pause both projects.
- **Ports:** Dox's Caddy (`web`) already holds 80/443 and issues certificates, and only one process can hold them.
- **What Poneglyph brings:** its own small Caddy (no access log, its own body-size cap) and expects plain HTTP from a trusted proxy at a fixed address.

## Decision
- **TLS:** Dox's Caddy terminates TLS for `poneglyph.dox-chat.in` (a CNAME to `dox-chat.in`).
- **Routing:** it forwards that host, and only that host, to Poneglyph's Caddy at `10.89.0.20:80`.
- **A shared network, `edge`** (`10.89.0.0/24`), is defined and created by this stack (`deploy/compose.yml`), with `web` at the fixed address `10.89.0.10`. Poneglyph's stack joins it as `external`, so neither project can reach the other's database or app.
- **The site block** (`deploy/caddy/Caddyfile`) has no `log` directive, so Poneglyph's requests are not written to this access log (Caddy lists the host under `skip_hosts`).
- **The host name** comes from `PONEGLYPH_HOST`, which compose sets to `poneglyph.<DOMAIN>`. Locally and in CI that is `poneglyph.localhost` (Caddy's local CA), so nothing asks ACME for a real name outside production.
- **Rollout:** the pipeline reloads the Caddyfile in place, as for any Caddyfile-only change. `deploy.sh` recreates `web` only when the UI changes, so the network change is applied once by the operator: `up -d --no-deps web`, the documented ~1 s Caddy blip (runbook 9c).

## Alternatives considered
- **A second server, or a larger instance:** over the $35 spend limit, which pauses this project too.
- **Proxying straight to Poneglyph's app:** Poneglyph's own ingress rules would then live in this repo.
- **Teaching `deploy.sh` to recreate `web` on any compose change:** more deploy logic for a one-time change.
- **Connecting the network live** (`docker network connect`, no blip): kept only as the emergency path. A network created outside compose clashes with this compose file's definition the next time `web` is recreated.

## Consequences
- **Good:**
  - One TLS terminator and one certificate store.
  - Nothing of Poneglyph's is in Dox's logs, and Dox's own site config is unchanged.
- **Bad / accepted cost:**
  - Two products share one 2 GiB box. Poneglyph's limits are 576 MiB more on top of Dox's 1,504 MiB, an overcommit on paper; measured use is about 811 MB. Poneglyph's ADR 0035 holds the figures and revisit triggers.
  - A broken Caddyfile change can affect both sites. Caddy refuses an invalid config on reload and keeps the old one, and runbook 9c has the rollback.
  - The fixed `10.89.0.0/24` must not clash with other networks on the box (checked 2026-11-26: only `172.17/16` and `172.18/16`).
- **The free plan ends 7 Apr 2027,** when both projects stop unless the account is upgraded.
