output "cluster_arn" {
  description = "ECS cluster ARN."
  value       = aws_ecs_cluster.this.arn
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "service_names" {
  description = "ECS service names keyed by role (query when service_layout is combined, fast and slow otherwise)."
  value       = { for role, service in aws_ecs_service.this : role => service.name }
}

output "query_service_name" {
  description = "Name of the ECS service that serves POST /v1/query."
  value       = aws_ecs_service.this[local.query_service].name
}

output "worker_service_name" {
  description = "Embedding worker service name, or null when the worker is off."
  value       = one(aws_ecs_service.worker[*].name)
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

output "alb_dns_name" {
  description = "DNS name of the internal ALB, or null. Reachable only from the load-client security group. The base URL for the load client is http://<this>[:port]."
  value       = one(aws_lb.this[*].dns_name)
}

output "alb_arn_suffix" {
  description = "ARN suffix of the ALB (the LoadBalancer metric dimension), or null."
  value       = one(aws_lb.this[*].arn_suffix)
}

output "target_group_arn_suffix" {
  description = "ARN suffix of the query target group (the TargetGroup metric dimension), or null."
  value       = one(aws_lb_target_group.this[*].arn_suffix)
}

output "load_client" {
  description = "Names the experiment entry points need for ecs run-task (SCHOLARCHAIN_LC_*): the task definition family, the container name to override, the log group and stream prefix, and the security group. Null while the services stack is off (the A and B applies)."
  value = local.stack ? {
    task_definition_family = aws_ecs_task_definition.load_client[0].family
    container_name         = "load-client"
    log_group              = aws_cloudwatch_log_group.this["load-client"].name
    log_stream_prefix      = "ecs"
    security_group_id      = aws_security_group.load_client[0].id
  } : null
}

output "log_groups" {
  description = "CloudWatch log group names by role."
  value       = { for role, group in aws_cloudwatch_log_group.this : role => group.name }
}
