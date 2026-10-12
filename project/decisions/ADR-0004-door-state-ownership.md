# ADR-0004 — Ownership of BuildSim's door collection

**Status:** Superseded by ADR-0001 (A-06) · 2026-09-18 (was Proposed · 2026-08-31)

> Superseded 2026-09-18 with no successor: BuildSim's door collection is not written by
> A-06 because doors are out of scope (ADR-0001). The single-writer observation about
> `PUT /api/doors` stays on record for any future use of that endpoint.

## Context

BuildSim exposes doors as a **collection replacement**, not as individually addressable
resources. `PUT /api/doors` is documented as "Replace doors" and hands the whole slice to
`SetDoors`, which swaps the stored collection; there is no per-door endpoint. Verified
against `pkg/server/handlers/visualization.go` and `pkg/store/memory.go` in BuildSim at
commit `c74ec3c`.

Six door controllers, each responsible for one lock, therefore cannot write their own
door to BuildSim: each write carries every door, so two concurrent writers silently
discard each other's state. This is a property of the given interface, not a design
choice, and something must own the collection.

## Options

1. **A door gateway owns the collection.** Controllers publish their commanded lock state;
   one process merges the six into a snapshot and performs the single `PUT`.
2. **The access service writes directly.** Fewer processes, but policy becomes coupled to
   rendering, and the actuator stops being an independent process — against the gate
   requirement that each actuator run on its own.
3. **Controllers each write the full collection.** Rejected outright: last write wins and
   silently reverts the other five doors. It is the race this ADR exists to prevent.

## Recommendation

Option 1. The gateway is a genuine architectural element rather than a workaround: it is
the single writer for a resource that has no per-item semantics, and it is the place
where snapshot-shaped interfaces are reconciled with per-device processes.

## Trade-off accepted

One more process, and a single point of failure in front of all six locks. This is
tolerable only if a gateway failure does not leave the locks unmanaged, which makes the
degradation policy (still to be decided) a precondition rather than a detail: the
authoritative lock state must live with the controllers, so the gateway's death costs
visibility in BuildSim, not control of the doors.

## Open question for the decision it depends on

If controllers hold authoritative state and the gateway only publishes it, what does the
viewer show while the gateway is down, and is a stale door in the 3D view acceptable
during a demonstration? Resolve alongside the degradation policy.

## Defence in one sentence

BuildSim replaces the whole door collection on every write, so six independent
controllers would clobber one another; one gateway owns the collection and the
controllers keep authority over their own lock.
