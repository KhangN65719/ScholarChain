# Committed results: what goes here and how to copy it

Author: experiment-engineer (Phase 15, P15-46 step 3). The copy and the P15-47 re-derive below were run by the human (the experiment-engineer's shell cannot copy files or create directories, project guard); the files are now in this directory. The two command blocks are kept as the record: do not rerun the copy block. The strict scan reported by the human found only `000000000000` and six UUID tails (message ids, not account ids; file lines 312, 327, 693, 1542, 1859 and 1879, all in the `message_id` column) in `c/exp-C-20261118T014553Z-38421c6f/derived-queue/worker-messages.csv`. The inputs are the redacted copies in `/Users/winnguyen/scholarchain-data/results-copy/`, already derived by the human (logs `phase15-wave3-derive-{d,e,fc}.log` and `phase15-tn-redact.log`). No account ID may appear in any copied file (the scan at the end of the command checks it).

The results summary is `experiments/RESULTS.md`; the limitations are `experiments/LIMITATIONS.md`.

## Layout after the copy

One directory per run: `experiments/results/<a|b|c|d|e|f>/<run id>/`.

| Experiment | Run id | Directory | What it holds |
|---|---|---|---|
| A | `exp-A-20261117T161757Z-5c69ba90` | `a/` | `README.md` only (written here): A has no derived files; NOT DERIVED |
| B | `exp-B-20261117T185928Z-5c69ba90` | `b/` | `derived/` (8 files), `run-record.env`, small `collected/` files, `raw/` control files, `RAW-SHA256SUMS` |
| C | `exp-C-20261118T014553Z-38421c6f` | `c/` | `derived-queue/`, `derived-metrics/`, `run-record.env`, small `collected/` and `raw/` files |
| D | `exp-D-20261118T002157Z-38421c6f` | `d/` | `derived-latency-v2/` (committed; re-derived by the human after the P15-47 stage-alias fix: graph and vector stage rows in `stage-latency-by-level.csv`; v1 `derived-latency/` is in `results-copy/` only, as history), `derived-metrics/`, `derived-requests/`, `run-record.env`, small `collected/` and `raw/` files |
| E | `exp-E-20261118T012347Z-38421c6f` | `e/` | `derived-latency-modes-v2/` (committed; re-derived by the human after P15-47: graph and vector stage rows in `stage-latency-by-mode.csv`; v1 `derived-latency-modes/` is in `results-copy/` only, as history), `derived-requests/`, `run-record.env`, small `collected/` and `raw/` files (no derived CloudWatch metrics for E: NOT DERIVED) |
| F2 (the F result) | `exp-F-20261118T013740Z-38421c6f` | `f/` | `derived-verdict/`, `derived-queue/`, `derived-metrics/`, `run-record.env`, small `collected/` and `raw/` files |
| F1 (INVALID) | `exp-F-20261118T011431Z-38421c6f` | `f/` | `README.md` (written here, states INVALID), `run-record.env`, `raw/` poll, events, window, enqueue files. Not derived |

## Deliberately not copied

- Raw logs and large collected files: `collected/loadclient-logs.json` (5.4 MB in D, 0.77 MB in E), `collected/service-logs.json` (8.3 MB in D, 1.2 MB in E), `collected/worker-logs.json` (0.88 MB in C, 23 KB in F2), `cloudwatch-list-metrics-*.json`, `entrypoint.log`, `pipeline-run.log`, `poll.log`, `raw/enqueue/messages.jsonl` and `send-log.jsonl`, `raw/load-client-*.json` (task ARNs), and the `metrics/` directories of per-metric listings. They stay in the immutable run directories `/Users/winnguyen/scholarchain-data/experiments/runs/<run id>/` and in `results-copy/`.
- `derived-requests/requests.jsonl` of D (2.8 MB) and E (0.4 MB) IS copied: it is the per-request table the derive scripts read, so the latency tables can be reproduced without the raw logs. Drop the two files from the command if the repository size matters.

## SHA256SUMS: read this

The `SHA256SUMS` files inside `results-copy/` hash the RAW files, not the redacted copies. They are copied here under the name `RAW-SHA256SUMS` (top level and `collected/` where present) as provenance of the raw files in the immutable run directories. They do NOT verify the redacted copies in this repository. The command regenerates a `SHA256SUMS` for the copies (sha256 of every committed file in the run directory, relative paths, sorted). A redacted copy will not match its raw counterpart by design.

## Paste-ready command (human, `!` prefix, zsh)

DONE, DO NOT RERUN. This copy block was run once (log `phase15-wave3-results-copy.log`). It copies the v1 `derived-latency` and `derived-latency-modes` folders, which were replaced by the v2 folders (P15-47, section below); rerunning it would put the v1 folders back and overwrite the regenerated `SHA256SUMS`. It is kept as the record of what was copied.

```
set -o pipefail
SRC="/Users/winnguyen/scholarchain-data/results-copy"
DST="/Users/winnguyen/ScholarChain/experiments/results"
{
fail=0
cpf() { d="${DST}/${3}/$(dirname "${2}")"; mkdir -p "${d}" && cp "${SRC}/${1}/${2}" "${d}/" || { echo "COPY_FAILED ${1}/${2}"; fail=$((fail+1)); }; }
cpd() { d="${DST}/${3}/${2}"; mkdir -p "${d}" && cp -R "${SRC}/${1}/${2}/." "${d}/" || { echo "COPY_FAILED ${1}/${2}/"; fail=$((fail+1)); }; }
cprn() { d="${DST}/${3}/$(dirname "${4}")"; mkdir -p "${d}" && cp "${SRC}/${1}/${2}" "${DST}/${3}/${4}" || { echo "COPY_FAILED ${1}/${2}"; fail=$((fail+1)); }; }

B="b/exp-B-20261117T185928Z-5c69ba90"
cpf p15-ab-b run-record.env "${B}"
cpd p15-ab-b derived "${B}"
for f in COLLECTED.txt CALL_STATUS.txt emr-managed-scaling-policy.json emr-describe-cluster.json emr-list-instances.json emr-list-steps.json cloudwatch-metric-data.json metric-queries.json sfn-execution.json sfn-history.json; do cpf p15-ab-b "collected/${f}" "${B}"; done
for f in window.json events.jsonl pipeline-input.json pipeline-input.sha256 pipeline/pipeline-20261117T185932Z-medium.record.env; do cpf p15-ab-b "raw/${f}" "${B}"; done
cprn p15-ab-b SHA256SUMS "${B}" RAW-SHA256SUMS
cprn p15-ab-b collected/SHA256SUMS "${B}" collected/RAW-SHA256SUMS

C="c/exp-C-20261118T014553Z-38421c6f"
cpf p15-tn-c run-record.env "${C}"
cpd p15-tn-c derived-queue "${C}"
cpd p15-tn-c derived-metrics "${C}"
for f in app-autoscaling-activities.json app-autoscaling-policies.json app-autoscaling-targets.json CALL_STATUS.txt COLLECTED.txt cloudwatch-metric-data.json ecs-describe-services.json metric-queries.json sqs-dlq-attributes.json sqs-queue-attributes.json; do cpf p15-tn-c "collected/${f}" "${C}"; done
for f in window.json events.jsonl poll.jsonl enqueue/summary.json; do cpf p15-tn-c "raw/${f}" "${C}"; done
cprn p15-tn-c SHA256SUMS "${C}" RAW-SHA256SUMS
cprn p15-tn-c collected/SHA256SUMS "${C}" collected/RAW-SHA256SUMS

D="d/exp-D-20261118T002157Z-38421c6f"
cpf p15-tn-d run-record.env "${D}"
cpd p15-tn-d derived-latency "${D}"
cpd p15-tn-d derived-metrics "${D}"
cpd p15-tn-d derived-requests "${D}"
for f in app-autoscaling-activities.json app-autoscaling-policies.json app-autoscaling-targets.json CALL_STATUS.txt COLLECTED.txt cloudwatch-metric-data.json ecs-describe-services.json metric-queries.json; do cpf p15-tn-d "collected/${f}" "${D}"; done
for f in window.json events.jsonl; do cpf p15-tn-d "raw/${f}" "${D}"; done
cprn p15-tn-d SHA256SUMS "${D}" RAW-SHA256SUMS
cprn p15-tn-d collected/SHA256SUMS "${D}" collected/RAW-SHA256SUMS

E="e/exp-E-20261118T012347Z-38421c6f"
cpf p15-tn-e run-record.env "${E}"
cpd p15-tn-e derived-latency-modes "${E}"
cpd p15-tn-e derived-requests "${E}"
for f in app-autoscaling-activities.json app-autoscaling-policies.json app-autoscaling-targets.json CALL_STATUS.txt COLLECTED.txt cloudwatch-metric-data.json ecs-describe-services.json metric-queries.json; do cpf p15-tn-e "collected/${f}" "${E}"; done
for f in window.json events.jsonl; do cpf p15-tn-e "raw/${f}" "${E}"; done
cprn p15-tn-e SHA256SUMS "${E}" RAW-SHA256SUMS
cprn p15-tn-e collected/SHA256SUMS "${E}" collected/RAW-SHA256SUMS

F2="f/exp-F-20261118T013740Z-38421c6f"
cpf p15-tn-f2 run-record.env "${F2}"
cpd p15-tn-f2 derived-verdict "${F2}"
cpd p15-tn-f2 derived-queue "${F2}"
cpd p15-tn-f2 derived-metrics "${F2}"
for f in CALL_STATUS.txt COLLECTED.txt cloudwatch-metric-data.json ecs-describe-services.json metric-queries.json sqs-dlq-attributes.json sqs-queue-attributes.json ledger-item.json duplicate-vector.json; do cpf p15-tn-f2 "collected/${f}" "${F2}"; done
for f in window.json events.jsonl poll.jsonl enqueue/summary.json; do cpf p15-tn-f2 "raw/${f}" "${F2}"; done
cprn p15-tn-f2 SHA256SUMS "${F2}" RAW-SHA256SUMS
cprn p15-tn-f2 collected/SHA256SUMS "${F2}" collected/RAW-SHA256SUMS

F1="f/exp-F-20261118T011431Z-38421c6f"
cpf p15-tn-f1 run-record.env "${F1}"
for f in window.json events.jsonl poll.jsonl enqueue.log enqueue/summary.json; do cpf p15-tn-f1 "raw/${f}" "${F1}"; done
cprn p15-tn-f1 SHA256SUMS "${F1}" RAW-SHA256SUMS

for r in a/exp-A-20261117T161757Z-5c69ba90 "${B}" "${C}" "${D}" "${E}" "${F2}" "${F1}"; do (cd "${DST}/${r}" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 > SHA256SUMS) || { echo "SUMS_FAILED ${r}"; fail=$((fail+1)); }; done

echo "REDACTION_SCAN (any line below is a 12-digit number other than 000000000000; review each):"
grep -rEn '(^|[^0-9A-Za-z])[0-9]{12}([^0-9A-Za-z]|$)' "${DST}" | grep -v '000000000000' | head -n 20
echo "COPY_FAILURES=${fail}"
du -sh "${DST}"
} 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-wave3-results-copy.log" | tail -n 60; echo "exit: $?"
```

Expected: no `COPY_FAILED` or `SUMS_FAILED` line, no line under `REDACTION_SCAN` (a hit may be a hash that happens to hold a 12-digit run; read it before committing), `COPY_FAILURES=0`, then the size, then `exit: 0`. The scan prints the file path of any hit, not the whole file. If it prints a real account ID, do not commit: replace it with `000000000000` in the copy and tell the orchestrator.

The command refuses nothing and overwrites on a repeat (`cp`); run it once into the empty directories. It reads only `results-copy/`; it makes no AWS call.

## Re-derive D and E stage latency (P15-47, human, `!` prefix, zsh)

DONE (record, do not rerun). The human ran the re-derive (log `/Users/winnguyen/scholarchain-data/logs/phase15-p15-47-rederive.log`). The log shows both `DERIVE_COMPLETE` lines (`requests=7428 groups=4`, `requests=1053 groups=3`), the 8 D and 6 E stage rows, and `REMOVE_FAILED` with `FAILURES=1` for the v1 removal in the sandbox that ran it. The human then removed the v1 folders from `experiments/results`, regenerated `SHA256SUMS` in every `experiments/results` run directory and rewrote the VM paths in the SUMMARY and derived files to `/Users/winnguyen/...`. I checked that only the v2 folders are present in the D and E run directories and that the D `SHA256SUMS` lists them; I did not run `shasum -c`. The results are cited in `experiments/RESULTS.md` section 3.D and 3.E and `LIMITATIONS.md` L16. The command below is the original instruction, kept for the record.

Why: the service logs name the stages `graph-retrieval` and `vector-retrieval`; `STAGE_ALIASES` in `load-tests/lib/service_log.py` did not list them, so the first derive wrote `measured 0, skipped 0` for the `graph` and `vector` rows. The two aliases are now added. Do not rerun the copy command above after this step: it would copy the old `derived-latency` and `derived-latency-modes` back. The old folders stay in `results-copy/` as history. No AWS call; it reads `results-copy/` only. Derived files are written once, so the v2 folders must not exist yet.

```
set -o pipefail
SRC="/Users/winnguyen/scholarchain-data/results-copy"
DST="/Users/winnguyen/ScholarChain/experiments/results"
D="d/exp-D-20261118T002157Z-38421c6f"
E="e/exp-E-20261118T012347Z-38421c6f"
{
fail=0
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py derive --requests "${SRC}/p15-tn-d/derived-requests/requests.jsonl" --events "${SRC}/p15-tn-d/derived-requests/events.jsonl" --service-logs "${SRC}/p15-tn-d/collected/service-logs.json" --group-by level --out-dir "${SRC}/p15-tn-d/derived-latency-v2" --title "Experiment D: latency by level" || { echo "DERIVE_FAILED D"; fail=$((fail+1)); }
python3 -I /Users/winnguyen/ScholarChain/load-tests/query-load/derive_latency.py derive --requests "${SRC}/p15-tn-e/derived-requests/requests.jsonl" --events "${SRC}/p15-tn-e/derived-requests/events.jsonl" --service-logs "${SRC}/p15-tn-e/collected/service-logs.json" --group-by mode --out-dir "${SRC}/p15-tn-e/derived-latency-modes-v2" --title "Experiment E: latency by mode" || { echo "DERIVE_FAILED E"; fail=$((fail+1)); }
if [ "${fail}" -eq 0 ]; then
mkdir -p "${DST}/${D}/derived-latency-v2" "${DST}/${E}/derived-latency-modes-v2" && cp -R "${SRC}/p15-tn-d/derived-latency-v2/." "${DST}/${D}/derived-latency-v2/" && cp -R "${SRC}/p15-tn-e/derived-latency-modes-v2/." "${DST}/${E}/derived-latency-modes-v2/" || { echo "COPY_FAILED"; fail=$((fail+1)); }
fi
if [ "${fail}" -eq 0 ]; then
rm -r "${DST}/${D}/derived-latency" "${DST}/${E}/derived-latency-modes" || { echo "REMOVE_FAILED"; fail=$((fail+1)); }
for r in "${D}" "${E}"; do (cd "${DST}/${r}" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 > SHA256SUMS) || { echo "SUMS_FAILED ${r}"; fail=$((fail+1)); }; done
fi
echo "STAGE_ROWS D (group,stage,measured,skipped,p50,p95,p99,max,missing):"
grep -E ',(graph|vector),' "${DST}/${D}/derived-latency-v2/stage-latency-by-level.csv"
echo "STAGE_ROWS E:"
grep -E ',(graph|vector),' "${DST}/${E}/derived-latency-modes-v2/stage-latency-by-mode.csv"
echo "REDACTION_SCAN (expect no line):"
grep -rEn '(^|[^0-9A-Za-z])[0-9]{12}([^0-9A-Za-z]|$)' "${DST}/${D}/derived-latency-v2" "${DST}/${E}/derived-latency-modes-v2" | grep -v '000000000000' | head -n 5
echo "FAILURES=${fail}"
} 2>&1 | tee "/Users/winnguyen/scholarchain-data/logs/phase15-p15-47-rederive.log" | tail -n 40; echo "exit: $?"
```

Expected: two `DERIVE_COMPLETE` lines (`requests=7428 groups=4` and `requests=1053 groups=3`), eight D rows (graph and vector at four levels) and six E rows (three modes), `measured` above 0 for graph in every D level and E mode and for vector in E vector and fused modes, `skipped` above 0 for the vector rows of E graph mode (the observed D vector rows also had `skipped` above 0 with `measured` above 0, which this expectation had not foreseen), no `REDACTION_SCAN` line, `FAILURES=0`, `exit: 0`. What happened instead: the log records `REMOVE_FAILED` and `FAILURES=1` (the removal of the v1 folders failed inside the sandbox where the block ran). The human side then did, afterwards, the removal of the v1 folders, the rewrite of the VM paths and the regeneration of `SHA256SUMS`; I did not run or check these, and did not run `shasum -c`. The run directories contain no account ID: the service-log input is read from the already redacted copy. After it, `cd <run dir> && shasum -a 256 -c SHA256SUMS` must pass for D and E. If a v2 folder already exists in `results-copy/`, the derive refuses; move it aside, do not delete the old v1 folders.

## After the copy

- The `SHA256SUMS` of a run directory is regenerated and committed with it; verify with `cd <run dir> && shasum -a 256 -c SHA256SUMS`.
- `experiments/RESULTS.md` and `experiments/LIMITATIONS.md` cite these paths. If a file is dropped from the command, edit the citation and tell the experiment-engineer.
