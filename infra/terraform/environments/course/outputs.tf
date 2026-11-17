output "account_id" {
  description = "Account the configuration targets. Compare with the approved account before any apply."
  value       = local.account_id
}

output "egress_mode" {
  description = "Network egress mode in effect."
  value       = module.network.egress_mode
}

output "cpu_architecture" {
  description = "Fargate CPU architecture in effect. deploy.sh checks it against the docker platform it builds for."
  value       = var.cpu_architecture
}

output "data_bucket" {
  description = "Data bucket."
  value       = module.data_lake.data_bucket
}

output "artifacts_bucket" {
  description = "Artifacts bucket."
  value       = module.data_lake.artifacts_bucket
}

output "vector_bucket" {
  description = "S3 Vectors bucket."
  value       = module.s3vectors.bucket_name
}

output "dynamodb_table_names" {
  description = "DynamoDB table names."
  value       = module.dynamodb.table_names
}

output "ecr_repository_urls" {
  description = "ECR repository URLs."
  value       = module.runtime.repository_urls
}

output "state_machine_arn" {
  description = "Pipeline state machine ARN, or null."
  value       = module.workflow.state_machine_arn
}

output "role_arns" {
  description = "Role ARNs for the IAM privilege review."
  value       = module.identity.role_arns
}

output "secret_arns" {
  description = "Secret ARNs (references only, no values)."
  value       = module.identity.secret_arns
}

output "alb_dns_name" {
  description = "Internal ALB DNS name, or null. The load client's base URL is http://<this> (listener port 80). Not reachable from outside the VPC."
  value       = module.runtime.alb_dns_name
}

output "ecs_cluster_name" {
  description = "ECS cluster name (SCHOLARCHAIN_LC_CLUSTER)."
  value       = module.runtime.cluster_name
}

output "query_service_name" {
  description = "ECS service that serves POST /v1/query."
  value       = module.runtime.query_service_name
}

output "worker_service_name" {
  description = "Embedding worker ECS service, or null."
  value       = module.runtime.worker_service_name
}

output "load_client" {
  description = "Load-client task definition family, container name, log group, stream prefix and security group (SCHOLARCHAIN_LC_*)."
  value       = module.runtime.load_client
}

output "app_subnet_ids" {
  description = "Private app subnets (SCHOLARCHAIN_LC_SUBNETS)."
  value       = module.network.app_subnet_ids
}

output "embedding_queue_url" {
  description = "embedding-work queue URL (Experiments C and F)."
  value       = module.workflow.queue_urls["embedding-work"]
}

output "neptune_endpoint" {
  description = "Neptune endpoint, or null when not deployed."
  value       = one(module.neptune[*].endpoint)
}
