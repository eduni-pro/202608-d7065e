# Demo and capture tooling

Three scripts. One starts a demo from nothing; the other two produce the UI evidence in
`../report/screenshots/` and the transcript in `../evaluation/results/T-23-demo-run.txt`. None of
them is part of the system; they exist so that the run that produced the report's figures can be
produced again.

| | |
|---|---|
| `fresh-demo.sh` | Tears the stack down, deletes every byte under `../storage/`, rebuilds the empty directory skeleton out of what git tracks, and brings the ten containers back on a newly generated `REAL_EPOCH` with `--force-recreate`. Then verifies that the emptied `PGDATA` re-ran `init.sql` and that the dashboard sees every room. No arguments; honours `FAULT_SPEC`, `ZONE_COUNT`, `BUILDSIM_PORT` and `DASHBOARD_PORT`, so the RUNBOOK section 6 variants work through it. It does **not** build. |
| `capture-demo-run.sh` | Brings up the demo profile, captures five moments on both UIs, polls `/api/state` at 1 Hz, records the bus and the container logs, exports the journal, and tears down at +313 s. Writes everything under `$CAPTURE_ROOT` (default `$TMPDIR/d7065e-demo-capture`). |
| `screenshot.js` | Screenshots one URL through Chrome's DevTools Protocol. `node screenshot.js <url> <out.png> <width> <height> <settleMs>`, with `CDP_PORT` naming an already-running headless Chrome. |

## Why `fresh-demo.sh` is not `make down && make up`

Because `down -v` removes *named volumes*, and this compose project declares none. The journal's
whole PostgreSQL cluster, the broker's spool and the two ADR-0012 state documents are bind mounts
under `../storage/`, so they outlive a teardown and the next run starts on top of them. Two of the
three matter:

- the **journal** carries every earlier run's rows into the next run's queries, which is why
  `capture-demo-run.sh` scopes its assertions by `received_at` against its own start instant;
- the two **`state.json`** documents carry the latch and the pinned damper beliefs. A fresh demo
  that opens with A115 already confirmed is the shape of that bug.

The broker's spool is wiped for completeness rather than for effect: clean sessions, randomised
client ids and nothing retained mean it has nothing to restore.

## Why the capture scripts look like this

Four things about capturing these two UIs cost a run each to learn, and all four are the reason
the scripts are shaped as they are rather than being three lines of `curl`:

- **A cold Chrome `--user-data-dir` hangs for minutes on macOS**; a warm one starts in about a
  second. The script therefore keeps two long-lived profiles under `$CAPTURE_ROOT` and reuses them,
  one per UI, so the two halves of a dual-UI moment can be captured in parallel.
- **`--virtual-time-budget` never completes against real network requests.** It is the obvious flag
  for "wait for the page to settle" and it will hang forever here. `screenshot.js` waits explicitly
  instead.
- **The 3D viewer paints its scene long after the load event**, behind a splash overlay that
  reports its own progress, so `--screenshot` alone captures the splash. `screenshot.js` polls the
  overlay away and only then shoots.
- **`screencapture(1)` works only while the display is awake.** Unattended it returns pure black,
  so it cannot be driven from a timer. This is why the capture goes through the browser rather than
  the window server.

One shell trap is worth repeating because it is in this file twice: **zsh does not word-split an
unquoted parameter**, so compose flags cannot live in a variable (`$DC exec …` becomes one command
name), and a bare `wait` waits for *every* child including the pollers that never exit. Both are
commented where they bite.
