# ADR-0005 — Architecture style: BCE structure, ports at every boundary crossing

**Status:** Accepted · 2026-10-05
*Retargeted 2026-10-05: this record was written for the superseded access-control use case
(A-01) and its examples, component names and requirement ids are rewritten here against A-06
and the code as built. The decision itself is unchanged.*
*Restated 2026-10-06 against the source tree rather than against memory of it, which found
three descriptions of the code that were wrong: the interface inventory was incomplete and
placed `Topics` in a layer it is not in, the claim that the ports are the only interfaces in
the system was false of `EventCodec`, and the two device processes are around 250 lines each
rather than around a hundred. The structural exception in `shared/event` is recorded here for
the first time. No decision changed; three sentences about the code did.*

## Context

The intent is that replacing one implementation — the broker, the store, the clock —
must not ripple through every class and every business component. Hexagonal architecture,
Clean Architecture and BCE are three formulations of one underlying rule (dependencies
point inward; the core does not know its infrastructure), and SOLID is how the classes
inside any of them are written, with the Dependency Inversion Principle being the one that
this rule *is*. Adopting all four vocabularies at once multiplies the nouns to be defended
without multiplying the isolation.

The question that shapes this decision is where interfaces belong. An earlier draft of
this record proposed admitting a port only where the decision log already named an
alternative implementation. That rule is wrong, and is rejected below: it confuses a
speculative justification (a second implementation *might* arrive) with a structural one
(the core must not depend outward, whether or not anything is ever swapped).

## Decision

One vocabulary, one rule, and a positional test for interfaces.

- **BCE is the package structure.** Each business component is `boundary` (adapters,
  framework annotations, transport, persistence, DTOs), `control` (decision logic, plain
  Java), `entity` (domain types, plain Java).
- **One package sits outside the three layers, deliberately: `shared/event`.** It holds
  `DomainEvent`, the seven event records and `Topics`. They are not any one component's
  entity types — they are the vocabulary the components speak to each other in — so putting
  them in the publisher's `entity` would make every subscriber depend on the publisher's
  business component, which is the coupling the layering exists to prevent. The package obeys
  the dependency rule exactly as `entity` does and carries no framework import, which a grep
  for `io.quarkus`, `jakarta`, `org.eclipse.paho`, `java.sql` and `com.fasterxml` across it
  confirms. It is an exception to the package *names*, not to the rule (ADR-0006).
- **The hexagonal rule is the dependency rule.** Quarkus, MicroProfile, MQTT, JDBC and
  HTTP types appear only in `boundary`. `control` and `entity` depend on nothing outward.
  Clean Architecture is an influence, not a second nomenclature; its rings are not
  reproduced as packages.
- **Every crossing of the core's boundary is a port**, regardless of how many
  implementations are foreseen. Anything that leaves the process — a broker, a database,
  BuildSim's REST API, the file system, the wall clock, a machine-learning runtime — is
  reached through an interface.
- **The port is owned by the core and written in the core's vocabulary.** It is declared
  in `control`, takes and returns `entity` types, and names domain operations
  (`applyDamperPosition`, `replayCase`, `raiseWorkOrder`). It is never a thin mirror of the
  adapter's API.
- **Inside the core, no interface without genuine strategy variation.** One control class
  calling another needs no abstraction between them. `OccupancySource` earns one because the
  simulator's drawn plausible day and the harness's scripted occupant step are genuinely
  different strategies — and it is the only one. Counted off the tree, the system declares
  **fifteen interfaces and only eight of them carry an abstract method**: the seven ports in
  `shared/control` (`BuildingStatePort`, `BuildingOverlayPort`, `ReadingStreamPort`,
  `EventPublisherPort`, `ResidualJournalPort`, `OccupancyPort`, `ClockPort`) and
  `OccupancySource` in `physics/control`. The other seven declare static methods only and are
  this project's substitute for a utility class rather than strategies: `Topology`
  (`ventilation/entity`), `FlowSplit` (`ventilation/control`), `Co2MassBalance` and
  `FaultSpecParser` (`physics/control`), `WorkOrderNarrative` (`supervision/control`),
  `Topics` (`shared/event`) and `EventCodec` (`shared/boundary`). So the precise claim is
  **not** that the ports are the only interfaces — `EventCodec` is an interface, and it is in
  `boundary` — but that **nothing in this system has an abstract method without an alternative
  implementation behind it**, which is the rule actually working.

**Applied where it earns its cost, and the shape is deliberately not uniform.**
`physics-simulator`, `dcv-controller`, `fault-monitor`, `supervisor` and `dashboard` all carry
`boundary` and `control`; only `physics-simulator` also carries its own `entity`, because the
other four read and write the *same* domain types and those live in `shared`
(`ventilation/entity`, `diagnosis/entity`). Four components sharing one domain model is the
reason that package is in `shared` rather than a layer each of them is missing. `co2-sensor`
(297 lines over two classes) and `damper-actuator` (236 over two) stay flat: each is a
transport adapter around one small decision, with no logic worth isolating from the wire, and
uniformity between components differing by an order of magnitude in complexity is cost without
benefit.

## Because

- **Dependency inversion is structural, not speculative.** The port is what makes the
  arrow point inward. Without it the core imports the adapter and the layering is
  decorative, however few implementations exist.
- **Every port already has two implementations.** The real adapter and the test double.
  R-1, R-4 and R-7 all require fault injection — a damper that ignores its command, readings
  stale past three intervals, a dead `co2-sensor`, a five-second connect timeout to BuildSim —
  and injecting those at a port makes them fast unit tests instead of slow, flaky container
  manipulation. `ClockPort` alone turns R-3's seven simulated days into milliseconds.
- **Ownership of the contract is what prevents the ripple.** Because the interface is
  declared in the core in domain terms, an adapter swap cannot force a change inward: the
  new adapter must satisfy the core's contract rather than the core accommodating the new
  infrastructure. An interface declared in the adapter's own shape would leave the ripple
  intact while looking like a port.
- **Keeping framework annotations out of `control`** is what allows the decision logic to
  be explained, and examined, without reference to Quarkus at all.

## Alternatives rejected

- **Ports only where an alternative implementation is already named** — the earlier draft
  of this record. Rejected because it justifies the interface by speculation about the
  future rather than by the dependency rule, and because it leaves the core importing
  infrastructure wherever no second implementation happens to be planned. It also gives
  the wrong answer at an examination: "in case we swap MQTT for Kafka" is a weaker defence
  than "so the policy logic can be tested and reasoned about without a broker".
- **Interfaces everywhere, including between control classes** — rejected as the failure
  mode that makes hexagonal projects unreadable, and by this project's standing rule
  against interfaces for single implementations with nothing to invert.
- **Full Clean Architecture nomenclature alongside BCE** — entities, use cases, interface
  adapters and frameworks as packages on top of boundary/control/entity. Rejected as two
  names for one rule.
- **Direct Quarkus wiring, no ports** — fastest to write, and viable for the device
  processes, which is why they are exempted. Rejected for the logic-bearing services
  because fault injection would then be possible only through containers.
- **The events in the publishing component's `entity`, keeping three package names and no
  exception** — the tidier structure on paper. Rejected because `ReadingObserved` is published
  by `co2-sensor` and read by `dcv-controller`, `fault-monitor` and `dashboard`, so every
  subscriber would import the publisher's business component to name the thing it subscribes
  to. A fourth package with no framework import costs one sentence of explanation; that import
  graph costs the independence the layering is for.

## Trade-off accepted

The cost of a port is not the interface file; it is the **translation** at the adapter,
and only where the schema on the far side is not ours to define — BuildSim's JSON and the
journal's SQL rows. Across transports whose payloads this project owns, the domain type is
the payload and there is nothing to translate (ADR-0006). The
asymmetry between the five structured components and the two flat device processes is
deliberate and recorded here so that it reads as a decision rather than as inconsistency.
The second cost is `shared/event`: a reader who has been told the structure is
`boundary`/`control`/`entity` finds a fourth package and has to be told why, which is a
sentence of explanation paid once in exchange for keeping the components independent of each
other. The alternative was not a cleaner tree but a worse import graph.

## Defence in one sentence

Every boundary crossing gets an interface because that is what makes the dependency point
inward and lets the decision logic be tested with a stub instead of a broker — and the
interface is declared in the core, in domain terms, because a contract owned by the
adapter would leave the ripple it was meant to prevent.
