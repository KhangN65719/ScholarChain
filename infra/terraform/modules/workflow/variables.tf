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

variable "step_timeout_seconds" {
  description = "Timeout of each pipeline step, 300 to 7200 seconds (default 3600). A step timeout is a States.Timeout error, which the Hadoop and Spark steps catch and route to CleanupOnFailure. CreateCluster, TerminateCluster and RunLoader route a caught error to Failed instead. Whether a timed-out step also stops the EMR step on the cluster is UNVERIFIED. Set it per tier with max_run_seconds (S 1800 with 5400, M 2700 with 7200, L 3600 with 10800): the rule is that cluster start plus the Hadoop step plus a hung Spark step, which is start + 2 x this value, must stay below max_run_seconds, otherwise the execution-level timeout fires first and CleanupOnFailure is skipped. A single legitimate step longer than this value is stopped and cleaned up, so raise both values together."
  type        = number
  default     = 3600

  validation {
    condition     = var.step_timeout_seconds >= 300 && var.step_timeout_seconds <= 7200
    error_message = "step_timeout_seconds must be 300 to 7200."
  }
}

variable "max_run_seconds" {
  description = "Timeout of the whole execution, 1800 to 10800 seconds (default 10800, 180 minutes: the 150-minute tier L script cap plus 30 minutes of margin). Pass the bound per run through the experiment tfvars (S 5400, M 7200, L 10800). WARNING: when the execution-level timeout fires, the execution ends as TIMED_OUT and CleanupOnFailure does NOT run, as it does not after StopExecution. If the client dies, a running cluster is left to the EMR idle auto-termination (900 s, which starts only once no step is running) and to the human's cleanup. This bound limits how long the state machine itself can run, nothing more."
  type        = number
  default     = 10800

  validation {
    condition     = var.max_run_seconds >= 1800 && var.max_run_seconds <= 10800
    error_message = "max_run_seconds must be 1800 to 10800 (30 to 180 minutes)."
  }

  validation {
    condition     = var.max_run_seconds >= var.step_timeout_seconds
    error_message = "max_run_seconds must not be shorter than one step timeout."
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
