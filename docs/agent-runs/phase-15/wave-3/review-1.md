# Wave 3 final review 1: code-reviewer (Opus), received 2026-11-18 (summary as received)

Verdict: REQUEST CHANGES. No HIGH finding: no account ID, no secret value, no untraced number.

Passed: numbers in `experiments/RESULTS.md` and `docs/experiments.md` checked against D `derived-latency-v2`, E `derived-latency-modes-v2`, F2 `derived-verdict`, C `derived-queue` and `derived-metrics`, B `derived/derived.json`, D `derived-metrics`, the D and C autoscaling activity files and every `run-record.env`; C own counts with the F2 overlap, B caveats, F1 INVALID, E mixed provenance, billed cost PENDING, burn x time labels, P15-47 limitations, every prompt.md row mapped, no stale PENDING RE-DERIVE; release-record digests and counts match `session-tonight/evidence.md`; `experiments/results/**` has only `000000000000` (12-digit hits elsewhere are UUID tails in C's `worker-messages.csv` message_id column, and the documented `123456789012` placeholder); SHA256SUMS in all 7 run dirs; `service_log.py:24-25` only the two aliases; harness block `tests/scripts/run.sh:13156-13347` traced to pass with the fix, pins HEAD aliases, writes no `__pycache__`; ownership by author lines.

Findings:
- M1 (MEDIUM; infra-engineer, deploy-engineer; human ruling): `SESSION2.md:203-207` (describe-secret, exits 0 on ABSENT) and runbook amendment `:670-672` (list-secret-version-ids, SV.1 fails closed) give two commands for one gate; stale UNVERIFIED hedges (`:659`, `:682`); `SESSION2.md:215-218` gives a paste-ready `update-service --force-new-deployment` while the runbook (`:688`) and P15-45 do not approve it; stale "no runbook step fills them" and `main.tf:55-68` cites.
- M2 (MEDIUM; experiment-engineer, then documentation-engineer): requirements 18 (latency alarm) and 23 (Terraform dashboard) marked NOT VERIFIED though `modules/runtime/main.tf:659-672`, `variables.tf:266-267`, `scaling_dashboard.tf:203` and `deploy-tn-s2-a2.md:34,48` exist (`RESULTS.md:54,59,253,300`; `LIMITATIONS.md:44,45`; `docs/experiments.md:140`). Step Functions Retry correctly NOT SHOWN.
- L1: six UUID tails, not four (`LIMITATIONS.md:50`, `results/README.md:3`).
- L2: D mode readable, `"mode": "none"` (`requests.jsonl:1`), not UNVERIFIED (`RESULTS.md:148`, `LIMITATIONS.md:36`).
- L3: C window 01:35:53Z starts before F2 (01:37:40Z) and spans it (`RESULTS.md:118`, `LIMITATIONS.md:27`).
- L4: rewrite, not tag, the superseded "E first vector" sentences (`RESULTS.md:204,244`, `LIMITATIONS.md:43`).
- L5: requirement 4 "MET by design, NOT VERIFIED" (`RESULTS.md:40`).
- L6: experiment READMEs still name v1 folders (`e-retrieval-modes/README.md:31,36`, `d-query-load/README.md:70`) and PENDING-WO-8 (`d-query-load/README.md:16`).
- L7: `results/README.md:141` should note the human-side removal, path rewrite and sums afterwards.
- L8: F1 README reads numbers from raw files (labeled); human acknowledgment.
- L9: `decisions.md:53` P15-11 after P15-47; ordering only (pre-existing).

Pending (not verified by the reviewer): harness run (expected 1935/0); run.sh 193 insertions 0 deletions; `shasum -c` per run dir; human `sbt clean check`; billed cost.

Repair round 1 routed 2026-11-18: M1 to infra-engineer and deploy-engineer (redeploy pending human ruling); M2 and L1-L8 to experiment-engineer; M2 wording to documentation-engineer.
