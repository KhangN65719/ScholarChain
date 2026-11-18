#!/usr/bin/env bash
# Experiment H entry point: answer quality in fused mode with Haiku 4.5, on the laptop.
# Started once by scripts/run-experiment.sh with the approved manifest
# experiments/manifests/experiment-h-answers.manifest. No tuning, no retry.
#
# It runs ONE sbt command, the Scala entry point
# scholarchain.evaluation.answer.AnswerEval (experiments/evaluation), in full mode (not
# --diagnostic): every question once, then a seeded stratified repeat of up to 20 answerable
# questions, then the frozen answer records and the rating sample. It reads A's local export from
# disk (graph port: InMemoryNeptune, no Neptune, ECS or ALB) and uses Titan, S3 Vectors, S3
# GetObject and Bedrock Converse (Haiku) in us-east-2. Raw output goes under
# SCHOLARCHAIN_RUN_DIR (the run's raw/ directory): events.jsonl and answers/ (answers.jsonl with
# its RAW-SHA256SUMS written first, then model-attempts-summary.json, model-calls.jsonl,
# answer-summary.json, rating-sample.csv, rating-sample-key.json, run-manifest.json and the
# directory's own SHA256SUMS). answers.jsonl and rating-sample.csv hold question and answer text
# (they are the frozen records the audit and the human rating need); nothing else does.
#
# AnswerAudit (no AWS) is a separate step on the frozen answers.jsonl; see README.md.
#
# Human-supplied environment (paths and names only, no secret; the environment is NOT bound by
# the approval digest, the committed numbers below are, because they are part of this script):
#   SCHOLARCHAIN_H_EXPORT_DIR      A's local Spark export (has export-manifest.json and _SUCCESS)
#   SCHOLARCHAIN_H_QUESTIONS       questions-v1.jsonl
#   SCHOLARCHAIN_H_LABELS          labels-v1.jsonl
#   SCHOLARCHAIN_H_DATA_BUCKET     the data bucket that holds the semantic documents
#   SCHOLARCHAIN_H_VECTOR_BUCKET   the S3 Vectors bucket
#   SCHOLARCHAIN_H_MODEL_ID        the Bedrock model or inference profile id of Haiku 4.5
# AWS credentials come from the profile in AWS_PROFILE (the SDK default chain), never from here.
# Committed limits (must stay within the manifest; a change is a new script digest):
#   Haiku call cap 460 (decisions P16-3 and P16-29), Titan call cap 1000, USD stop 2.00
#   (= SPEND_CEILING_USD, the binding limit, decision P16-3), repeat questions 20, rating sample 20,
#   rates Haiku 1.00 input and 5.00 output, Titan 0.02 USD per million tokens (all UNVERIFIED, the
#   human reads the pricing pages). USD figures are estimates, not bounds.
# The call cap 460 covers the worst case: 76 answers (56 primary plus 20 repeat), each with a
#   planner classification that misses the rules (StructuredCall, up to 3 attempts) and a synthesis
#   (up to 3 attempts), 6 calls per answer, 76 x 6 = 456, plus 4 slack = 460. The USD stop ends the
#   run first in practice.
# Wall clock: the in-process stop is an ABSOLUTE deadline, computed here from the start of this
#   script, MAX_MINUTES minus MARGIN_MINUTES (10) after it. sbt start, compile and the graph load
#   are inside that window (their duration is UNMEASURED until the first live run). The deadline is
#   checked between answers, so the run can pass it by one answer: the longest is 6 Haiku calls at
#   the 40 s call deadline (4 minutes) plus 2 Titan embeds at the 60 s attempt deadline (2 minutes,
#   no SDK retry), 6 minutes, and the closing writes take under a minute, so 10 minutes is the
#   margin. If a dependency hangs past the margin, run-experiment.sh kills the process at
#   MAX_MINUTES; answers.jsonl is appended and flushed as each answer finishes, so the answers made
#   so far stay (without RAW-SHA256SUMS, summaries, rating sample and seal; the run directory's own
#   SHA256SUMS from run-experiment.sh covers them).
SC_ENTRY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=experiments/lib/exp-lib.sh
source "$SC_ENTRY_DIR/../lib/exp-lib.sh"

trap 'exp_event ENTRY_SIGNAL "TERM or INT received"; exit 143' TERM INT

# 76 answers x (3 planner + 3 synthesis attempts) = 456, plus 4 slack.
HAIKU_MAX_CALLS=460
TITAN_MAX_CALLS=1000
USD_CEILING=2.00
HAIKU_INPUT_USD_PER_MTOK=1.00
HAIKU_OUTPUT_USD_PER_MTOK=5.00
TITAN_USD_PER_MTOK=0.02
REPEAT_COUNT=20
RATING_SAMPLE_SIZE=20
MARGIN_MINUTES=10

ENTRY_START_EPOCH="$(date +%s)"
exp_init H

for v in SCHOLARCHAIN_H_EXPORT_DIR SCHOLARCHAIN_H_QUESTIONS SCHOLARCHAIN_H_LABELS \
  SCHOLARCHAIN_H_DATA_BUCKET SCHOLARCHAIN_H_VECTOR_BUCKET SCHOLARCHAIN_H_MODEL_ID; do
  [[ -n "${!v:-}" ]] || exp_die "$v is not set"
done
[[ "$SCHOLARCHAIN_H_EXPORT_DIR" == /* && -d "$SCHOLARCHAIN_H_EXPORT_DIR" ]] || exp_die "SCHOLARCHAIN_H_EXPORT_DIR must be an existing absolute directory"
[[ "$SCHOLARCHAIN_H_QUESTIONS" == /* && -f "$SCHOLARCHAIN_H_QUESTIONS" ]] || exp_die "SCHOLARCHAIN_H_QUESTIONS must be an existing absolute file"
[[ "$SCHOLARCHAIN_H_LABELS" == /* && -f "$SCHOLARCHAIN_H_LABELS" ]] || exp_die "SCHOLARCHAIN_H_LABELS must be an existing absolute file"
[[ "$SCHOLARCHAIN_MAX_TASKS" -ge "$HAIKU_MAX_CALLS" ]] || exp_die "MAX_TASKS $SCHOLARCHAIN_MAX_TASKS is below the answer-model call cap $HAIKU_MAX_CALLS of this experiment"
ENTRY_MINUTES=$((SCHOLARCHAIN_MAX_MINUTES - MARGIN_MINUTES))
[[ "$ENTRY_MINUTES" -ge 20 ]] || exp_die "MAX_MINUTES $SCHOLARCHAIN_MAX_MINUTES minus the $MARGIN_MINUTES minute margin leaves under 20 minutes for the run"
DEADLINE_EPOCH_SECONDS=$((ENTRY_START_EPOCH + ENTRY_MINUTES * 60))

# The input digests go in the ledger first, so a changed input is visible in the raw record.
exp_event INPUT_QUESTIONS "sha256=$(exp_sha256 "$SCHOLARCHAIN_H_QUESTIONS")"
exp_event INPUT_LABELS "sha256=$(exp_sha256 "$SCHOLARCHAIN_H_LABELS")"
exp_event ENTRY_LIMITS "haiku_max_calls=$HAIKU_MAX_CALLS titan_max_calls=$TITAN_MAX_CALLS usd_ceiling=$USD_CEILING repeat_count=$REPEAT_COUNT entry_minutes=$ENTRY_MINUTES margin_minutes=$MARGIN_MINUTES deadline_epoch_seconds=$DEADLINE_EPOCH_SECONDS"

cd "$EXP_REPO_ROOT"
rc=0
sbt "evaluation/runMain scholarchain.evaluation.answer.AnswerEval \
--questions $SCHOLARCHAIN_H_QUESTIONS \
--labels $SCHOLARCHAIN_H_LABELS \
--out-dir $SCHOLARCHAIN_RUN_DIR \
--run-id answers \
--repeat-count $REPEAT_COUNT \
--rating-sample-size $RATING_SAMPLE_SIZE \
--deadline-epoch-seconds $DEADLINE_EPOCH_SECONDS \
--export-dir $SCHOLARCHAIN_H_EXPORT_DIR \
--region $SCHOLARCHAIN_REGION \
--data-bucket $SCHOLARCHAIN_H_DATA_BUCKET \
--vector-bucket $SCHOLARCHAIN_H_VECTOR_BUCKET \
--model-id $SCHOLARCHAIN_H_MODEL_ID \
--titan-max-calls $TITAN_MAX_CALLS \
--haiku-max-calls $HAIKU_MAX_CALLS \
--usd-ceiling $USD_CEILING \
--haiku-input-usd-per-mtok $HAIKU_INPUT_USD_PER_MTOK \
--haiku-output-usd-per-mtok $HAIKU_OUTPUT_USD_PER_MTOK \
--titan-usd-per-mtok $TITAN_USD_PER_MTOK" || rc=$?
exp_event ENTRY_END "exit=$rc"
exit "$rc"
