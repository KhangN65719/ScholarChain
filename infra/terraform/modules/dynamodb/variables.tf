variable "table_prefix" {
  description = "Table name prefix. The application receives it as TableNames(prefix)."
  type        = string
}

variable "deletion_protection" {
  description = "DynamoDB deletion protection. Off for the ephemeral course environment."
  type        = bool
  default     = false
}

variable "point_in_time_recovery" {
  description = "Point-in-time recovery. Off in the course environment (bills by table size), on in a persistent one."
  type        = bool
  default     = false
}
