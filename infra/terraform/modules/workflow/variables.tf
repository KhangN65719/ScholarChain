variable "name_prefix" {
  description = "Prefix for names. The identity module builds queue and state machine ARN patterns from it."
  type        = string
}

variable "enable_pipeline" {
  description = "Create the pipeline state machine. A state machine costs nothing while idle."
  type        = bool
  default     = true
}

variable "sfn_role_arn" {
  description = "Step Functions execution role."
  type        = string
}

variable "cluster_definition" {
  description = "RunJobFlow parameters from the emr module."
  type        = any
}

variable "artifacts_bucket" {
  description = "Artifacts bucket name holding the job jars (jars/), the Spark 4.0.5 distribution (jars/spark-4.0.5/) and the on-node scripts (bootstrap/)."
  type        = string
}

variable "data_bucket" {
  description = "Data bucket name: inputs under raw/, run outputs under curated/, graph/, semantic-context/ and reports/. Passed to the on-node scripts as an argument."
  type        = string
}

variable "ecs_cluster_arn" {
  description = "ECS cluster for the loader task."
  type        = string
}

variable "loader_task_definition_arn" {
  description = "Loader task definition ARN."
  type        = string
}

variable "app_subnet_ids" {
  description = "Subnets for the loader task."
  type        = list(string)
}

variable "loader_security_group_id" {
  description = "Loader security group."
  type        = string
}

variable "create_cluster_timeout_seconds" {
  description = "TimeoutSeconds of the CreateCluster step, 300 to 3600 seconds (default 1440 = 0.4 h, the HIGH cluster launch overhead in COST_ESTIMATE.md section 1.2). Bounds how long Step Functions waits for the cluster to start. On timeout the step raises States.Timeout, which is caught and routed to Failed. There is NO cleanup state on this path. Whether Step Functions also terminates the half-created cluster is UNVERIFIED, and idle auto-termination is not a proven backstop for a cluster that never reached WAITING (UNVERIFIED), so a hung CreateCluster can leave a cluster until the script's leftover-cluster teardown (run-bounded-pipeline.sh with --stop-on first-failure) or the human's cleanup ends it."
  type        = number
  default     = 1440

  validation {
    condition     = var.create_cluster_timeout_seconds >= 300 && var.create_cluster_timeout_seconds <= 3600
    error_message = "create_cluster_timeout_seconds must be 300 to 3600."
  }
}

variable "emr_step_timeout_seconds" {
  description = "TimeoutSeconds of the one long EMR script step (CapabilityStep or PipelineStep), 300 to 7200 seconds (default 3600). The script-fetch step has its own fixed 300 s. Rule A below counts this value once plus the 300 s fetch step (work order 49; it used to count 2 x this value, which over-covered a run of one long step). On timeout the step raises States.Timeout, which is caught and routed to CleanupOnFailure (terminate the cluster, itself bounded at 900 s) and then Failed. Whether a timed-out step also stops the EMR step on the cluster is UNVERIFIED; the termination ends it either way. The value must be at least the longest legitimate single step, because a longer step is stopped and cleaned up. See max_run_seconds for the rule that ties it to the execution timeout."
  type        = number
  default     = 3600

  validation {
    condition     = var.emr_step_timeout_seconds >= 300 && var.emr_step_timeout_seconds <= 7200
    error_message = "emr_step_timeout_seconds must be 300 to 7200."
  }
}

variable "loader_timeout_seconds" {
  description = "TimeoutSeconds of the RunLoader step (the Fargate loader task), 300 to 7200 seconds (default 3600). The step runs in mode pipeline (after TerminateCluster) and in mode loader-only (work order 86, no cluster at all). On timeout the step raises States.Timeout, which is caught and routed to Failed. There is NO cleanup state on this path: in mode pipeline the EMR cluster was already terminated by TerminateCluster, in mode loader-only none exists, and whether the Fargate task is stopped on timeout is UNVERIFIED (a left task bills about $0.025 an hour at the default 0.5 vCPU and 1 GB). The value must be at least the longest legitimate load."
  type        = number
  default     = 3600

  validation {
    condition     = var.loader_timeout_seconds >= 300 && var.loader_timeout_seconds <= 7200
    error_message = "loader_timeout_seconds must be 300 to 7200."
  }
}

variable "max_run_seconds" {
  description = "Timeout of the whole execution, 1800 to 10800 seconds (default 10800, 180 minutes: the 150-minute tier L script cap plus 30 minutes of margin). Pass the bound per run through the experiment tfvars (S 7200 since work order 49, M 7200, L 10800). WARNING: when the execution-level timeout fires, the execution ends as TIMED_OUT and CleanupOnFailure does NOT run, as it does not after StopExecution. If the client dies, a running cluster is left to the EMR idle auto-termination (900 s, which starts only once no step is running) and to the human's cleanup. This bound limits how long the state machine itself can run, nothing more. Two rules tie it to the step timeouts. Rule A (enforced here, changed in work order 49): create_cluster_timeout_seconds + emr_step_timeout_seconds + 600 must not exceed this value, so the one long EMR step that runs to its limit is cut by its own timeout and reaches CleanupOnFailure before this bound. The 600 s is the fixed 300 s fetch step plus 300 s of margin for the cleanup state to issue the terminate call. Known limitation, not enforced (human decision O1, phase 13 D10): create + 300 (fetch) + EMR step + 900 (TerminateCluster) + loader timeout can exceed this value (S overlay: 12840 against 7200 since work order 86, which raised the S loader timeout to 5400; it was 9240 with 1800), so a run whose segments all take their full timeout, or a run at the estimate's HIGH bound, can end as TIMED_OUT in the loader or terminate segment. Rule A does not apply to mode loader-only (work order 86): that execution is ValidateInput and RunLoader only, with no EMR state, so it ends at loader_timeout_seconds (plus the Fargate start), which must stay below this value to end at its own timeout (S: 5400 against 7200; not enforced by a validation). Recorded in infra/terraform/README.md, Pipeline bounds."
  type        = number
  default     = 10800

  validation {
    condition     = var.max_run_seconds >= 1800 && var.max_run_seconds <= 10800
    error_message = "max_run_seconds must be 1800 to 10800 (30 to 180 minutes)."
  }

  validation {
    condition     = var.max_run_seconds >= var.create_cluster_timeout_seconds + var.emr_step_timeout_seconds + 600
    error_message = "Rule A: max_run_seconds must be at least create_cluster_timeout_seconds + emr_step_timeout_seconds + 600 (300 s fetch step plus 300 s cleanup margin), otherwise a hung EMR step ends in the execution timeout and CleanupOnFailure is skipped."
  }

  # Phase 15: Rule A with the optional Wait state. Identical to the rule above when
  # wait_before_terminate_seconds is 0 (the default); the rule above is kept as it was.
  validation {
    condition     = var.max_run_seconds >= var.create_cluster_timeout_seconds + var.emr_step_timeout_seconds + var.wait_before_terminate_seconds + 600
    error_message = "Rule A with the wait: max_run_seconds must be at least create_cluster_timeout_seconds + emr_step_timeout_seconds + wait_before_terminate_seconds + 600, so the Wait state before TerminateCluster does not push a hung step past the execution timeout."
  }
}

variable "wait_before_terminate_seconds" {
  description = "Phase 15 (P15-1, Experiment B): a bounded Step Functions Wait state before TerminateCluster, 0 to 1800 seconds. Default 0 means NO Wait state exists and the state machine is the same as before. When non-zero, CapabilityStep and PipelineStep go to WaitBeforeTerminate, which waits this many seconds with the cluster still running and idle, so a managed-scaling scale-in can be observed before the cluster is terminated. The cluster's idle auto-termination (emr_idle_timeout_seconds in the course environment) must be longer than this wait, or the cluster can end itself before TerminateCluster runs: the overlay that sets a wait must also raise the idle timeout. The failure paths (CleanupOnFailure) never wait. Rule A counts this value (the second validation on max_run_seconds)."
  type        = number
  default     = 0

  validation {
    condition     = var.wait_before_terminate_seconds >= 0 && var.wait_before_terminate_seconds <= 1800
    error_message = "wait_before_terminate_seconds must be 0 to 1800 (0 = no Wait state)."
  }
}

variable "skip_loader_after_pipeline" {
  description = "Phase 15 (Experiments A and B): when true, TerminateCluster of mode pipeline goes straight to Done and the AfterTerminate state is left out of the definition, so a run with Neptune and Bedrock off ends SUCCEEDED without starting the Fargate loader (which has nothing to do then and would fail or idle). Default false: the state machine is unchanged. Mode loader-only still reaches RunLoader. The execution input still needs loader_command in mode pipeline (ValidateInput checks it); its value is not used."
  type        = bool
  default     = false
}

variable "pipeline_profile" {
  description = "Phase 15: the settings profile passed as the optional sixth argument of scripts/emr/run-s-pipeline.sh, which reads the matching <profile>.KEY lines of spark-yarn.conf (a-baseline for Experiment A, b-burst for B). Empty (default) passes no sixth argument and the Args expression is the one it was. 0 to 32 characters of a-z0-9- so it cannot break the States.Array expression."
  type        = string
  default     = ""

  validation {
    condition     = can(regex("^[a-z0-9-]{0,32}$", var.pipeline_profile))
    error_message = "pipeline_profile must be 0 to 32 characters of a-z0-9-."
  }
}

variable "log_retention_days" {
  description = "CloudWatch log retention."
  type        = number
  default     = 3
}

variable "queue_retention_seconds" {
  description = "Message retention of the work queues. Dead-letter queues keep messages four days."
  type        = number
  default     = 86400
}

variable "queue_visibility_seconds" {
  description = "Visibility timeout of the work queues."
  type        = number
  default     = 300
}

variable "max_receive_count" {
  description = "Receives before a message moves to the dead-letter queue."
  type        = number
  default     = 3
}
