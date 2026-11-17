variable "name_prefix" {
  description = "Prefix for names. The identity module builds ARN patterns from it, so the ECS cluster is named <prefix>-cluster."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
}

variable "vpc_id" {
  description = "VPC for the Cloud Map namespace (used only when enable_service_discovery is on)."
  type        = string
}

variable "enable_service_discovery" {
  description = "Create the Cloud Map private namespace and register the fast and slow services. Off by default: it adds a hosted zone (about $0.50 a month) and nothing in the design resolves the names."
  type        = bool
  default     = false
}

variable "app_subnet_ids" {
  description = "Private application subnets."
  type        = list(string)
}

variable "services_security_group_id" {
  description = "Security group of the fast and slow services."
  type        = string
}

variable "loader_security_group_id" {
  description = "Security group of the loader task."
  type        = string
}

variable "execution_role_arn" {
  description = "ECS task execution role."
  type        = string
}

variable "runtime_role_arn" {
  description = "Task role of the fast and slow services."
  type        = string
}

variable "loader_role_arn" {
  description = "Task role of the loader."
  type        = string
}

variable "secret_arns" {
  description = "Secret ARNs by name (auth-query-token, auth-read-token, auth-cursor-secret)."
  type        = map(string)
}

variable "image_tag" {
  description = "Git SHA tag of the image to run. Tags are immutable. The placeholder never exists, so a service started before a deploy cannot run."
  type        = string
  default     = "unset"
}

variable "force_delete_repositories" {
  description = "Delete repositories that still hold images. True only in cleanup mode."
  type        = bool
  default     = false
}

variable "log_retention_days" {
  description = "CloudWatch log retention in days. Phase 15: 7, so the raw lines of an experiment can be collected after the session."
  type        = number
  default     = 7
}

variable "desired_count" {
  description = "Desired task count of each service. 0 by default so nothing bills until the human raises it."
  type        = number
  default     = 0

  validation {
    condition     = var.desired_count >= 0 && var.desired_count <= 4
    error_message = "desired_count must be 0 to 4 (Phase 15: raised from 2 so the starting count can sit inside the autoscaling bounds; the bounded maximum is query_max_tasks)."
  }
}

variable "use_fargate_spot" {
  description = "Run services on Fargate Spot (about 70 percent cheaper, interruptible)."
  type        = bool
  default     = false
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64 (about 20 percent cheaper, needs an arm64 image)."
  type        = string
  default     = "X86_64"

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "service_cpu" {
  description = "Fargate CPU units for the services (256 is the smallest)."
  type        = number
  default     = 256

  validation {
    condition     = contains([256, 512, 1024, 2048, 4096], var.service_cpu)
    error_message = "service_cpu must be 256, 512, 1024, 2048 or 4096."
  }
}

variable "service_memory" {
  description = "Fargate memory in MiB for the services (512 is the smallest). Phase 15: the AwsRuntime holds the whole S3 export in the heap, so the session overlay raises it (PROVISIONAL until the local measurement, see infra/terraform/SESSION2.md)."
  type        = number
  default     = 512

  validation {
    condition = contains(
      lookup({
        "256"  = tolist([512, 1024, 2048])
        "512"  = range(1024, 4097, 1024)
        "1024" = range(2048, 8193, 1024)
        "2048" = range(4096, 16385, 1024)
        "4096" = range(8192, 30721, 1024)
      }, tostring(var.service_cpu), []),
      var.service_memory,
    )
    error_message = "service_memory is not a Fargate memory size for service_cpu. Allowed: 256 CPU 512, 1024 or 2048; 512 CPU 1024 to 4096; 1024 CPU 2048 to 8192; 2048 CPU 4096 to 16384; 4096 CPU 8192 to 30720 (1024 steps except 256 CPU)."
  }
}

variable "service_jvm_options" {
  description = "Phase 15: JAVA_TOOL_OPTIONS for the service containers, for example -XX:MaxRAMPercentage=80.0. Empty (default) sets nothing, so the image's own 75 percent applies. PROVISIONAL until the local heap measurement."
  type        = string
  default     = ""
}

variable "loader_cpu" {
  description = "Fargate CPU units for the loader."
  type        = number
  default     = 512
}

variable "loader_memory" {
  description = "Fargate memory in MiB for the loader."
  type        = number
  default     = 1024
}

### Phase 15 (WO-10): query service layout, internal ALB, scaling, worker, load client

variable "enable_services_stack" {
  description = "Create the session 2 resources beside the Phase 14 shape: the load-client ECR repository and lifecycle policy, the worker and load-client log groups and task definitions, the alb and load_client security groups with their seven rules, and Container Insights (enabled; disabled otherwise). Off by default so the Experiment A and B applies plan exactly the resources session 1 applied (OD-1). enable_load_balancer and enable_worker need it. Everything in it is free while idle."
  type        = bool
  default     = false
}

variable "service_layout" {
  description = "split (default, as before): two services, fast (AwsRuntime fast, port fast_port) and slow (AwsRuntime slow, port slow_port). combined: ONE service named query that runs AwsRuntime both (fast on fast_port and slow on slow_port in one task). combined is the Phase 15 layout: query records, jobs and idempotency are in-process, so /v1/evidence and /v1/explain work only when the same task served /v1/query, and one task loads the S3 export once instead of twice."
  type        = string
  default     = "split"

  validation {
    condition     = contains(["split", "combined"], var.service_layout)
    error_message = "service_layout must be split or combined."
  }
}

variable "enable_load_balancer" {
  description = "Create the INTERNAL application load balancer, its target group, listener and p95 latency alarm (P15-3). Off by default: it bills while it exists. The balancer sits in the private app subnets, has no public IP, and its security group admits only the load-client security group."
  type        = bool
  default     = false
}

variable "endpoints_security_group_id" {
  description = "Security group of the interface VPC endpoints (network module). The load-client task needs HTTPS to it for ECR, Secrets Manager and CloudWatch Logs."
  type        = string
}

variable "s3_prefix_list_id" {
  description = "Prefix list of the S3 gateway endpoint (network module). The load-client task pulls image layers from S3."
  type        = string
}

variable "alb_listener_port" {
  description = "Listener port of the internal ALB. HTTP: an internal ALB DNS name has no certificate, so the bearer token crosses the VPC unencrypted (accepted risk, SESSION2.md)."
  type        = number
  default     = 80
}

variable "health_check_grace_seconds" {
  description = "Seconds ECS ignores failing ALB health checks after a task starts. AwsRuntime opens no port until it has downloaded and parsed the whole S3 export (WO-8b), so a refused connection is expected at start. 300 covers image pull plus export load with margin; the real figure is measured in the local run (SESSION2.md). PROVISIONAL."
  type        = number
  default     = 300

  validation {
    condition     = var.health_check_grace_seconds >= 0 && var.health_check_grace_seconds <= 900
    error_message = "health_check_grace_seconds must be 0 to 900."
  }
}

variable "enable_autoscaling" {
  description = "Create the Application Auto Scaling target and policies of the query service, and (with enable_worker) the worker scaling. Off by default. When on, desired_count is only the starting value: autoscaling moves the count between the min and max, and a later apply resets desired_count to the variable (no applies run during an experiment)."
  type        = bool
  default     = false
}

variable "query_min_tasks" {
  description = "Minimum tasks of the query service while autoscaling is on."
  type        = number
  default     = 1

  validation {
    condition     = var.query_min_tasks >= 0 && var.query_min_tasks <= 4
    error_message = "query_min_tasks must be 0 to 4."
  }
}

variable "query_max_tasks" {
  description = "Maximum tasks of the query service. 4 tasks of 0.5 vCPU are 2 vCPU, far inside the 30 vCPU Fargate quota (P15-8); hard-capped at 6."
  type        = number
  default     = 4

  validation {
    condition     = var.query_max_tasks >= 1 && var.query_max_tasks <= 6 && var.query_max_tasks >= var.query_min_tasks
    error_message = "query_max_tasks must be 1 to 6 and not below query_min_tasks."
  }
}

variable "request_count_target" {
  description = "Target tracking target of ALBRequestCountPerTarget: requests per target per minute. 300 = 5 requests per second per task. Experiment D peaks at about 9.6 to 16 requests per second at 48 clients (think time 2 to 4 s, mean 3.0 s, plus 0 to 2 s latency), i.e. 576 to 960 per minute on one task, so the target is crossed 1.9 to 3.2 times at the top level."
  type        = number
  default     = 300

  validation {
    condition     = var.request_count_target >= 30 && var.request_count_target <= 6000
    error_message = "request_count_target must be 30 to 6000 requests per target per minute."
  }
}

variable "cpu_target_percent" {
  description = "Target tracking target of ECSServiceAverageCPUUtilization, percent. A second policy beside the request-count one: either metric above its target scales out, and scale-in needs both below."
  type        = number
  default     = 60

  validation {
    condition     = var.cpu_target_percent >= 20 && var.cpu_target_percent <= 90
    error_message = "cpu_target_percent must be 20 to 90."
  }
}

variable "query_scale_out_cooldown_seconds" {
  description = "Cooldown after a scale-out of the query service."
  type        = number
  default     = 60
}

variable "query_scale_in_cooldown_seconds" {
  description = "Cooldown after a scale-in of the query service. The 15-minute zero period of experiment D comfortably exceeds it."
  type        = number
  default     = 300
}

variable "p95_latency_alarm_seconds" {
  description = "Threshold of the p95 TargetResponseTime alarm (graph-retriever row of the course scaling table). Three consecutive one-minute periods at or above it raise the alarm. It signals only: no scaling action is attached."
  type        = number
  default     = 2
}

variable "alarm_actions" {
  description = "SNS topic ARNs notified by the alarms created here. Empty keeps them dashboard signals."
  type        = list(string)
  default     = []
}

variable "enable_worker" {
  description = "Create the embedding worker service (scholarchain.storage.EmbeddingWorkerMain). Off by default."
  type        = bool
  default     = false
}

variable "worker_role_arn" {
  description = "Task role of the embedding worker (identity module output worker). Null while the services stack is off: the role does not exist then."
  type        = string
  default     = null
}

variable "worker_environment" {
  description = "Nonsecret environment of the worker: exactly the names EmbeddingWorkerMain reads (WO-7 report). The queue URL, buckets and table prefix come from the environment module."
  type        = map(string)
  default     = {}
}

variable "worker_cpu" {
  description = "Fargate CPU units of the worker."
  type        = number
  default     = 512

  validation {
    condition     = contains([256, 512, 1024], var.worker_cpu)
    error_message = "worker_cpu must be 256, 512 or 1024."
  }
}

variable "worker_memory" {
  description = "Fargate memory in MiB of the worker. 1024 with 512 CPU: JVM plus four AWS SDK clients, one message in flight, text at most 50,000 bytes."
  type        = number
  default     = 1024

  validation {
    condition = contains(
      lookup({
        "256"  = tolist([512, 1024, 2048])
        "512"  = range(1024, 4097, 1024)
        "1024" = range(2048, 8193, 1024)
      }, tostring(var.worker_cpu), []),
      var.worker_memory,
    )
    error_message = "worker_memory is not a Fargate memory size for worker_cpu (256: 512, 1024, 2048; 512: 1024 to 4096; 1024: 2048 to 8192)."
  }
}

variable "worker_desired_count" {
  description = "Starting count of the worker service."
  type        = number
  default     = 1

  validation {
    condition     = var.worker_desired_count >= 0 && var.worker_desired_count <= 4
    error_message = "worker_desired_count must be 0 to 4."
  }
}

variable "worker_min_tasks" {
  description = "Minimum worker tasks while autoscaling is on."
  type        = number
  default     = 1

  validation {
    condition     = var.worker_min_tasks >= 0 && var.worker_min_tasks <= 4
    error_message = "worker_min_tasks must be 0 to 4."
  }
}

variable "worker_max_tasks" {
  description = "Maximum worker tasks. One task handles one message at a time, about 2 to 3 messages per second (UNVERIFIED), so 4 tasks stay under the Titan V2 quota of 300,000 tokens per minute (about 16 messages per second at 300 tokens each). Hard-capped at 6."
  type        = number
  default     = 4

  validation {
    condition     = var.worker_max_tasks >= 1 && var.worker_max_tasks <= 6 && var.worker_max_tasks >= var.worker_min_tasks
    error_message = "worker_max_tasks must be 1 to 6 and not below worker_min_tasks."
  }
}

variable "worker_backlog_high" {
  description = "Backlog per worker task (visible messages divided by running tasks) at or above which the worker scales out. 150 is about one minute of work for one task at 2.5 messages per second."
  type        = number
  default     = 150
}

variable "worker_backlog_low" {
  description = "Backlog per worker task below which, for five consecutive minutes, the worker scales in."
  type        = number
  default     = 20
}

variable "load_client_image_tag" {
  description = "Tag of the load-client image in the scholarchain-load-client repository. Tags are immutable. The placeholder never exists, so a run-task before a push cannot start."
  type        = string
  default     = "unset"
}

variable "load_client_cpu" {
  description = "Fargate CPU units of the load-client task (1 vCPU: 48 client threads, about 16 requests per second)."
  type        = number
  default     = 1024
}

variable "load_client_memory" {
  description = "Fargate memory in MiB of the load-client task."
  type        = number
  default     = 2048

  validation {
    condition     = contains(range(2048, 8193, 1024), var.load_client_memory) || (var.load_client_cpu < 1024 && contains(range(1024, 4097, 1024), var.load_client_memory))
    error_message = "load_client_memory is not a Fargate memory size for load_client_cpu (1024 CPU: 2048 to 8192; 512 CPU: 1024 to 4096)."
  }
}

variable "fast_port" {
  description = "Port of the fast service (evidence, graph, explain). Proposed: no production entrypoint exists yet."
  type        = number
  default     = 8080
}

variable "slow_port" {
  description = "Port of the slow service (query and jobs). Proposed."
  type        = number
  default     = 8081
}

variable "container_environment" {
  description = "Nonsecret environment variables of the fast and slow services. The names are a PROPOSED contract with the service owners: no service code read them when this was written (UNVERIFIED for the current services code). The loader does not use this map."
  type        = map(string)
  default     = {}
}

variable "loader_environment" {
  description = "Nonsecret environment variables of the loader task. Exactly the names scholarchain.rag.AwsIngestMain reads: AWS_REGION, SCHOLARCHAIN_DATA_BUCKET, SCHOLARCHAIN_VECTOR_BUCKET, SCHOLARCHAIN_TABLE_PREFIX, and (only with Neptune) SCHOLARCHAIN_NEPTUNE_HOST and SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN. No credential belongs here: the task role supplies credentials."
  type        = map(string)
  default     = {}
}
