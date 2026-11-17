variable "service_cpu" {
  type    = number
  default = 256
}

variable "log_retention_days" {
  type    = number
  default = 7
}

variable "answer_model" {
  type    = string
  default = "stub"
}

variable "bedrock_max_calls" {
  type    = number
  default = 10
}

variable "bedrock_model_id" {
  type    = string
  default = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}

variable "graph_run_id" {
  type = string
}

variable "export_run_id" {
  type    = string
  default = null
}

variable "enable_services_stack" {
  type    = bool
  default = false
}

variable "service_layout" {
  type    = string
  default = "split"
}

variable "enable_load_balancer" {
  type    = bool
  default = false
}

variable "enable_worker" {
  type    = bool
  default = false
}

variable "query_max_tasks" {
  type    = number
  default = 6
}
