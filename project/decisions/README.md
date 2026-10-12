# Decision log

One file per decision, numbered in the order taken. The format follows the course's own
pattern — a choice is only a decision when it names the requirement it serves, the
alternative it beat, and the price paid:

> **X was chosen because … (requirement); the alternative was Y; the trade-off accepted is Z.**

Each record also carries a **Defence in one sentence** — the answer to give at the
whiteboard when the examiner asks why. If that sentence cannot be written, the decision
is not understood well enough to keep.

**Status** is `Proposed` (options on the table, not yet chosen), `Accepted`, or
`Superseded by ADR-nnnn`. Superseded records are never deleted: the path the design took
is itself evidence, and the final report needs a "changes since the proposal" account.

| # | Decision | Status |
|---|---|---|
| [0001](ADR-0001-use-case.md) | Use case: ventilation fault detection, diagnosis and graceful degradation (A-06) | Accepted |
| [0002](ADR-0002-runtime-stack.md) | Runtime and language: Java 25 with MicroProfile / Quarkus | Accepted |
| [0003](ADR-0003-detection-tiers.md) | Two-tier detection: rules hold authority, learned model advises | Superseded by ADR-0001 (A-06) |
| [0004](ADR-0004-door-state-ownership.md) | Ownership of BuildSim's door collection | Superseded by ADR-0001 (A-06) |
| [0005](ADR-0005-architecture-style.md) | Architecture style: BCE structure, ports at every boundary crossing | Accepted |
| [0006](ADR-0006-domain-model-and-payloads.md) | Records as domain types; domain events as the internal payload | Accepted |
| [0007](ADR-0007-repository-layout.md) | The project lives in the course-material repository | Accepted |
| [0008](ADR-0008-room-topology.md) | Room set and coupling topology | Accepted |
| [0009](ADR-0009-healthy-room-constraint.md) | The healthy rooms' airflow bound stays at 5 %, in both directions | Accepted |
| [0010](ADR-0010-plausibility-guards-are-bounds.md) | The plausibility guards are bounds, not filters of the measurement | Accepted |
| [0011](ADR-0011-observability-telemetry.md) | Operational telemetry is OpenTelemetry metrics pushed to Prometheus, published from `boundary/` only | Accepted |
| [0012](ADR-0012-service-state-survives-restart.md) | Service state survives a restart, as a JSON document on the service's own volume | Accepted |

## Still to decide

Kept here so the backlog is visible rather than discovered late:

- **Simulated time** — real-time vs accelerated clock, and how tests pin it.
- **Dashboard technology** — Grafana over the store vs a Quarkus application vs BuildSim's own
  viewer alone. The plan (§3) names `dashboard` as Quarkus writing BuildSim's
  `/api/room-layers` and `/api/alerts`, which is what the container does; the choice is to be
  recorded with its rejected alternatives, not re-opened. **Partially answered by ADR-0011**: the
  Quarkus `dashboard` stays the occupant-facing view and Grafana enters beside it as the
  operator's view of the control loop's internals. Two audiences, not a replacement.
- **Observer occupancy dependency** — step-response only vs an estimated head-count. The
  plan's risk register answers "restrict detection to commanded step responses only; record
  as an ADR", which fixes what the observer in T-11 may assume.
