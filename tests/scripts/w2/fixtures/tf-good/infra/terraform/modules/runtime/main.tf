# Test fixture for tests/scripts/w2/tfcheck.py. A minimal tree that satisfies every check. Not real
# infrastructure: it is never planned or applied.
locals {
  service_environment = {
    AWS_REGION                     = var.region
    SCHOLARCHAIN_TABLE_PREFIX      = var.table_prefix
    SCHOLARCHAIN_DATA_BUCKET       = var.data_bucket
    SCHOLARCHAIN_VECTOR_BUCKET     = var.vector_bucket
    SCHOLARCHAIN_NEPTUNE_HOST      = var.neptune_host
    SCHOLARCHAIN_GRAPH_RUN_ID      = var.graph_run_id
    SCHOLARCHAIN_EXPORT_RUN_ID     = var.export_run_id == null ? "" : var.export_run_id
    SCHOLARCHAIN_ANSWER_MODEL      = var.answer_model
    SCHOLARCHAIN_BEDROCK_MAX_CALLS = var.bedrock_max_calls
    SCHOLARCHAIN_RUNTIME_ROLE      = "both"
    SCHOLARCHAIN_BEDROCK           = "off"
  }

  worker_environment = {
    AWS_REGION                       = var.region
    SCHOLARCHAIN_EMBEDDING_QUEUE_URL = var.embedding_queue_url
    SCHOLARCHAIN_DATA_BUCKET         = var.data_bucket
    SCHOLARCHAIN_VECTOR_BUCKET       = var.vector_bucket
    SCHOLARCHAIN_TABLE_PREFIX        = var.table_prefix
  }

  secret_env = {
    SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN = var.secret_arns["auth-query-token"]
    SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN  = var.secret_arns["auth-read-token"]
  }

  secrets = [for name, arn in local.secret_env : { name = name, valueFrom = arn }]
}

# OD-1: everything session 2 adds is gated behind the services stack flag. use_lb and use_worker are
# ANDed with it, so the balancer and the worker service cannot exist without the stack.
locals {
  stack      = var.enable_services_stack
  use_lb     = var.enable_load_balancer && local.stack
  use_worker = var.enable_worker && local.stack
}

resource "aws_ecr_repository" "this" {
  for_each = toset(concat(["services", "loader"], local.stack ? ["load-client"] : []))

  name = "fx-${each.value}"
}

resource "aws_ecs_cluster" "this" {
  name = "fx-cluster"

  setting {
    name  = "containerInsights"
    value = local.stack ? "enabled" : "disabled"
  }
}

resource "aws_security_group" "load_client" {
  count = local.stack ? 1 : 0

  name = "fx-load-client"
}

resource "aws_security_group" "alb" {
  count = local.stack ? 1 : 0

  name = "fx-alb"

  ingress {
    from_port       = 80
    to_port         = 80
    protocol        = "tcp"
    security_groups = [aws_security_group.load_client[0].id]
  }
}

resource "aws_vpc_security_group_egress_rule" "load_client_to_alb" {
  count = local.stack ? 1 : 0

  security_group_id            = aws_security_group.load_client[0].id
  referenced_security_group_id = aws_security_group.alb[0].id
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
}

resource "aws_lb" "this" {
  count = local.use_lb ? 1 : 0

  name               = "fx-alb"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb[0].id]
  subnets            = var.app_subnet_ids
}

resource "aws_lb_target_group" "query" {
  name        = "fx-query"
  port        = 8081
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id

  health_check {
    path = "/healthz"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this[0].arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.query.arn
  }
}

resource "aws_ecs_task_definition" "service" {
  family = "fx-service"
  cpu    = var.service_cpu
  memory = 4096

  container_definitions = jsonencode([
    {
      name         = "slow"
      image        = "fx-image"
      command      = ["scholarchain.services.runtime.AwsRuntime", "slow"]
      portMappings = [{ containerPort = 8081, protocol = "tcp" }]
      environment  = local.service_environment
      secrets      = local.secrets
    }
  ])
}

resource "aws_ecs_task_definition" "worker" {
  count = local.stack ? 1 : 0

  family = "fx-worker"
  cpu    = 512
  memory = 2048

  container_definitions = jsonencode([
    {
      name        = "worker"
      image       = "fx-image"
      command     = ["scholarchain.storage.EmbeddingWorkerMain"]
      environment = local.worker_environment
    }
  ])
}

resource "aws_ecs_task_definition" "load_client" {
  count = local.stack ? 1 : 0

  family = "fx-load-client"
  cpu    = 1024
  memory = 2048

  container_definitions = jsonencode([
    {
      name    = "load-client"
      image   = "fx-load-client-image"
      command = ["--help"]
      secrets = [{ name = "SCHOLARCHAIN_QUERY_TOKEN", valueFrom = var.secret_arns["auth-query-token"] }]
    }
  ])
}

resource "aws_ecs_service" "query" {
  name                              = "fx-query"
  task_definition                   = aws_ecs_task_definition.service.arn
  health_check_grace_period_seconds = 300

  load_balancer {
    target_group_arn = aws_lb_target_group.query.arn
    container_name   = "slow"
    container_port   = 8081
  }

  network_configuration {
    assign_public_ip = false
  }
}

resource "aws_ecs_service" "worker" {
  count = local.use_worker ? 1 : 0

  name            = "fx-worker"
  task_definition = aws_ecs_task_definition.worker[0].arn

  network_configuration {
    assign_public_ip = false
  }
}

resource "aws_appautoscaling_target" "query" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.query.name}"
  min_capacity       = 1
  max_capacity       = 6
}

resource "aws_appautoscaling_policy" "query" {
  name               = "fx-query-requests"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.query.resource_id
  scalable_dimension = aws_appautoscaling_target.query.scalable_dimension
  service_namespace  = aws_appautoscaling_target.query.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
    }
  }
}

resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.worker[0].name}"
  min_capacity       = 1
  max_capacity       = 4
}

resource "aws_appautoscaling_policy" "worker_out" {
  name               = "fx-worker-backlog-out"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  service_namespace  = aws_appautoscaling_target.worker.service_namespace

  step_scaling_policy_configuration {
    adjustment_type = "ChangeInCapacity"

    step_adjustment {
      scaling_adjustment          = 1
      metric_interval_lower_bound = 0
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "backlog" {
  alarm_name          = "fx-worker-backlog"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Average"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 100
  evaluation_periods  = 1
  period              = 60
  alarm_actions       = [aws_appautoscaling_policy.worker_out.arn]
}

resource "aws_cloudwatch_metric_alarm" "p95" {
  alarm_name          = "fx-query-p95"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  extended_statistic  = "p95"
  comparison_operator = "GreaterThanThreshold"
  threshold           = 5
  evaluation_periods  = 3
  period              = 60
}

resource "aws_cloudwatch_log_group" "this" {
  for_each = toset(concat(["slow", "loader"], local.stack ? ["worker", "load-client"] : []))

  name              = "/fx/${each.value}"
  retention_in_days = var.log_retention_days
}
