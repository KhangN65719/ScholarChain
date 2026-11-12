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
  description = "Artifacts bucket name holding the job jars."
  type        = string
}

variable "hadoop_jar_key" {
  description = "Object key of the Hadoop job jar under the artifacts bucket. Proposed name: no packaging step names it yet."
  type        = string
  default     = "jars/scholarchain-hadoop.jar"
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
  description = "TimeoutSeconds of each EMR step, the Hadoop step and the Spark step each, 300 to 7200 seconds (default 3600). On timeout the step raises States.Timeout, which is caught and routed to CleanupOnFailure (terminate the cluster, itself bounded at 900 s) and then Failed. Whether a timed-out step also stops the EMR step on the cluster is UNVERIFIED; the termination ends it either way. The value must be at least the longest legitimate single step, because a longer step is stopped and cleaned up. See max_run_seconds for the rule that ties it to the execution timeout."
  type        = number
  default     = 3600

  validation {
    condition     = var.emr_step_timeout_seconds >= 300 && var.emr_step_timeout_seconds <= 7200
    error_message = "emr_step_timeout_seconds must be 300 to 7200."
  }
}

variable "loader_timeout_seconds" {
  description = "TimeoutSeconds of the RunLoader step (the Fargate loader task), 300 to 7200 seconds (default 3600). On timeout the step raises States.Timeout, which is caught and routed to Failed. There is NO cleanup state on this path: the EMR cluster was already terminated by TerminateCluster, and whether the Fargate task is stopped on timeout is UNVERIFIED (a left task bills about $0.025 an hour at the default 0.5 vCPU and 1 GB). The value must be at least the longest legitimate load."
  type        = number
  default     = 3600

  validation {
    condition     = var.loader_timeout_seconds >= 300 && var.loader_timeout_seconds <= 7200
    error_message = "loader_timeout_seconds must be 300 to 7200."
  }
}

variable "max_run_seconds" {
  description = "Timeout of the whole execution, 1800 to 10800 seconds (default 10800, 180 minutes: the 150-minute tier L script cap plus 30 minutes of margin). Pass the bound per run through the experiment tfvars (S 5400, M 7200, L 10800). WARNING: when the execution-level timeout fires, the execution ends as TIMED_OUT and CleanupOnFailure does NOT run, as it does not after StopExecution. If the client dies, a running cluster is left to the EMR idle auto-termination (900 s, which starts only once no step is running) and to the human's cleanup. This bound limits how long the state machine itself can run, nothing more. Two rules tie it to the step timeouts. Rule A (enforced here): create_cluster_timeout_seconds + 2 x emr_step_timeout_seconds + 300 must not exceed this value, so a Hadoop step that runs to its limit followed by a hung Spark step is cut by its own timeout and reaches CleanupOnFailure before this bound. The 300 s is the margin for the cleanup state to issue the terminate call. Known limitation, not enforced (human decision O1, phase 13 D10): create + 2 x EMR step + 900 (TerminateCluster) + loader timeout exceeds this value on every tier, so a run whose steps all take their full timeout, or a run at the estimate's HIGH bound, can end as TIMED_OUT in the loader or terminate segment. Recorded in infra/terraform/README.md, Pipeline bounds."
  type        = number
  default     = 10800

  validation {
    condition     = var.max_run_seconds >= 1800 && var.max_run_seconds <= 10800
    error_message = "max_run_seconds must be 1800 to 10800 (30 to 180 minutes)."
  }

  validation {
    condition     = var.max_run_seconds >= var.create_cluster_timeout_seconds + 2 * var.emr_step_timeout_seconds + 300
    error_message = "Rule A: max_run_seconds must be at least create_cluster_timeout_seconds + 2 x emr_step_timeout_seconds + 300, otherwise a hung second EMR step ends in the execution timeout and CleanupOnFailure is skipped."
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
