### Identity and tags

variable "region" {
  description = "AWS region. The project is fixed to us-east-1."
  type        = string
  default     = "us-east-1"

  validation {
    condition     = var.region == "us-east-1"
    error_message = "This project deploys to us-east-1 only."
  }
}

variable "project" {
  description = "Required tag: project."
  type        = string
  default     = "scholarchain"
}

variable "phase" {
  description = "Required tag: phase that created the resources."
  type        = string
  default     = "12"
}

variable "owner" {
  description = "Required tag: owner. A team or handle, not a secret."
  type        = string
}

variable "environment" {
  description = "Required tag and name part: course or persistent."
  type        = string
  default     = "course"
}

variable "cost_center" {
  description = "Required tag: cost-center."
  type        = string
}

### Safety and cleanup

variable "persistent_environment" {
  description = "True for a long-lived environment: turns on PITR, S3 versioning, deletion protection and final snapshots. False for the ephemeral course environment."
  type        = bool
  default     = false
}

variable "cleanup_mode" {
  description = "Explicit teardown switch. Turns on force_destroy everywhere, turns off deletion protection and the final snapshot, and shortens the secret recovery window to zero. Apply it, then destroy."
  type        = bool
  default     = false
}

### Budgets

variable "create_budgets" {
  description = "Create the project budget with the instructor alert thresholds. Off by default because $25 and $50 budgets already exist."
  type        = bool
  default     = false
}

variable "budget_total_usd" {
  description = "Cumulative project ceiling (instructor: $25). Used only when create_budgets is true."
  type        = number
  default     = 25

  validation {
    condition     = var.budget_total_usd > 0 && var.budget_total_usd <= 25
    error_message = "budget_total_usd must be greater than 0 and at most 25 (the instructor's ceiling)."
  }
}

variable "budget_alert_thresholds_usd" {
  description = "Actual-spend alerts in USD (instructor: 10, 15, 20). Used only when create_budgets is true."
  type        = list(number)
  default     = [10, 15, 20]

  validation {
    condition     = length(var.budget_alert_thresholds_usd) >= 1 && length(var.budget_alert_thresholds_usd) <= 5 && alltrue([for t in var.budget_alert_thresholds_usd : t > 0 && t <= var.budget_total_usd])
    error_message = "Give 1 to 5 alert thresholds, each above 0 and not above budget_total_usd."
  }
}

variable "budget_notification_emails" {
  description = "Alert recipients. Pass with -var at plan time, not in a committed file."
  type        = list(string)
  default     = []
}

variable "activate_cost_allocation_tags" {
  description = "Activate the required tags for cost allocation."
  type        = bool
  default     = false
}

### Network

variable "availability_zones" {
  description = "Two Availability Zones."
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b"]
}

variable "vpc_cidr" {
  description = "VPC CIDR."
  type        = string
  default     = "10.20.0.0/16"
}

variable "egress_mode" {
  description = "endpoints (default), nat or none. See the README egress table."
  type        = string
  default     = "endpoints"
}

variable "interface_endpoint_services" {
  description = "Interface endpoints created in endpoints mode (8 by default, 9 with bedrock-runtime when Bedrock is on). s3vectors and sqs are required by the in-VPC services and loader; removing them breaks vector queries and queue calls in endpoints mode."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sts", "elasticmapreduce", "s3vectors", "sqs"]
}

variable "interface_endpoint_az_count" {
  description = "AZs that get endpoint network interfaces (1 or 2)."
  type        = number
  default     = 1
}

### Optional services

variable "enable_neptune" {
  description = "Create the Neptune cluster. Off by default: it needs the Paid plan and bills while it exists, so create it for a session and destroy it after."
  type        = bool
  default     = false
}

variable "neptune_mode" {
  description = "provisioned (default: one db.t4g.medium instance, no replica) or serverless (1 NCU minimum, no scale to zero)."
  type        = string
  default     = "provisioned"

  validation {
    condition     = contains(["provisioned", "serverless"], var.neptune_mode)
    error_message = "neptune_mode must be provisioned or serverless."
  }
}

variable "neptune_instance_class" {
  description = "Instance class in provisioned mode."
  type        = string
  default     = "db.t4g.medium"
}

variable "neptune_max_ncu" {
  description = "Neptune Serverless maximum capacity (serverless mode only)."
  type        = number
  default     = 2

  validation {
    condition     = var.neptune_max_ncu >= 1 && var.neptune_max_ncu <= 4
    error_message = "neptune_max_ncu must be 1 to 4."
  }
}

variable "enable_service_discovery" {
  description = "Create the Cloud Map namespace and register the fast and slow services. Off by default (about $0.50 a month, and nothing resolves the names)."
  type        = bool
  default     = false
}

variable "neptune_engine_version" {
  description = "Neptune engine version (UNVERIFIED, see the module)."
  type        = string
  default     = "1.4.5.1"
}

variable "enable_pipeline" {
  description = "Create the Step Functions pipeline. No idle cost. A run needs the Paid plan because EMR does."
  type        = bool
  default     = true
}

variable "enable_dashboard" {
  description = "Create the CloudWatch dashboard."
  type        = bool
  default     = true
}

variable "alarm_sns_topic_arns" {
  description = "SNS topics notified by alarms. Supplied outside source."
  type        = list(string)
  default     = []
}

### Bedrock (decision D2a)

variable "enable_bedrock" {
  description = "Grant Bedrock invoke on the exact model ARNs below. Off by default: the hash embedder and offline stub are the fallback."
  type        = bool
  default     = false
}

variable "bedrock_haiku_profile_id" {
  description = "Claude Haiku 4.5 inference profile ID. UNVERIFIED: confirm the ID in Phase 14 before enabling."
  type        = string
  default     = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "bedrock_haiku_model_id" {
  description = "Foundation model ID behind the profile. UNVERIFIED."
  type        = string
  default     = "anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "bedrock_haiku_regions" {
  description = "Regions the inference profile may route to. Each needs a foundation-model ARN. UNVERIFIED: confirm in Phase 14."
  type        = list(string)
  default     = ["us-east-1", "us-east-2", "us-west-2"]
}

variable "bedrock_titan_model_id" {
  description = "Titan Text Embeddings V2 model ID."
  type        = string
  default     = "amazon.titan-embed-text-v2:0"
}

### Vectors

variable "vector_dimension" {
  description = "Embedding dimension (256, 384, 512 or 1024). 384 is the hash embedder. WARNING: changing this value on an environment that already holds vectors replaces all five S3 Vectors indexes in place and DESTROYS their vectors (the dimension is not part of the index names). A change is refused at plan time unless acknowledge_vector_loss is true. A 384-dimension fallback run needs its own environment or a state-aware plan."
  type        = number
  default     = 256

  validation {
    condition     = contains([256, 384, 512, 1024], var.vector_dimension)
    error_message = "vector_dimension must be 256, 384, 512 or 1024."
  }
}

variable "acknowledge_vector_loss" {
  description = "Set true, for one apply, to allow a vector_dimension change that replaces the S3 Vectors indexes and destroys their vectors. Leave false otherwise. Set it back to false afterwards, in an apply of its own with vector_dimension unchanged. KNOWN GAP: flipping it from true to false in the same apply as another dimension change can replace the indexes without a plan-time refusal (the dimension record's output is unknown at plan time). The second layer is the plan review: plan.sh flags aws_s3vectors_index replacements and the approval record needs STATEFUL_CHANGE_ACKNOWLEDGED=yes. UNVERIFIED until a plan."
  type        = bool
  default     = false
}

### EMR

variable "emr_release_label" {
  description = "EMR release label (UNVERIFIED, see the module)."
  type        = string
  default     = "emr-7.10.0"
}

variable "emr_master_instance_type" {
  description = "Primary node type."
  type        = string
  default     = "m5.xlarge"
}

variable "emr_core_instance_count" {
  description = "Core nodes (0 to 4). 0 is a one-node cluster. 4 is the Phase 15 elasticity run (see elasticity-example.tfvars)."
  type        = number
  default     = 0

  validation {
    condition     = var.emr_core_instance_count >= 0 && var.emr_core_instance_count <= 4
    error_message = "emr_core_instance_count must be 0 to 4."
  }
}

variable "emr_managed_scaling_max_units" {
  description = "EMR managed scaling cap in instance units (1 to 7). Default 3. Must cover every fixed node: 1 primary + core nodes + Spot task nodes (task nodes count only when there is a core node), at most 1 + 4 + 2 = 7. The elasticity example uses 5 (master plus 4 core nodes)."
  type        = number
  default     = 3

  validation {
    condition     = var.emr_managed_scaling_max_units >= 1 && var.emr_managed_scaling_max_units <= 7
    error_message = "emr_managed_scaling_max_units must be 1 to 7."
  }

  validation {
    condition     = var.emr_managed_scaling_max_units >= 1 + var.emr_core_instance_count + (var.emr_core_instance_count > 0 ? var.emr_task_spot_instance_count : 0)
    error_message = "emr_managed_scaling_max_units must be at least 1 + core nodes + Spot task nodes (task nodes count only with a core node). Raise the cap or lower the node counts."
  }
}

variable "emr_task_spot_instance_count" {
  description = "Spot task nodes (0 to 2), effective only with core nodes."
  type        = number
  default     = 0

  validation {
    condition     = var.emr_task_spot_instance_count >= 0 && var.emr_task_spot_instance_count <= 2
    error_message = "emr_task_spot_instance_count must be 0 to 2."
  }
}

variable "emr_idle_timeout_seconds" {
  description = "Idle auto-termination."
  type        = number
  default     = 900
}

### Runtime

variable "image_tag" {
  description = "Git SHA image tag, supplied by the deploy script."
  type        = string
  default     = "unset"
}

variable "service_desired_count" {
  description = "Desired count of each service. 0 by default."
  type        = number
  default     = 0
}

variable "use_fargate_spot" {
  description = "Run services on Fargate Spot."
  type        = bool
  default     = false
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64."
  type        = string
  default     = "X86_64"
}

variable "log_retention_days" {
  description = "CloudWatch log retention."
  type        = number
  default     = 3
}

variable "deployer_trusted_principal_arns" {
  description = "Principals that may assume the deployment role. Empty creates no role. The role does not run terraform apply: deploy.sh needs the human's broad credentials (see the README, Who runs what)."
  type        = list(string)
  default     = []
}

variable "deployer_require_mfa" {
  description = "Require MFA in the deployer role's trust policy. Off by default: the account's IAM users are programmatic-only."
  type        = bool
  default     = false
}

### Pipeline bounds

variable "pipeline_max_run_seconds" {
  description = "Timeout of one whole pipeline execution, 1800 to 10800 seconds. Default 10800 (the 150-minute tier L script cap plus 30 minutes). Set per run in the experiment tfvars: S 5400, M 7200, L 10800. When it fires, CleanupOnFailure does NOT run (same as StopExecution): an EMR cluster left behind falls to the 900 s idle auto-termination and to the human's cleanup."
  type        = number
  default     = 10800

  validation {
    condition     = var.pipeline_max_run_seconds >= 1800 && var.pipeline_max_run_seconds <= 10800
    error_message = "pipeline_max_run_seconds must be 1800 to 10800."
  }
}

variable "pipeline_step_timeout_seconds" {
  description = "Timeout of each pipeline step, 300 to 7200 seconds. Default 3600. Set per tier in the experiment tfvars: S 1800, M 2700, L 3600. With max run S 5400, M 7200, L 10800, a Hadoop step that runs to the step limit followed by a hung Spark step is cut by the step timeout (and routed to CleanupOnFailure) as long as cluster start stays under 1800 s on S and M (3600 s on L). At the old uniform 3600 the run-level timeout fired first on S and M, and that path skips CleanupOnFailure. A legitimate single step longer than this value is stopped, so raise it together with pipeline_max_run_seconds. Must not exceed pipeline_max_run_seconds (checked in the workflow module)."
  type        = number
  default     = 3600

  validation {
    condition     = var.pipeline_step_timeout_seconds >= 300 && var.pipeline_step_timeout_seconds <= 7200
    error_message = "pipeline_step_timeout_seconds must be 300 to 7200."
  }
}
