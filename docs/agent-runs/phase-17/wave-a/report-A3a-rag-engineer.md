# WO-A3a report (rag-engineer), as received 2026-11-19

STATUS: COMPLETE
OWNER: rag-engineer
WORK ORDER: WO-A3a (K01, sbt slot 1)
FILES CREATED: none
FILES MODIFIED: `modules/rag/src/test/scala/scholarchain/rag/AwsIngestMainSuite.scala`. In the test "credentials in the environment are never read into the output", the `val secret` literal (a fake AKIA-prefixed key id; [REDACTED, see note]) and the two literal values became runtime-built `keyId = "AK" + "IA" + "FAKEFAKEFAKE" + "12345"`, `accessValue = Vector("super","secret","value","9876").mkString("-")`, `sessionValue = Vector("token","value","abcdef").mkString("-")`. The runtime values are unchanged and the leak assertions use the three names. Lines 655 and 659 (`"UPPER_secret_value!"`, `"not-an-arn-secret"`) were left unchanged: they are map values outside the order.
COMMANDS RUN: `sbt quick` (bare). An earlier piped attempt was refused by the guard and did not run.
TEST RESULTS: `[success] Total time: 157 s`, completed 2026-11-19 10:05:55 AM. Totals read: `Passed: Total 77, Failed 0, Errors 0, Passed 77` and `Passed: Total 1306, Failed 0, Errors 0, Passed 1306`. The per-module split was NOT RECORDED.
VALIDATION RESULTS: the existing test passes inside the green run (its own line was not read). There was no grep for the old literals (the guard refuses it). No AWS and no Python were used.
ASSUMPTIONS: the K01 scanner is a line regex (`AKIA[0-9A-Z]{16}`, and the quoted secret-assignment form [REDACTED wording, P17-36]). A V1 re-preview is needed to confirm.
RISKS: a scanner that folds constant concatenation would still match. The marker should be newer than the edit, but the agent could not stat it.
MANUAL ACTION REQUIRED: none.
BLOCKED-ON OWNER: none. BLOCKED-ON EVIDENCE: none.
NEXT GATE: WO-A3b, then WO-A3c.

---
Redaction note (orchestrator, 2026-11-20, decision P17-34): on line 7 the quoted fake key literal and its assignment form were replaced by a description, so that the committed archive holds no credential-shaped string (review F1 finding B5). The value was fake; it equals the runtime-built `keyId` in `AwsIngestMainSuite.scala:727`. Nothing else in this report was changed.
Second redaction (orchestrator, 2026-11-20, decision P17-36): on line 11 the quoted secret-assignment form was replaced by a description, and this note's own wording was reworded the same way (re-review F1-1 finding N1).
