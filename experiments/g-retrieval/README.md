# Experiment G: retrieval quality in graph, vector and fused mode (Phase 16, W2-R)

Status: authored, NOT executed. No AWS call, no run, no approval record. Tests ran offline only (`sbt quick`).

## What it measures
For every question of benchmark v1 (`../benchmark/questions-v1.jsonl` and `labels-v1.jsonl`; 48 generated questions plus the 8 free-form ones once the fill lands) in each of the modes `graph`, `vector` and `fused`, two repeats, with the stub answer model (no Haiku call): the ranked ids retrieved, Recall@K, Precision@K, nDCG@K (K = 2 as deployed, 5, 10) and MRR, abstention-aware outcomes (`acceptedOutcomes`, `expectedCodes`), per-stratum and pooled means with a seeded 95% bootstrap interval over questions, over all questions and over the `vectorAvailable` ones.

- K = 2 is read from the vector queries the deployed workflow made (topK 2 per index, recorded, no second call). K = 5 and 10 come from one direct `SemanticRetrieval.search` per question and repeat, with the workflow's own query text, index choice and filter. Titan is called once per question for those direct searches (memoized embedder).
- Only the question `text` is sent to the workflow (test: "only the question text reaches the workflow"). The record the entry point reads has no field for the stratum or any label.
- Graph port: `InMemoryNeptune` loaded from A's local export through the projector (about 6 GB heap; the forked JVM runs with `-Xmx6g`). Not Neptune. Vectors: Titan v2 (256 dimensions), S3 Vectors, S3 GetObject, us-east-2 only.
- Raw per-question records (`records.jsonl`) are appended and flushed to the operating system as each question finishes (a question's records are final once its direct searches are done, so a kill loses at most the question in flight), then hashed (`RAW-SHA256SUMS`) before the summary exists. A record made while the guard refused a call is marked `costStopped` and is left out of every metric.

## Stops (all checked BEFORE the call they guard)
Titan call cap 2000, USD stop 0.25 (every USD figure is an estimate at an UNVERIFIED rate, not a bound; Titan: input characters over four, so spend can pass the ceiling by the error of one call; the Titan client does not retry, so one counted call is one request; S3 Vectors queries and S3 reads are not metered). Wall clock: `run.sh` passes an absolute deadline, `MAX_MINUTES` (60) minus 10 minutes after the start of `run.sh`, so sbt start, compile and the graph load (about 6 GB; duration unmeasured until the first live run) are inside the window. The deadline is checked between questions, so the run can pass it by one question: 3 modes with at most 4 Titan embeds at the 60 s attempt deadline plus S3 reads, about 5 minutes, then the closing writes; 10 minutes is the margin. A dependency that hangs past it is cut by the kill at `MAX_MINUTES`, and the flushed `records.jsonl` keeps the finished questions. Exit codes: 0 complete, 1 failed, 2 usage, 3 halted by a stop. A halted run keeps its raw records and reports `status=HALTED`.

## Free-form questions
The entry point reads `--questions` and `--labels` only. It does not read `free-form-slots-v1.jsonl`. `BenchmarkFill` (decisions P16-23 and P16-26, human step after the Wave 2 commit) appends the 8 free-form lines to `questions-v1.jsonl` (`{questionId, version, stratum:"free-form", text}`) and to `labels-v1.jsonl` (the label line of `benchmark/README.md`, `stratum:"free-form"`); the filled files are then committed (P16-31) before G or H. A question without a label, or a label without a question, is refused. The fill sets a free-form label's `operation` to one of the generator's stratum names (`researcher-lookup`, `coauthor-lookup`, `ranking`, `shortest-path`, `community-component`, `temporal-comparison`): the scorer then reads the graph ids as for that stratum; any other value falls back to the generic rule (every id-bearing fact). That vocabulary is not the runtime's operation names (see "Operation vocabulary" in `benchmark/README.md`). An unanswerable free-form label has `expected.abstain: true` and `expectedCodes`.

## Paste-ready commands (human, `!` prefix, zsh)
Needs the manifest copy with the placeholders replaced (`ACCOUNT_ID`, `OUTPUT_ROOT`), an approval record and a clean tree (commit the filled benchmark first: the fill changes committed files). `scripts/run-experiment.sh` accepts experiment id G. `AWS_PROFILE=scholarchain`, region us-east-2.
```
cd /Users/winnguyen/ScholarChain
mkdir -p /Users/winnguyen/scholarchain-data/logs /Users/winnguyen/scholarchain-data/experiments/runs/phase16
set -o pipefail
ACCOUNT_ID="$(AWS_PROFILE=scholarchain aws sts get-caller-identity --query Account --output text)"
export SCHOLARCHAIN_G_EXPORT_DIR="/Users/winnguyen/scholarchain-data/rehearsal/a-export"
export SCHOLARCHAIN_G_QUESTIONS="/Users/winnguyen/ScholarChain/experiments/benchmark/questions-v1.jsonl"
export SCHOLARCHAIN_G_LABELS="/Users/winnguyen/ScholarChain/experiments/benchmark/labels-v1.jsonl"
export SCHOLARCHAIN_G_DATA_BUCKET="REPLACE_WITH_DATA_BUCKET"
export SCHOLARCHAIN_G_VECTOR_BUCKET="REPLACE_WITH_VECTOR_BUCKET"
AWS_PROFILE=scholarchain bash /Users/winnguyen/ScholarChain/scripts/run-experiment.sh --manifest "/Users/winnguyen/scholarchain-data/experiments/REPLACE_WITH_G_MANIFEST_COPY" --environment course --account-id "${ACCOUNT_ID}" --region us-east-2 --cost-ceiling-usd 20 --approval-record "/Users/winnguyen/scholarchain-data/approvals/REPLACE_WITH_G_APPROVAL.env" 2>&1 | sed -E 's/[0-9]{12}/************/g' | tee "/Users/winnguyen/scholarchain-data/logs/phase16-g-run.log" | tail -n 60; echo "exit: $?"
```
Expected: roughly 10 to 40 minutes; `exit: 0`, and `RETRIEVAL_RESULT status=COMPLETE` in `raw/entrypoint.log`. Output: `<OUTPUT_ROOT>/exp-G-<utc>-<sha>/raw/retrieval/` (`records.jsonl`, `RAW-SHA256SUMS`, `summary.json`, `run-manifest.json`, `SHA256SUMS`).

## Limits
- Ground truth and retrieval share one export, so these numbers measure plan, resolve and retrieve fidelity to the graph, not real-world truth.
- Researcher-lookup recall in graph mode is capped by what the runtime returns (`benchmark/README.md`, "Threats to validity"); community and component members are never retrieved in graph mode.
- Template wording matches the planner rules, so planner accuracy is overstated; the free-form questions test that.
- Graph port is `InMemoryNeptune`, not Neptune; latency here is laptop-to-AWS, not in-VPC.
- Titan USD is an estimate, rates UNVERIFIED.
