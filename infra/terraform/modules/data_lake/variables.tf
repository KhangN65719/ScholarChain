variable "name_prefix" {
  description = "Prefix for bucket names."
  type        = string
}

variable "account_id" {
  description = "AWS account ID, part of the globally unique bucket name."
  type        = string
}

variable "force_destroy" {
  description = "Let Terraform delete non-empty buckets. Off by default, true only in cleanup mode."
  type        = bool
  default     = false
}

variable "versioning_enabled" {
  description = "Enable bucket versioning. Off in the course environment (object keys are immutable by design), on in a persistent one."
  type        = bool
  default     = false
}

variable "scratch_expiry_days" {
  description = "Days before objects under tmp/ expire."
  type        = number
  default     = 7
}

variable "ops_log_expiry_days" {
  description = "Days before EMR and run logs expire."
  type        = number
  default     = 14
}

variable "noncurrent_expiry_days" {
  description = "Days before noncurrent versions expire when versioning is on."
  type        = number
  default     = 30
}
