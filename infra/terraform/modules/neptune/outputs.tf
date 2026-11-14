output "cluster_identifier" {
  description = "Cluster identifier (CloudWatch dimension DBClusterIdentifier)."
  value       = aws_neptune_cluster.this.cluster_identifier
}

output "mode" {
  description = "provisioned or serverless."
  value       = var.mode
}

output "endpoint" {
  description = "Writer endpoint."
  value       = aws_neptune_cluster.this.endpoint
}

output "reader_endpoint" {
  description = "Reader endpoint."
  value       = aws_neptune_cluster.this.reader_endpoint
}

output "port" {
  description = "Listener port."
  value       = aws_neptune_cluster.this.port
}

output "cluster_resource_id" {
  description = "Cluster resource ID used in neptune-db IAM resource ARNs."
  value       = aws_neptune_cluster.this.cluster_resource_id
}

output "bulk_load_role_arn" {
  description = "Role the cluster assumes (trusts rds.amazonaws.com, attached through iam_roles) to read graph/neptune/ for bulk loads. Passed to the loader as SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN."
  value       = aws_iam_role.bulk_load.arn
}
