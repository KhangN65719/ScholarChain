# W2-H: test-engineer report

Author: test-engineer. Received by the orchestrator on 2026-11-18 and preserved as received (includes the two items routed from W2-I by SendMessage).

---

**Status: COMPLETE for the edits. Nothing was run.** The guard refuses `bash tests/scripts/run.sh`; no sbt. The human runs the harness.

**Edits** (`tests/scripts/run.sh`, `tests/scripts/README.md`)

| Item | Case before | Case after |
|---|---|---|
| Committed manifest count (`run.sh:1816-1817`) | `8.committed-manifest.count`, `[ "$n_mf" -eq 6 ]`, "expected 6 committed manifests" | same id, `-eq 8`, "expected 8 committed manifests, found $n_mf" |
| JSON-copy check (`run.sh:12350-12352`) | `15.experiment.manifests.each-of-the-six-manifests-has-a-json-copy-that-says-the-same-as-its-key-value-file`, `= "6"` | `15.experiment.manifests.each-of-the-eight-manifests-has-a-json-copy-that-says-the-same-as-its-key-value-file`, `= "8"`, message "expected 8" |
| Unknown-id refusal (`run.sh:1725`) | `8.refuse.unknown-experiment-id` with `EXPERIMENT_ID G` | same id with `EXPERIMENT_ID Z`; `unknown EXPERIMENT_ID` assertion kept |

- Not weakened: both count checks are equality checks, so 7 or 9 manifests fail. The JSON case still compares every manifest with its copy through `w15-mf-compare.py`. Case total unchanged.
- Untouched: `8.committed-manifest.G` and `.H`.
- README: added a "Phase 16 Wave 2, W2-H" paragraph at the end recording the count change, the renamed id, the `Z` input and the `CHECK_SKIPPED` finding. The Phase 14 "six committed manifests" sentence is left as history.

**`CHECK_SKIPPED entry-point` in `8.committed-manifest.{G,H}`: still printed.** `setup_repo` (`run.sh:445-474`) builds the temporary repo from scratch: it copies `experiments/manifests/*.manifest` and writes a stand-in `experiments/harness/run.sh`, and copies no experiment directory. So `experiments/g-retrieval/run.sh` and `experiments/h-answers/run.sh` are never tracked in the temp repo, committed or not. The A to F cases rely on the same behaviour. No fix needed.

**Could not verify**
- No case run; all of the above is unobserved.
- `8.committed-manifest.G` and `.H` pass only once infra-engineer's `run-experiment.sh` change is in (not read by test-engineer).
- They also depend on the G and H manifests being valid under that script (region, caps, spend ceiling, key set): unchecked.
