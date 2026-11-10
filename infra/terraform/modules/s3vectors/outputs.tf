output "bucket_name" {
  description = "Vector bucket name."
  value       = aws_s3vectors_vector_bucket.this.vector_bucket_name
}

output "bucket_arn" {
  description = "Vector bucket ARN (provider attribute vector_bucket_arn)."
  value       = aws_s3vectors_vector_bucket.this.vector_bucket_arn
}

output "index_arns" {
  description = "Index ARNs keyed by index name (provider attribute index_arn)."
  value       = { for name, index in aws_s3vectors_index.this : name => index.index_arn }
}

output "index_names" {
  description = "Index names."
  value       = sort(tolist(local.index_names))
}

output "dimension" {
  description = "Dimension of every index."
  value       = var.dimension
}
