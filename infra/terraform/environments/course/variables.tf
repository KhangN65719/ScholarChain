### Identity and tags

variable "region" {
  description = "AWS region. Single source of truth for the provider, endpoint service names, ARNs and the AZ check. The account is LOCKED to us-east-2 (Ohio) by Phase 14 decision D1."
  type        = string
  default     = "us-east-2"

  validation {
    condition     = var.region == "us-east-2"
    error_message = "Phase 14 decision D1: the AWS account is locked to us-east-2 (Ohio). Any other Region needs the irreversible account change that removes the spend limit, and is never suggested. This project deploys to us-east-2 only."
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
  description = "Create a project budget with the alert thresholds. Off by default. Phase 14 D1 account facts: the account has USD 20 credits, an AWS-enforced project spend limit of USD 20 with pause idle resources on, and an AWS Budget of USD 25 with alerts at 10, 15 and 20 that was created outside Terraform. This resource would be a second budget, capped at the code ceiling of 20 (D6)."
  type        = bool
  default     = false
}

variable "budget_total_usd" {
  description = "Cumulative ceiling of the optional Terraform budget, at most 20 USD (Phase 14 D6: the code ceiling matches the AWS spend limit; the existing USD 25 AWS Budget is the outer alert and is not managed here). Used only when create_budgets is true."
  type        = number
  default     = 20

  validation {
    condition     = var.budget_total_usd > 0 && var.budget_total_usd <= 20
    error_message = "budget_total_usd must be greater than 0 and at most 20 (Phase 14 decision D6, the AWS-enforced project spend limit)."
  }
}

variable "budget_alert_thresholds_usd" {
  description = "Actual-spend alerts in USD (10, 15, 20, as on the existing USD 25 AWS Budget). Used only when create_budgets is true."
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
  description = "Two Availability Zones, both in var.region. Zone names are per account: a and b exist in us-east-2, but whether they are the zones a plan can use is only confirmed by the first plan."
  type        = list(string)
  default     = ["us-east-2a", "us-east-2b"]

  validation {
    condition     = length(var.availability_zones) == 2 && alltrue([for az in var.availability_zones : startswith(az, var.region)])
    error_message = "Give exactly two Availability Zones, each inside var.region (us-east-2, Phase 14 decision D1)."
  }
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
  description = "Instance class in provisioned mode. Default db.t4g.medium (course.tfvars). The S overlay sets db.r6g.xlarge for the bulk load (work order 114: OUT_OF_MEMORY_ERROR on db.t4g.medium in S run 10). The module sets apply_immediately on the instance, so a change is applied at once as an in-place modify with a restart. No allow-list: any class Neptune orders for the engine version is accepted."
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
  description = "Neptune engine version. 1.4.5.1 is confirmed orderable for db.t4g.medium in us-east-2 (human read-only CLI check, 2026-11-13, docs/agent-runs/phase-14/checks-3.md). The module's parameter_group_family default neptune1.4 matches it."
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
  description = "Claude Haiku 4.5 inference profile ID. Geo (US) profile: documented for source us-east-2 (docs/agent-runs/phase-14/evidence-availability-us-east-2.md). In-Region is not supported for this model in us-east-2. The human confirmed a live invoke of this profile in us-east-2 (Phase 14 D5, as relayed by the orchestrator; the log is not in this work order's evidence). global.anthropic.claude-haiku-4-5-20251001-v1:0 is the Region-neutral alternative. Cross-Region routing is Bedrock-side behavior, not a Terraform Region."
  type        = string
  default     = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "bedrock_haiku_model_id" {
  description = "Foundation model ID behind the profile. UNVERIFIED."
  type        = string
  default     = "anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "bedrock_haiku_regions" {
  description = "Regions the US inference profile may route to (destinations, not deployment Regions). Each needs a foundation-model ARN. Documented for source us-east-2: us-east-1, us-east-2, us-west-2. The us-east-1 entry is a routing destination only; nothing is deployed there."
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
  description = "EMR release label. Design B (Phase 14 D21): emr-7.10.0 with the Hadoop application only; Spark 4.0.5 runs on YARN from our own distribution (scripts/emr/). UNVERIFIED until the first cluster, see the module."
  type        = string
  default     = "emr-7.10.0"
}

variable "emr_install_java_bootstrap" {
  description = "Add the optional bootstrap action that installs Corretto (scripts/emr/install-java-bootstrap.sh). Off by default. Turn it on only after the capability check reports no usable JDK 17, and only after scripts/upload-inputs.sh has uploaded the script. A failing bootstrap action fails the cluster launch."
  type        = bool
  default     = false
}

variable "emr_bootstrap_java_major" {
  description = "Corretto major version for the bootstrap: 17 (default) or 21 (when the job jars need Java 21 bytecode). Used only with emr_install_java_bootstrap."
  type        = number
  default     = 17

  validation {
    condition     = contains([17, 21], var.emr_bootstrap_java_major)
    error_message = "emr_bootstrap_java_major must be 17 or 21."
  }
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

variable "loader_cpu" {
  description = "Fargate CPU units of the loader task (work order 108). Default 512, the module default. The S overlay sets 2048 after S run 8 died with OutOfMemoryError in the Neptune graph phase at 512 CPU and 1024 MiB. Must pair with loader_memory as Fargate allows (for 2048: 4096 to 16384 MiB in 1024 steps)."
  type        = number
  default     = 512

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096], var.loader_cpu)
    error_message = "loader_cpu must be 256, 512, 1024, 2048 or 4096 (larger Fargate sizes are not needed by the loader and are refused as a cost guard)."
  }
}

variable "loader_memory" {
  description = "Fargate memory in MiB of the loader task (work order 108). Default 1024, the module default. The S overlay sets 8192. The JVM heap is 75 percent of it (Dockerfile MaxRAMPercentage), so 8192 gives about 6 GiB. Ephemeral storage is not set and stays at the Fargate default of 20 GiB."
  type        = number
  default     = 1024

  validation {
    condition = contains(
      lookup({
        "256"  = tolist([512, 1024, 2048])
        "512"  = range(1024, 4097, 1024)
        "1024" = range(2048, 8193, 1024)
        "2048" = range(4096, 16385, 1024)
        "4096" = range(8192, 30721, 1024)
      }, tostring(var.loader_cpu), []),
      var.loader_memory,
    )
    error_message = "loader_memory is not a Fargate memory size for loader_cpu. Allowed: 256 CPU 512, 1024 or 2048; 512 CPU 1024 to 4096; 1024 CPU 2048 to 8192; 2048 CPU 4096 to 16384; 4096 CPU 8192 to 30720 (1024 steps except 256 CPU)."
  }
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
  description = "Timeout of one whole pipeline execution, 1800 to 10800 seconds. Default 10800 (the 150-minute tier L script cap plus 30 minutes). Set per run in the experiment tfvars: S 7200 (work order 49; was 5400), M 7200, L 10800. When it fires, CleanupOnFailure does NOT run (same as StopExecution): an EMR cluster left behind falls to the 900 s idle auto-termination and to the human's cleanup. Rule A (enforced in the workflow module, changed in work order 49): pipeline_create_cluster_timeout_seconds + pipeline_emr_step_timeout_seconds + 600 must not exceed this value. Known limitation, not enforced (human decision O1, phase 13 D10): create + 300 (fetch) + EMR step + 900 + pipeline_loader_timeout_seconds can exceed it (infra README, Pipeline bounds). The S overlay now sets 7200 (work order 49). A loader-only execution (work order 86) has no EMR state, so Rule A does not apply to it; its own bound is pipeline_loader_timeout_seconds, which should stay below this value."
  type        = number
  default     = 10800

  validation {
    condition     = var.pipeline_max_run_seconds >= 1800 && var.pipeline_max_run_seconds <= 10800
    error_message = "pipeline_max_run_seconds must be 1800 to 10800."
  }
}

variable "pipeline_create_cluster_timeout_seconds" {
  description = "Timeout of the CreateCluster step, 300 to 3600 seconds. Default and all tiers 1440 (0.4 h, the HIGH cluster launch overhead of COST_ESTIMATE.md section 1.2). On timeout the execution goes to Failed with NO cleanup state (FetchScripts, ChooseStep with no matching mode, CapabilityStep and PipelineStep route to CleanupOnFailure; CreateCluster, TerminateCluster and RunLoader go straight to Failed). Whether Step Functions terminates the half-created cluster is UNVERIFIED."
  type        = number
  default     = 1440

  validation {
    condition     = var.pipeline_create_cluster_timeout_seconds >= 300 && var.pipeline_create_cluster_timeout_seconds <= 3600
    error_message = "pipeline_create_cluster_timeout_seconds must be 300 to 3600."
  }
}

variable "pipeline_emr_step_timeout_seconds" {
  description = "Timeout of the one pipeline script step (Hadoop, ER, Spark, export and publish together), 300 to 7200 seconds. Default 3600. Set per tier in the experiment tfvars: S 4800 (work order 52; was 3600 under work order 49 and 1800 before), M 2700, L 3600 (M and L unchanged from repair round 2; the estimate's HIGH Hadoop plus Spark time is S 2160, M 3600, L 7200 s, which does not fit under the current execution timeouts: accepted under human decision O1, see the infra README, Pipeline bounds). On timeout the execution goes to CleanupOnFailure and then Failed. A legitimate single step longer than this value is stopped. Tied to pipeline_max_run_seconds by Rule A (checked in the workflow module)."
  type        = number
  default     = 3600

  validation {
    condition     = var.pipeline_emr_step_timeout_seconds >= 300 && var.pipeline_emr_step_timeout_seconds <= 7200
    error_message = "pipeline_emr_step_timeout_seconds must be 300 to 7200."
  }
}

variable "pipeline_loader_timeout_seconds" {
  description = "Timeout of the RunLoader step, 300 to 7200 seconds. Default 3600. Set per tier in the experiment tfvars: S 5400 (work order 86, S run 6: the loader hit the old 1800 and needs about 52 more minutes, so the loader-only mode gets 5400; it fits the 7200 s execution timeout only when it runs alone), M 2700, L 3600 (M and L unchanged from repair round 2; the estimate's HIGH loader time is S 3600, M 7200, L 14400 s, which does not fit the current caps: accepted under human decision O1, see the infra README, Pipeline bounds). On timeout the execution goes to Failed with NO cleanup state; in mode pipeline the cluster is already terminated by then, and mode loader-only has none. Rule A does not involve this value."
  type        = number
  default     = 3600

  validation {
    condition     = var.pipeline_loader_timeout_seconds >= 300 && var.pipeline_loader_timeout_seconds <= 7200
    error_message = "pipeline_loader_timeout_seconds must be 300 to 7200."
  }
}
