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
  description = "CloudWatch log retention."
  type        = number
  default     = 3
}

variable "desired_count" {
  description = "Desired task count of each service. 0 by default so nothing bills until the human raises it."
  type        = number
  default     = 0

  validation {
    condition     = var.desired_count >= 0 && var.desired_count <= 2
    error_message = "desired_count must be 0 to 2."
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
}

variable "service_memory" {
  description = "Fargate memory in MiB for the services (512 is the smallest)."
  type        = number
  default     = 512
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
  description = "Nonsecret environment variables. The names are a PROPOSED contract with the application owners."
  type        = map(string)
  default     = {}
}
