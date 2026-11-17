# WO-1c report, infra-engineer (received 2026-11-17; preserved as received; author: infra-engineer subagent)

Order (orchestrator, 2026-11-17): apply the whitespace-only `terraform fmt` diff from the human's run (`phase15-wave1-fmt-diff.log`, exit 3) and fix the shellcheck SC2034 warning at `scripts/emr/lib-emr.sh:617` (`phase15-wave1-shellcheck.log`, exit 1).

Status: COMPLETE. No terraform, shellcheck, AWS, sbt or commit run; neither repair re-checked by a tool.

## Diff 1: `infra/terraform/modules/workflow/main.tf` (whitespace only)
```diff
@@ -115 @@  definition = {
-    Comment       = "Bounded pipeline. ... then end."
+    Comment        = "Bounded pipeline. ... then end."
@@ -354,3 +354,3 @@ resource "aws_sfn_state_machine" "pipeline"
-  name       = "${var.name_prefix}-pipeline"
-  role_arn   = var.sfn_role_arn
-  type       = "STANDARD"
+  name     = "${var.name_prefix}-pipeline"
+  role_arn = var.sfn_role_arn
+  type     = "STANDARD"
   definition = jsonencode(merge(local.definition, {
```

## Diff 2: `scripts/emr/lib-emr.sh` (`detect_remote_yarn_nodes`)
```diff
-  SC_REMOTE_NODES="$n"
+  # shellcheck disable=SC2034  # read by run-s-pipeline.sh (DATA_PATH, SPARK_MASTER, YARN_REMOTE_NODES)
+  SC_REMOTE_NODES="$n"
 }
```
Reason: `run-s-pipeline.sh` reads `SC_REMOTE_NODES` at line 205 (`DATA_PATH=auto`), 211 (`YARN_REMOTE_NODES` record) and 442 (`SPARK_MASTER=auto`); the script sources the library, so shellcheck cannot see the read from the library file. Removing the assignment would break both `auto` decisions.

Risks: none beyond whitespace and one comment line.
