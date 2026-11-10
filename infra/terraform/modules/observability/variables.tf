variable "name_prefix" {
  description = "Prefix for names."
  type        = string
}

variable "region" {
  description = "AWS region."
  type        = string
}

variable "enable_dashboard" {
  description = "Create the dashboard. The first three dashboards in an account are free."
  type        = bool
  default     = true
}

variable "pipeline_enabled" {
  description = "Whether the pipeline state machine exists. A flag, not a null test, because the ARN is unknown at plan time."
  type        = bool
}

variable "neptune_enabled" {
  description = "Whether Neptune exists. A flag, not a null test, because the identifier is unknown at plan time."
  type        = bool
}

variable "state_machine_arn" {
  description = "Pipeline state machine ARN, or null."
  type        = string
  default     = null
}

variable "dead_letter_queue_names" {
  description = "Dead-letter queue names keyed by short name."
  type        = map(string)
}

variable "ecs_cluster_name" {
  description = "ECS cluster name."
  type        = string
}

variable "ecs_service_names" {
  description = "ECS service names keyed by role."
  type        = map(string)
}

variable "neptune_cluster_identifier" {
  description = "Neptune cluster identifier, or null when Neptune is not deployed."
  type        = string
  default     = null
}

variable "neptune_mode" {
  description = "provisioned or serverless. The capacity alarm exists only in serverless mode."
  type        = string
  default     = "provisioned"

  validation {
    condition     = contains(["provisioned", "serverless"], var.neptune_mode)
    error_message = "neptune_mode must be provisioned or serverless."
  }
}

variable "neptune_max_ncu" {
  description = "Neptune Serverless maximum capacity. The alarm fires when capacity reaches it. Unused in provisioned mode."
  type        = number
  default     = 2
}

variable "alarm_actions" {
  description = "SNS topic ARNs notified by alarms. Empty keeps alarms as dashboard signals only."
  type        = list(string)
  default     = []
}
