variable "name_prefix" {
  description = "Prefix for budget names."
  type        = string
}

variable "create_budgets" {
  description = "Create the project budget. Off by default: see the README budget mismatch note."
  type        = bool
  default     = false
}

variable "total_usd" {
  description = "Cumulative project ceiling in USD (instructor constraint, decision D2)."
  type        = number
  default     = 25
}

variable "alert_thresholds_usd" {
  description = "Absolute actual-spend alert thresholds in USD (instructor: 10, 15, 20)."
  type        = list(number)
  default     = [10, 15, 20]
}

variable "time_unit" {
  description = "Budget period. The ceiling is cumulative for the project, so the longest period is the closest fit."
  type        = string
  default     = "ANNUALLY"

  validation {
    condition     = contains(["MONTHLY", "QUARTERLY", "ANNUALLY"], var.time_unit)
    error_message = "time_unit must be MONTHLY, QUARTERLY or ANNUALLY."
  }
}

variable "notification_emails" {
  description = "Alert recipients. Supplied outside source control."
  type        = list(string)
  default     = []
}

variable "activate_cost_allocation_tags" {
  description = "Activate the required tags for cost allocation reports."
  type        = bool
  default     = false
}

variable "cost_allocation_tag_keys" {
  description = "Tag keys to activate."
  type        = list(string)
  default     = ["project", "phase", "owner", "environment", "cost-center"]
}
