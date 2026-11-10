# Example for a long-lived environment. Not used in Phase 12. Copy and review.
# Turns on PITR, S3 versioning, DynamoDB and Neptune deletion protection and the
# Neptune final snapshot. A KMS-key decision belongs here too (none is created).

owner       = "course-team"
cost_center = "course"
environment = "persistent"

persistent_environment = true
cleanup_mode           = false

egress_mode = "endpoints"

log_retention_days = 14
