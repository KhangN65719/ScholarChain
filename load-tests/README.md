# Load tests

SUPERSEDED. The earlier Experiment D design that stood here (levels 1, 4, 8 and 16 clients, tier M,
3,500 requests, "design only; no generator exists") is withdrawn. Its levels were forced by the
provisional tier M caps; the human ratified the tier S caps in decision P15-1 (90 minutes, 64 clients,
20,000 tasks), and the generator now exists.

Current design, generator and commands: [`load-tests/query-load/README.md`](query-load/README.md).

| Item | Old (superseded) | Current |
|---|---|---|
| Levels | 1, 4, 8, 16 clients | 1, 8, 24, 48 clients, 480 s each, then 900 s with no client |
| Tier | M | S (`scholarchain-s-20261107-10dd7f34121b`) |
| Request bound | 3,500 | 19,521 by construction, manifest `MAX_TASKS` 20,000 |
| Entry point | `load-tests/d-query-load/run.sh` (never created) | `experiments/d-query-load/run.sh` |
| Manifest | `MAX_CONCURRENCY=16`, `MAX_MINUTES=90` | `MAX_CONCURRENCY=48`, `MAX_MINUTES=70` in `experiments/manifests/experiment-d-query-load.manifest` |

Shared helpers are in `load-tests/lib/`. The approved workload for any live run is the manifest and
approval record, not this file.
