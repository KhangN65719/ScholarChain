# W2-R (resumed): experiment-engineer report

Author: experiment-engineer. Received by the orchestrator on 2026-11-18 and preserved as received.

---

**W2-R: COMPLETE, owner experiment-engineer.** `sbt quick` passes. Nothing was run against real data, AWS or Python, and nothing was committed. Three checks stay open for the human: `check-manifest-json.sh`, shellcheck and `bash -n` of the two `run.sh` files, and a dry run of `run-experiment.sh` on G and H.

## Inventory of the interrupted turn

| Item | State | What I did |
|---|---|---|
| 1 `runtime/LaptopPorts` (`EvalArgs`, region lock, `--offline`) | done | Nothing. |
| 2 `runtime/CostGuard` (Titan and Haiku caps, USD stop) | done | Nothing. |
| 3 `retrieval/RetrievalEval` (+`RetrievalData`, `RetrievalScoring`) | done | Nothing. |
| 4 `answer/AnswerEval` (+`AnswerRecords`, `RatingSample`) | done | Added one test (see below). |
| 5 `answer/AnswerAudit` | done | Nothing. |
| 6 manifests and entry points | partial | `h-answers/run.sh` was missing and is now written. G and H manifests and their JSON copies were corrected from 58 to 56 questions. |

- Manifest changes: 58 questions became 56 (336 G workflow calls, 76 H answers at most 228 attempts). The `MAX_TASKS` comments were rewritten, and `g-retrieval/run.sh` now refuses a `MAX_TASKS` below 336.
- Added `README.md` in `g-retrieval/` and `h-answers/` with the paste-ready commands, the stops and the limits.
- Added one test. H had no text-only test, so `AnswerEvalSuite` now checks that only the question text reaches the workflow in the primary and repeat passes.

## Free-form questions

G, H and AnswerAudit read `--questions` and `--labels` only, never `free-form-slots-v1.jsonl`. `questions-v1.jsonl` currently holds 48 lines and no free-form lines. The fill order must append the 8 lines to both files (or pass merged files). A question without a label, or a label without a question, is refused.

The free-form label should use `stratum:"free-form"`. Its `operation` should be one of `researcher-lookup`, `coauthor-lookup`, `ranking`, `shortest-path`, `community-component` or `temporal-comparison`. Any other value falls back to a generic rule that reads every id-bearing fact. An unanswerable free-form label needs `expected.abstain:true` and `expectedCodes`.

## Test per gate item (all in `experiments/evaluation`)

- Scoring: `RetrievalScoringSuite` (recall, precision, nDCG and MRR against the metric oracle; abstention outcomes; accepted outcomes (`NotPrecomputed`); false abstention; the `vectorAvailable` scope; bootstrap determinism; repeat means).
- Cap and USD stop fire before the call: `CostGuardSuite`, asserting the inner model or embedder call count is unchanged after the refusal (Haiku cap; USD stop before the call; a ceiling below one call's worst case; a sticky stop; Titan cap and Titan USD stop; failed and estimated calls charged at the worst case; a cost stop is not a `StorageException`). End to end: `LaptopPortsSuite` ("Titan cap stops a workflow call before an embed", "Haiku cap turns synthesis into a refusal, never a call"), `RetrievalEvalSuite` ("a Titan stop ends the run…"), `AnswerEvalSuite` (USD stop, call cap).
- Text-only questions: `RetrievalEvalSuite` ("only the question text reaches the workflow…") and the new `AnswerEvalSuite` test, which also checks that `QuestionRec` has only `questionId` and `text`.
- `--diagnostic` call limit: stops at four calls and records the stop reason and rejection codes; refuses a guard allowing more than four before writing anything; the command-line cap is clamped to four; writes no question or answer text; picks distinct strata.
- Audit rules: `AnswerAuditSuite` (coauthor edge, path chain and hop, supporting works, publication, metric, ranking, group size and identity; structural support, unknown ids, number matching with tolerance, contradictions, verdicts kept apart as self-reported; abstention metrics, repeat variability, and a run that writes no claim or answer text).
- Offline end to end on a tiny fixture: `RetrievalEvalSuite` and `AnswerEvalSuite` run the full runs and the command line over the demo world with the stub model. `LaptopLiveSuite` covers the live path over an empty export, with no vectors and no AWS.

## `sbt quick` result

Run twice, the second after the test edit. The evaluation project's line was `Passed: Total 239, Failed 0, Errors 0, Passed 239`.

```
[info] Passed: Total 77, Failed 0, Errors 0, Passed 77
[info] Passed: Total 1126, Failed 0, Errors 0, Passed 1126
[success] Total time: 157 s (0:02:37.0), completed Nov 18, 2026, 9:11:36 AM
```

## Paste-ready commands

Full commands are in `experiments/h-answers/README.md` and `experiments/g-retrieval/README.md`.
- Common: `AWS_PROFILE=scholarchain`, `set -o pipefail`, absolute paths, `| sed -E 's/[0-9]{12}/************/g' | tee <log> | tail`, logs at `/Users/winnguyen/scholarchain-data/logs/phase16-{h4-diagnostic,g-run,h-run,h-audit}.log`.
- H-4 diagnostic: direct `sbt "evaluation/runMain …AnswerEval --diagnostic …"` with `--haiku-max-calls 4 --usd-ceiling 0.15`, writing to `…/runs/phase16/h4-diagnostic-1`. It does not go through `run-experiment.sh`. D10 lists the diagnostic among manifest runs, so the orchestrator or human should decide whether it needs a manifest.
- G and H: `run-experiment.sh` with `SCHOLARCHAIN_{G,H}_*` environment variables, a manifest copy with real `ACCOUNT_ID` and `OUTPUT_ROOT`, and an approval record. Need a clean tree, so after the commit and after the `run-experiment.sh` change below. H also waits on the diagnostic and the synthesis-limit decision.
- AnswerAudit: separate step, no AWS, reads `<OUTPUT_ROOT>/exp-H-…/raw/answers/answers.jsonl`, writes `h-audit-1` outside the read-only run directory.
- Placeholders: data bucket, vector bucket, manifest and approval file names. Model id is the plan's D1 value, `us.anthropic.claude-haiku-4-5-20251001-v1:0`.

## Routed requests

1. infra-engineer, `scripts/run-experiment.sh:144-145`: the list is `A | B | C | D | E | F) ;;` with message "the course experiments are A to F". Change to `A | B | C | D | E | F | G | H) ;;`, update the message, and update the experiment-id wording in the "Experiment manifest contract" section of `scripts/README.md` (not located). This is what fails `8.committed-manifest.{G,H}`.
2. test-engineer, `tests/scripts/run.sh:1817`: `[ "$n_mf" -eq 6 ] || note_fail "expected 6 committed manifests…"` needs 8, and the message too. The per-manifest loop at lines 1788-1813 derives the letter from `${bn:11:1}` and works for `g` and `h` once item 1 lands. The `15.experiment.manifests.each-of-the-six…` case was not located (no grep); it needs the same count and name change.
3. human or orchestrator: `experiments/README.md` still describes six manifests (A to F) and could get a G/H row. Left alone.

## Unverified

- Shell syntax and shellcheck of the two `run.sh` files (guard blocks `bash -n`).
- `check-manifest-json.sh` (human runs it); JSON copies hand-edited to match.
- Path layout `<OUTPUT_ROOT>/exp-H-…/raw/answers/`, inferred from the `run-experiment.sh` dry-run text, not observed.
- No live Titan, S3 Vectors, S3 GetObject or Bedrock call. `LaptopLiveSuite` covers an empty export only.
- Rates (Haiku 1.00 in and 5.00 out, Titan 0.02 USD per million tokens) unverified.
- sbt's exit code for a non-zero forked exit unobserved; read the printed `RETRIEVAL_RESULT`, `ANSWER_RESULT`, `AUDIT_RESULT` lines.
- Whether real data-bucket documents carry a `subjectId` matching the labels' `subjectId` was not tested offline.

Next gate: independent review, then the `run-experiment.sh` and harness changes, the free-form fill, the diagnostic, and the G and H runs.

Files: `experiments/h-answers/run.sh`, `experiments/h-answers/README.md`, `experiments/g-retrieval/README.md` (new); `experiments/g-retrieval/run.sh`, `experiments/manifests/experiment-{g-retrieval,h-answers}.manifest`, `experiments/manifests/json/experiment-{g-retrieval,h-answers}.json`, `experiments/evaluation/src/test/scala/scholarchain/evaluation/answer/AnswerEvalSuite.scala` (edited).
