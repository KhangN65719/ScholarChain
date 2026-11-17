# Overlay for a tier S run (3,472 works, the real S subset run in Phase 14 run 6; the
# earlier "5,000 works" was the estimate's proposed size). PROPOSED sizes: decision D2c, COST_ESTIMATE.md.
# Apply AFTER course.tfvars (later -var-file wins). Values only, no secrets.
# Adds Neptune (a session resource: destroy it after the run). Pipeline on one
# primary node, matching the estimate's node-hours for S.

enable_neptune = true
neptune_mode   = "provisioned"

# Bedrock on (work order 31). The loader embeds with Titan V2 in-Region
# (amazon.titan-embed-text-v2:0, us-east-2), which needs the bedrock-runtime
# interface endpoint (a ninth, about 0.010 to 0.015 USD an hour in one AZ at the
# estimate's unverified rate) and the bedrock:InvokeModel statements on the loader
# and runtime roles. It also adds the Haiku profile and foundation-model ARNs to
# the runtime role. With it off the loader cannot embed. The M and L overlays do
# not set it: a loader run under them needs the same line.
enable_bedrock = true

emr_core_instance_count       = 0
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 3

service_desired_count = 0

# Work order 108 (human decision, S run 8): the Fargate loader task died with
# OutOfMemoryError in the Neptune graph phase at the module default of 0.5 vCPU and
# 1024 MiB (JVM heap 75 percent of that, about 768 MiB). The S session now gives it
# 2 vCPU and 8 GiB (heap about 6 GiB). 2048 CPU allows 4096 to 16384 MiB in 1024 steps,
# so 8192 is a valid Fargate pair. Ephemeral storage is not set and stays at the
# 20 GiB default (the S export is 324 MB). Fargate X86_64 Linux, us-east-2, about
# 0.1165 USD an hour against 0.0247 (rates from memory, UNVERIFIED): about +0.09 USD
# an hour while the loader runs. The loader task definition is replaced; the state
# machine references it without a revision, so it changes in place only because the
# new ARN is unknown at plan time (the same shape as an image-tag change). The
# session-down overlay does not set these, so the next session-down plan returns the
# loader to 512 and 1024.
loader_cpu    = 2048
loader_memory = 8192

# Phase 15 (P15-5, 2026-11-16): the S overlay is back at the Terraform default class,
# db.t4g.medium, so an ordinary S session-up creates the cheap writer. The bulk load
# needs db.r6g.xlarge; that class is now the separate overlay graph-load.tfvars, layered
# after this one for the load only (apply order is documented in that file). The work
# order 114 text below is kept as history: it describes why the xlarge class was needed
# (run 10) and was written when this overlay itself set it.
neptune_instance_class = "db.t4g.medium"

# Work order 114 (human decision, 2026-11-16, after graph-only S run 10): Neptune's
# bulk loader reported OUT_OF_MEMORY_ERROR ("Out of memory error. Resume load and try
# again.", 153,760 errors counted after the failure) on edges-COAUTHORED_WITH.csv
# (69 MB, 98,661 edges) on the db.t4g.medium writer (4 GiB). The AWS bulk-load
# optimization guidance ("Scale up", docs.aws.amazon.com/neptune/latest/userguide/
# bulk-load-optimize.html; not re-read by the author, UNVERIFIED) says to set the
# writer to a large instance before a bulk load and scale it back down afterwards.
# db.r6g.xlarge is 4 vCPU and 32 GiB. The module sets apply_immediately = true on the
# instance, so the change is a ModifyDBInstance applied now, in place, with a restart
# of a few minutes (not a replacement; the plan must say "updated in-place" for the
# writer, and a "must be replaced" is a stop). Orderability of db.r6g.xlarge for engine
# 1.4.5.1 in us-east-2 (and in the writer's Availability Zone) is UNVERIFIED: check it
# read-only before the plan. Rate, us-east-2, UNVERIFIED: about 0.62 to 0.70 USD an
# hour against 0.06 to 0.10 for db.t4g.medium, so about +0.52 to +0.64 USD an hour,
# about +1.6 to +1.9 USD for a 3 hour window (COST_ESTIMATE.md, work order 114). The
# session-down overlay does not set this (Neptune is destroyed there, so the class is
# moot), and the M and L overlays do not set it: a graph load under them needs the same
# line. A later S session-up creates Neptune at this class for the whole session.
# (Work order 114 set the xlarge class on this spot. Phase 15 moved that setting to
# graph-load.tfvars; this file now sets db.t4g.medium above.)

# Work orders 49 and 52 (human decisions D31 and D32, 2026-11-15, after S runs 3 and
# 4). S run 3: the one on-node pipeline step was cut by the old 1800 s cap while
# Spark was still running. S run 4 (3600 s step cap): the on-node Spark limit of
# 1500 s in scripts/emr/lib-emr.sh cut Spark at about 70% of its stages (about 2100
# to 2400 s needed); that limit is now 3000 s, as is the entity-resolution limit
# (ER needs about 103 s). S run 4 used --size medium (script cap 90 minutes =
# 5400 s); the script stops the execution at --max-minutes before the 7200 s
# execution timeout can fire.
# S run 5 (human decision D33, after the independent review): the command line is
#   --size large --max-minutes 110 --spend-ceiling-usd 1.20 --hourly-burn-usd 0.62
# (projected 0.62 x 110 / 60 = 1.14 USD, a ceiling on the estimate, not a bill). The
# script stops the execution at 6600 s, before the 7200 s execution timeout. --size
# selects only the script's wall-clock tier (the --max-minutes cap, 150 for large),
# not the dataset: the data is the 3,472-work S subset.
# Work order 86 (S run 6, after the loader hit its 1800 s cap at RunLoader): the S
# loader cap is now 5400 s. Run 6 passed every EMR stage and published curated/,
# graph/ and semantic-context/; the Fargate loader embedded serially at about 9
# vectors a second and needed about 47 more minutes (about 2,800 s). The loader is rerun by itself
# in mode loader-only (modules/workflow: no cluster, ValidateInput goes straight to
# RunLoader, the export is already in S3), so the loader cap must fit alone inside
# the 7200 s execution timeout: 5400 <= 7200. In mode pipeline the loader is the last
# segment of a run that has already spent creation, fetch, the step and terminate, so
# the 7200 s execution timeout (and the script's stop) ends such a run long before
# 5400 s of loader time is available; 5400 s is for loader-only runs.
# Phase 15 note on the "660 s" creation figure used below (and in "its observed 660 s"):
# it is UNSOURCED. No record states it, and docs/agent-runs/phase-14/results-s-tier.md
# derives cluster create plus input fetch at about 374 s for run 6 (301 s in run 3,
# about 313 s in run 4; derived, not measured, the API timestamps were not kept). The
# sums below keep 660 as a deliberately high planning figure so the arithmetic that
# other files and checks quote does not move; read "observed" as "assumed".
# The sequential budget of a pipeline run, one segment after the other: creation
# about 660 s (timeout 1440) + fetch up to 300 + EMR step 3300 to 3900 expected (cap
# 4800) + terminate up to 900 + loader (cap 5400).
# 660 + 300 + 3300 + 900 + 5400 = 10560 s at the low step estimate, 11160 s
# at 3900 s, and 660 + 300 + 4800 + 900 + 5400 = 12060 s with the step at its 4800 s cap
# (creation at its observed 660 s; with creation at its 1440 s timeout the same sum is
# the O1 worst case below, 12840 s), against the 7200 s execution timeout (and a script
# stop at 6600 s for --max-minutes 110). So after
# a good pipeline run the stop or the execution timeout can cut the loader (about 840 to
# 1440 s are left for it after terminate at 6600 s). UNVERIFIED: whether StopExecution
# stops the Fargate runTask.sync loader task (stop_everything in the script stops only
# EMR, and in loader-only mode it stops no cluster at all).
# Execution timeout 7200 s, unchanged. When it fires, CleanupOnFailure does not run
# (README, "Pipeline bounds").
# Per-step timeouts (TerminateCluster and CleanupOnFailure stay 900 s, the
# script-fetch step stays 300 s):
#   CreateCluster 1440 s = 0.4 h, the estimate's HIGH launch overhead.
#   EMR step (the one pipeline script: Hadoop, ER, Spark, export, publish) 4800 s.
#   Loader 5400 s (1800 s until work order 86). The estimate's HIGH loader time is
#   1.0 h = 3600 s; run 6 shows the serial Titan embedding run is slower than that.
# Rule A (changed in work order 49, modules/workflow): create + 1 x EMR step + 600
# (300 s fetch step + 300 s for the cleanup state to issue the terminate call)
# must not exceed the execution timeout. 1440 + 4800 + 600 = 6840 <= 7200 (holds,
# 360 s of slack). The loader value does not enter Rule A, so the work order 86 change
# leaves it as it was. The old rule counted 2 x the step and would refuse these
# values. The state machine has one long step, so the old rule over-counted.
# Known limitation, accepted by human decision O1 (phase 13 D10), not enforced:
# the sequential worst case 1440 + 300 + 4800 + 900 + 5400 = 12840 > 7200, so a run
# in which every segment takes its full timeout can end TIMED_OUT in the terminate
# or loader segment (5640 s short; it was 9240 s, 2040 s short, with a 1800 s loader).
# The script's own stop (S run 5: --max-minutes 110 = 6600 s) ends a run first. A cap is
# a ceiling, not a cost.
# The EMR step value is NOT the estimate's HIGH value. After the S run the human
# re-derives the M and L values from the measured S runtime (README, "Pipeline
# bounds") and records them in a Phase 14 decisions file.
pipeline_max_run_seconds                = 7200
pipeline_create_cluster_timeout_seconds = 1440
pipeline_emr_step_timeout_seconds       = 4800
pipeline_loader_timeout_seconds         = 5400
