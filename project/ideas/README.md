# Idea catalogue

[`twelve-closed-loops.html`](twelve-closed-loops.html) — twenty-two candidate project loops for
D7065E, each checked against BuildSim's API, the proposal guide's seven questions and the
report guide's grading criteria. Section 07 is a full walkthrough of **A-06** (ventilation
fault detection): the two nested loops, the residual, the fault-separation logic, the monitor
state machine and the container split.

Open the file directly in a browser. Two caveats: without a network the web fonts fall back to
system faces, and the five Mermaid diagrams read as their source text rather than rendered
pictures. The second is a property of this file rather than of how it is opened — it carries no
Mermaid renderer, so serving it over HTTP does not change anything. The diagram sources are short
and readable as they stand. Nothing downstream depends on them either way: the report's own
figures are kept separately in `../diagrams/` as D2 sources with committed SVGs beside them.

The catalogue is a decision input, not a decision. Once a loop is chosen, the reasoning for
choosing it belongs in [`../decisions/ADR-0001-use-case.md`](../decisions/ADR-0001-use-case.md).
