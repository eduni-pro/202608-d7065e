# BuildSim API Reference

All endpoints are relative to `http://127.0.0.1:9090` by default.

New to the D7065E lab? Start with the [Lab Quickstart](../lab-quickstart.md), which maps the
assignment onto these endpoints.

| Document | Endpoints |
|----------|-----------|
| [Building Data](building.md) | `GET /api/building`, `GET /api/building/floors/{level}`, `GET /api/building/cross-floor-edges` |
| [Equipment](equipment.md) | `POST/GET/PUT/DELETE /api/equipment`, `POST /api/equipment/bulk`, sensors, actuators |
| [Sessions](sessions.md) | `POST/GET/DELETE /api/sessions`, viewport, highlights, occupancy, coverage, route, WebSocket |
| [Navigation Graph](graph.md) | `GET /api/graph`, `GET /api/graph/route` (Dijkstra) |
| [Simulation & Visualization](visualization.md) | Room layers, effects, entities, illumination, alerts, exact positions |
| [Entrances, Doors & Locks](doors.md) | `GET/PUT /api/doors`, walkable-route constraints |
| [Icons](icons.md) | `GET /api/icons/{name}.svg` |
| [Config](config.md) | `GET /api/config` |
| [openapi.json](openapi.json) | `GET /api/openapi.json` — machine-readable OpenAPI 3.1 description of everything above |

`GET /healthz` is intentionally outside `/api`; it returns `{"status":"ok"}`
for container and process health checks.

## Machine-readable specification

[`openapi.json`](openapi.json) and [`openapi.yaml`](openapi.yaml) describe
every endpoint on this page in OpenAPI 3.1, including request and response
schemas generated from the Go types. A running server serves the same document
at `GET /api/openapi.json`.

The pages above remain the place for prose, worked examples, and the reasoning
behind an endpoint; the specification is the place for exact shapes and for
feeding tools. Both are kept honest by tests: the documents here have
executable examples (`make docs-test`), and the specification is checked
against the server's registered routes on every `make test`.

Regenerate it after changing a handler annotation:

```bash
make openapi
```
