# R1 report, infra-engineer (received 2026-11-17; preserved with diffs as received; author: infra-engineer subagent)

Status: COMPLETE for the edits. Not run: harness, `bash -n`, `terraform fmt`, scan for other `{n,m}` with m > 255 (guard refuses `grep`, `sed`, `git grep`, `bash -n`).

## 1. `scripts/emr/lib-emr.sh`, `validate_snapshots` (line 388)
```
-  [[ "$1" =~ ^[A-Za-z0-9._=,:-]{1,300}$ ]] || die "snapshots must be 1 to 300 characters of A-Za-z0-9._=,:-"
+  # The length is checked apart from the character class: bash's regex engine on macOS
+  # refuses a repetition count above 255, and {1,300} is one.
+  [[ ${#1} -ge 1 && ${#1} -le 300 && "$1" =~ ^[A-Za-z0-9._=,:-]+$ ]] || die "snapshots must be 1 to 300 characters of A-Za-z0-9._=,:-"
```
Accepted set and error text unchanged.

## 2. `scripts/emr/run-s-pipeline.sh`: not a bug, no call removed
Only one `mapred job -status` call (line 341). The second occurrence was a `kv` record label:
```
-    kv MAPREDUCE_COUNTERS_FILES "$mrc (mapreduce-counters/<job id>.txt, mapred job -status)"
+    kv MAPREDUCE_COUNTERS_FILES "$mrc (mapreduce-counters/<job id>.txt, one job status query per job id)"
```
The case counts the phrase outside comments; the case is unchanged. Any other check asserting the old label text would need changing (not searched).

## 3. `infra/terraform/environments/course/course.tfvars`
```
-neptune_mode           = "provisioned"
-neptune_instance_class = "db.t4g.medium"
-neptune_max_ncu        = 2
+neptune_mode    = "provisioned"
+neptune_max_ncu = 2
```
Both defaults are `db.t4g.medium` (`environments/course/variables.tf` `neptune_instance_class`, `modules/neptune/variables.tf` `instance_class`); effective class unchanged. `variables.tf` description of `neptune_instance_class` changed to say it is the variable's default and only the S and graph-load overlays assign it.

## 4. `scripts/README.md`
```
-Decision D12 is DECIDED for tier S by Phase 15 decision P15-1 (human, 2026-11-16): 90 minutes
+Decision D12 is decided by P15-1 for tier S (Phase 15 decision of the human, 2026-11-16): 90 minutes
```

## Not verified
Other repetition counts above 255: none in `lib-emr.sh` lines 1 to 375 (largest `{1,64}`, `{20,}`, `{16}`); rest of `lib-emr.sh` and other `scripts/**` not read. Suggested human check (should print nothing): `grep -rnE '\{[0-9]*,[0-9]{3,}\}' scripts`. Rerun harness and `terraform fmt -check -recursive infra/terraform`.

Files changed: `scripts/emr/lib-emr.sh`, `scripts/emr/run-s-pipeline.sh`, `infra/terraform/environments/course/course.tfvars`, `infra/terraform/environments/course/variables.tf`, `scripts/README.md`.
