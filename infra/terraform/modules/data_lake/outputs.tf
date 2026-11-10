output "data_bucket" {
  description = "Data bucket name (prefixes raw, curated, graph, semantic-context, reports, logs, tmp)."
  value       = aws_s3_bucket.this["data"].bucket
}

output "data_bucket_arn" {
  description = "Data bucket ARN."
  value       = aws_s3_bucket.this["data"].arn
}

output "artifacts_bucket" {
  description = "Artifacts bucket name (prefixes jars, bootstrap, emr-logs, tmp)."
  value       = aws_s3_bucket.this["artifacts"].bucket
}

output "artifacts_bucket_arn" {
  description = "Artifacts bucket ARN."
  value       = aws_s3_bucket.this["artifacts"].arn
}
