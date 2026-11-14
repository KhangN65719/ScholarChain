terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Consolidation, read from modules/services: the code has two independently
# bounded services, fast (evidence, graph, explain, ops) and slow (query and
# jobs), each with its own call pool and executor. Retrieval, ranking, synthesis
# and verification run in-process inside the rag workflow behind the slow
# service. One image therefore serves two task definitions (fast and slow) so the
# two scale separately, and one more task definition runs the loader. Separate
# services for graph retrieval, vector retrieval, ranking, synthesis and
# verification are not created: the code has no network seam between them.
#
# Ingress: none. There is no load balancer, no public subnet and no public IP.
# Services are reached by name through Cloud Map (only when enable_service_discovery
# is on; it is off by default) from the client security group.
# Approving any public ingress is a human decision and is not in this module.
locals {
  services = tomap({
    fast = var.fast_port
    slow = var.slow_port
  })

  secret_env = {
    SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN   = var.secret_arns["auth-query-token"]
    SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN    = var.secret_arns["auth-read-token"]
    SCHOLARCHAIN_SERVICES_AUTH_CURSOR_SECRET = var.secret_arns["auth-cursor-secret"]
  }

  secrets = [for name, arn in local.secret_env : { name = name, valueFrom = arn }]

  environment = [for name, value in var.container_environment : { name = name, value = value }]

  # The loader reads exactly the names the environment passes in
  # loader_environment (scholarchain.rag.AwsIngestMain, Phase 14 work order 30).
  # It does not share the services' proposed names.
  loader_environment = [for name, value in var.loader_environment : { name = name, value = value }]
}

resource "aws_ecr_repository" "this" {
  for_each = toset(["services", "loader"])

  name                 = "${var.name_prefix}-${each.value}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = var.force_delete_repositories

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# Keep only the last five tagged images: stored images bill by the gigabyte-month.
resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after one day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the last five images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 5
        }
        action = { type = "expire" }
      },
    ]
  })
}

resource "aws_cloudwatch_log_group" "this" {
  for_each = toset(["fast", "slow", "loader"])

  name              = "/${var.name_prefix}/${each.value}"
  retention_in_days = var.log_retention_days
}

# Container Insights is off: it adds custom metrics that bill per task.
resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

# Off by default (decision D2c): there is no ingress and nothing in the design
# resolves these names. The namespace creates a Route 53 private hosted zone
# (about $0.50 a month). With it off, tasks have no registry and are reached by
# private IP.
resource "aws_service_discovery_private_dns_namespace" "this" {
  count = var.enable_service_discovery ? 1 : 0

  name        = "${var.name_prefix}.internal"
  description = "Internal service discovery"
  vpc         = var.vpc_id
}

resource "aws_service_discovery_service" "this" {
  for_each = { for role, port in local.services : role => port if var.enable_service_discovery }

  name = each.key

  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.this[0].id
    routing_policy = "MULTIVALUE"

    dns_records {
      ttl  = 10
      type = "A"
    }
  }

  health_check_custom_config {}
}

resource "aws_ecs_task_definition" "service" {
  for_each = local.services

  family                   = "${var.name_prefix}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.service_cpu
  memory                   = var.service_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.runtime_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name         = each.key
      image        = "${aws_ecr_repository.this["services"].repository_url}:${var.image_tag}"
      essential    = true
      portMappings = [{ containerPort = each.value, protocol = "tcp" }]
      environment  = local.environment
      secrets      = local.secrets
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this[each.key].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

resource "aws_ecs_task_definition" "loader" {
  family                   = "${var.name_prefix}-loader"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.loader_cpu
  memory                   = var.loader_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.loader_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name        = "loader"
      image       = "${aws_ecr_repository.this["loader"].repository_url}:${var.image_tag}"
      essential   = true
      environment = local.loader_environment
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this["loader"].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "this" {
  for_each = local.services

  name                   = "${var.name_prefix}-${each.key}"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.service[each.key].arn
  desired_count          = var.desired_count
  enable_execute_command = false

  capacity_provider_strategy {
    capacity_provider = var.use_fargate_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.services_security_group_id]
    assign_public_ip = false
  }

  dynamic "service_registries" {
    for_each = var.enable_service_discovery ? [1] : []

    content {
      registry_arn = aws_service_discovery_service.this[each.key].arn
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}
