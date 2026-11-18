#!/usr/bin/env bash
# Experiment G entry point: retrieval quality in graph, vector and fused mode, on the laptop.
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-g-retrieval.manifest. No tuning, no retry.
#
# It runs ONE sbt command, the Scala entry point
# scholarchain.evaluation.retrieval.RetrievalEval (experiments/evaluation), with the stub answer
# model (no Haiku call). It reads A's local export from disk (graph port: InMemoryNeptune, no
# Neptune, ECS or ALB) and uses Titan, S3 Vectors and S3 GetObject in us-east-2 for the vector
# side. Raw output goes under SCHOLARCHAIN_RUN_DIR (the run's raw/ directory): events.jsonl and
# retrieval/ (records.jsonl with its RAW-SHA256SUMS written first, then summary.json,
# run-manifest.json and the directory's own SHA256SUMS).
#
# Human-supplied environment (paths and names only, no secret; the environment is NOT bound by
# the approval digest, the committed numbers below are, because they are part of this script):
#   SCHOLARCHAIN_G_EXPORT_DIR      A's local Spark export (has export-manifest.json and _SUCCESS)
#   SCHOLARCHAIN_G_QUESTIONS       questions-v1.jsonl (BenchmarkGen output)
#   SCHOLARCHAIN_G_LABELS          labels-v1.jsonl (BenchmarkGen output)
#   SCHOLARCHAIN_G_DATA_BUCKET     the data bucket that holds the semantic documents
#   SCHOLARCHAIN_G_VECTOR_BUCKET   the S3 Vectors bucket
# AWS credentials come from the profile in AWS_PROFILE (the SDK default chain), never from here.
# Committed limits (must stay within the manifest; a change is a new script digest):
#   Titan call cap 2000, USD stop 0.25 (= SPEND_CEILING_USD), repeats 2,
#   Titan rate 0.02 USD per million tokens (UNVERIFIED, the human reads the pricing page).
#   USD figures are estimates, not bounds (Titan: characters over four).
# Wall clock: the in-process stop is an ABSOLUTE deadline, computed here from the start of this
#   script, MAX_MINUTES minus MARGIN_MINUTES (10) after it. sbt start, compile and the graph load
#   are inside that window (their duration is UNMEASURED until the first live run). The deadline is
#   checked between questions, so the run can pass it by one question: the longest is 3 modes with
#   at most 4 Titan embeds at the 60 s attempt deadline (no SDK retry) plus S3 reads, about 5
#   minutes, and the closing writes take under a minute, so 10 minutes is the margin. If a
#   dependency hangs past the margin, run-experiment.sh kills the process at MAX_MINUTES; the raw
#   records.jsonl is appended and flushed as each question finishes, so the records made so far
#   stay (without RAW-SHA256SUMS, summary and seal; the run record's SHA256SUMS still covers them).
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"

trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; exit 143' TERM INT

TITAN_MAX_CALLS=2000
USD_CEILING=0.25
TITAN_USD_PER_MTOK=0.02
REPEATS=2
MARGIN_MINUTES=10

ENTRY_START_EPOCH="$(date +%s)"
exp_init G

for v in SCHOLARCHAIN_G_EXPORT_DIR SCHOLARCHAIN_G_QUESTIONS SCHOLARCHAIN_G_LABELS \
  SCHOLARCHAIN_G_DATA_BUCKET SCHOLARCHAIN_G_VECTOR_BUCKET; do
  [[ -n "${!v:-}" ]] || exp_die "$v is not set"
done
[[ "$SCHOLARCHAIN_G_EXPORT_DIR" == /* && -d "$SCHOLARCHAIN_G_EXPORT_DIR" ]] || exp_die "SCHOLARCHAIN_G_EXPORT_DIR must be an existing absolute directory"
[[ "$SCHOLARCHAIN_G_QUESTIONS" == /* && -f "$SCHOLARCHAIN_G_QUESTIONS" ]] || exp_die "SCHOLARCHAIN_G_QUESTIONS must be an existing absolute file"
[[ "$SCHOLARCHAIN_G_LABELS" == /* && -f "$SCHOLARCHAIN_G_LABELS" ]] || exp_die "SCHOLARCHAIN_G_LABELS must be an existing absolute file"
[[ "$SCHOLARCHAIN_MAX_TASKS" -ge 336 ]] || exp_die "MAX_TASKS $SCHOLARCHAIN_MAX_TASKS is below the 336 workflow calls of this experiment (56 questions x 3 modes x 2 repeats)"
ENTRY_MINUTES=$((SCHOLARCHAIN_MAX_MINUTES - MARGIN_MINUTES))
[[ "$ENTRY_MINUTES" -ge 20 ]] || exp_die "MAX_MINUTES $SCHOLARCHAIN_MAX_MINUTES minus the $MARGIN_MINUTES minute margin leaves under 20 minutes for the run"
DEADLINE_EPOCH_SECONDS=$((ENTRY_START_EPOCH + ENTRY_MINUTES * 60))

# The input digests go in the ledger first, so a changed input is visible in the raw record.
exp_event INPUT_QUESTIONS "sha256=$(exp_sha256 "$SCHOLARCHAIN_G_QUESTIONS")"
exp_event INPUT_LABELS "sha256=$(exp_sha256 "$SCHOLARCHAIN_G_LABELS")"
exp_event ENTRY_LIMITS "titan_max_calls=$TITAN_MAX_CALLS usd_ceiling=$USD_CEILING repeats=$REPEATS entry_minutes=$ENTRY_MINUTES margin_minutes=$MARGIN_MINUTES deadline_epoch_seconds=$DEADLINE_EPOCH_SECONDS"

cd "$EXP_REPO_ROOT"
rc=0
sbt "evaluation/runMain scholarchain.evaluation.retrieval.RetrievalEval \
--questions $SCHOLARCHAIN_G_QUESTIONS \
--labels $SCHOLARCHAIN_G_LABELS \
--out-dir $SCHOLARCHAIN_RUN_DIR \
--run-id retrieval \
--repeats $REPEATS \
--deadline-epoch-seconds $DEADLINE_EPOCH_SECONDS \
--export-dir $SCHOLARCHAIN_G_EXPORT_DIR \
--region $SCHOLARCHAIN_REGION \
--data-bucket $SCHOLARCHAIN_G_DATA_BUCKET \
--vector-bucket $SCHOLARCHAIN_G_VECTOR_BUCKET \
--titan-max-calls $TITAN_MAX_CALLS \
--usd-ceiling $USD_CEILING \
--titan-usd-per-mtok $TITAN_USD_PER_MTOK" || rc=$?
exp_event ENTRY_END "exit=$rc"
exit "$rc"
