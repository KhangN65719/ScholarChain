output "cluster_arn" {
  description = "ECS cluster ARN."
  value       = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "service_names" {
  description = "ECS service names keyed by role."
  value       = { for role, service in aws_ecs_service.this : role => service.name }
}

output "loader_task_definition_arn" {
  description = "Loader task definition ARN without revision (always the latest active)."
  value       = aws_ecs_task_definition.loader.arn_without_revision
}

output "repository_urls" {
  description = "ECR repository URLs."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}

output "service_discovery_names" {
  description = "Internal DNS names of the services. Empty when service discovery is off."
  value       = { for role in keys(aws_service_discovery_service.this) : role => "${role}.${aws_service_discovery_private_dns_namespace.this[0].name}" }
}
