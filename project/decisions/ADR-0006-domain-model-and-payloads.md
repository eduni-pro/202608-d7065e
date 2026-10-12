# ADR-0006 — Records as domain types; domain events as the internal payload

**Status:** Accepted · 2026-10-05
*Retargeted 2026-10-05: this record was written for the superseded access-control use case
(A-01) and its examples, type names and requirement ids are rewritten here against A-06 and
the code as built. The decision itself is unchanged.*
*Restated 2026-10-06 against the source tree. Every type named below was checked to exist at
the path claimed for it; one thing this record had never said is now said — where the events
live, and why that is not any component's `entity` package (ADR-0005). The decision itself is
again unchanged.*

## Context

ADR-0005 puts a port at every boundary crossing and requires the contract to be written in
the core's vocabulary. The open question is what the core's types are, and how much
translation stands between them and what travels on the wire or lands in a row.

Two schemas in this system are **not ours**: BuildSim's REST representation, and the journal's
SQL rows. Everything else — the readings, commands, residuals, fault states and strategy
changes flowing between this project's own processes — has a schema this project defines, and
all of those processes are built, versioned and started together.

## Decision

- **Domain types are Java records.** Aggregates and value objects, with invariants enforced
  in compact constructors, in `entity`. `ZoneId`, `Ppm`, `DamperPosition`, `AirflowRate`,
  `FanSpeed`, `FanPower`, `Residual`, `CusumStatistic`, `CommandId` and `CaseId` are value
  objects rather than `String` and `double`; `VentilationZone` (a degraded zone refuses a
  normal control command), `AirHandlingUnit` and `FaultCase` (a latched case leaves CONFIRMED
  only through service) are the aggregates that carry the invariants.
- **Domain events are the payload on transports this project owns.** `ReadingObserved`,
  `FanPowerObserved`, `CommandIssued`, `FanSpeedCommanded`, `ResidualComputed`,
  `FaultStateChanged` and `StrategyChanged` are serialised
  directly. No parallel DTO hierarchy, no mapper classes, no mapping framework.
- **The events live in `shared/event`, beside `DomainEvent` and `Topics`, and not in any
  component's `entity`.** An event is the vocabulary two components share, not one
  component's domain type: `ReadingObserved` is published by `co2-sensor` and read by
  `dcv-controller`, `fault-monitor` and `dashboard`. Putting it in the publisher's `entity`
  would make all three subscribers depend on the publisher's business component in order to
  name what they subscribe to, which is the coupling BCE exists to prevent. The package carries
  no framework import, so it obeys the dependency rule exactly as `entity` does; ADR-0005
  records it as an exception to the package *names* and not to the rule.
- **Translation exists only where the schema is not ours**: the BuildSim adapter and the
  journal adapter. It is a few lines inside the adapter method, not a `Mapper` type.
- **Serialisation configuration stays out of `entity`.** `EventCodec` in `boundary` names the
  wire fields explicitly, so the records stay plain Java and `entity` keeps no framework
  import — and there are no serialisation annotations anywhere.

## Because

- BuildSim stores every sensor value and actuator state as a **string** — a CO₂ reading is
  `"420"` under `data_type: "text"`, a damper state is `"0.150"` — it has no concept of a
  zone's control mode or of a fault case, which R-7 and R-5 respectively need, and it keys
  rooms by the floor-qualified `level0/A115` where `ZoneId` is the bare room name, because a
  slash is an MQTT topic separator. Adopting that shape as the
  domain type would make BuildSim's API the domain model and would reintroduce exactly the
  ripple ADR-0005 exists to prevent — so `BuildSimAdapter` is the one place translation earns
  its cost.
- For the payloads this project defines, a separate DTO would be a copy of the record with
  a different package name. It would add code, add a place for the two to drift, and buy
  nothing, because there is no independent consumer whose schema must be held stable.
- Making the domain event the payload makes R-5 nearly free: the journal stores the
  event stream, so reconstructing a diagnosis is reading back the events that produced it
  rather than reassembling it from a bespoke persistence shape.
- Keeping the events in one package outside the components is what lets `ResidualJournalPort`
  declare `List<DomainEvent> replayCase(CaseId)` in the core's own vocabulary. If the events
  belonged to their publishers, the port that replays a case would have to name four business
  components to describe its return type.
- Value objects with invariants put validation at construction, which is where the boundary
  already is: a `DamperPosition` that cannot leave [0,1] cannot leave it anywhere downstream.

## Alternatives rejected

- **A DTO layer at every boundary, with a mapping framework** — the conventional answer,
  and correct when consumers deploy independently and schemas must be versioned. Rejected
  because every process here is built and deployed in lock step by one author, so the
  versioning problem the layer solves does not exist, and the layer would be pure cost.
- **BuildSim's representation as the domain type** — no translation at all. Rejected: it is
  strings with no room for a control mode, a latched case or a bounded position, its room key
  cannot be an MQTT topic level, and it would make an external API's shape the core's
  model.
- **Classes with builders instead of records** — rejected; records give equality, immutability
  and a compact constructor for invariants without ceremony, and read as domain vocabulary.
- **Each event in its publisher's `entity` package**, which would keep the structure to three
  package names with no exception to explain. Rejected on the import graph rather than on
  taste: every one of the seven is named in at least four of the nine modules and every one has
  a reader that is not its publisher, so the tidier tree buys a dependency from every
  subscriber onto every publisher and gains nothing. The exception is cheaper than the
  coupling, and ADR-0005 carries the cost explicitly.

## Trade-off accepted

Coupling the wire format to the domain model means a domain refactor is a wire-format
change, so all processes must be rebuilt and restarted together. That is acceptable
precisely because they already are, and it is a decision to revisit the moment any consumer
becomes independently deployable. The codec names every wire field explicitly rather than
reflecting over the records, so the native image ADR-0002 keeps for the final footprint
measurement needs no reflection registration for the types crossing a boundary — which is
what the hand-written codec buys for the lines it costs.

## Defence in one sentence

Translation happens only where the schema is not ours — BuildSim and the journal — because
for payloads this project defines, a DTO would be the same record in another package, and
the versioning problem that justifies DTOs does not exist when every process ships together.
