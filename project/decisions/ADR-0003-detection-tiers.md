# ADR-0003 — Two tiers of detection: rules hold authority, the learned model advises

**Status:** Superseded by ADR-0001 (A-06) · 2026-09-18 (was Accepted · 2026-08-31)

> Superseded 2026-09-18: the access-control detections this record tiered are gone with
> the use case (ADR-0001, A-06). Its principle — rules hold authority, a learned model only
> advises — survives in A-06 as the monitor/supervisor split (the `fault-monitor` raises
> events only and never commands; the `supervisor` acts only on a confirmed fault) and as
> the plan's rule that the CUSUM baseline comes first and a learned one-class model is an
> optional contender that must beat it on identical seeds to be kept (plan §5, T-29).

## Context

AI integration is mandatory, and REQ-05 requires anomalous access behaviour to be
detected. Sophistication earns no marks, and a learned component that can act on a
safety-relevant actuator has an unverifiable failure mode: a wrong model opens a door.

The detections themselves are not of one kind. Impossible travel is graph distance over
elapsed time; tailgating is a mismatch between entry count and swipe count; out-of-hours
and occupancy-limit breaches are threshold checks. Behavioural deviation — this badge, in
this zone, at this hour, arriving from that zone — is a joint distribution that is better
learned than hand-specified.

## Decision

Two tiers with different authority:

- **Rules hold authority.** Deterministic, verifiable, always on. They are the only thing
  the door controllers obey.
- **The learned model has none.** A DJL autoencoder scores behavioural deviation from
  reconstruction error and raises alerts for human review. It issues no lock commands,
  and the system asserts this: no lock command may carry the detector as its origin.

The detector runs on the JVM; every other service is a native image.

## Because

- A wrong model produces a false alert rather than an open door, so the failure mode of
  the AI component is bounded by construction rather than by testing (REQ-05).
- The simulator generates its own labelled-normal data, so the model can be trained and
  its precision and recall measured honestly against injected attacks, including attack
  types the rules were never written for. Rules-only versus rules-plus-model is a
  measurable comparison rather than an assertion.
- DJL trains as well as infers inside Java, so the model does not drag a second language
  and toolchain into the project.

## Alternatives rejected

- **Rules only** — simplest and fully verifiable. Rejected because it cannot catch
  unmodelled behaviour, and because it leaves the mandatory AI integration thin.
- **A model with authority over locks** — rejected: unverifiable failure mode on a
  safety-relevant actuator, and no defensible answer to "what happens when it is wrong".
- **Deep learning for the deterministic detections** — rejected as decoration; a rule
  that can be written in three lines is more explainable and cannot drift.
- **A Python service with scikit-learn** — better tooling for the model. Rejected as a
  second language and toolchain to justify for a single service.
- **An LLM incident explainer (Quarkus + LangChain4j against local Ollama)** — deferred,
  not rejected: attractive as a further advisory layer once the two tiers are measured.

## Trade-off accepted

DJL loads native engine binaries through JNI, which does not sit comfortably with
GraalVM native-image, so the detector alone stays on the JVM and carries a larger
image. This is affordable because it is one process rather than twelve, and the
asymmetry is itself a deployment argument: it is the only service that loads a machine-
learning runtime, and merging it into another service would spread that cost.

## Defence in one sentence

The learned model was given no authority because a wrong model must produce a false
alert rather than an open door, and the deterministic detections were left as rules
because a verifiable three-line check is worth more here than a network that can drift.
