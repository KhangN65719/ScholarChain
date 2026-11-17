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
# service. One image serves every task definition here. Separate services for
# graph retrieval, vector retrieval, ranking, synthesis and verification are not
# created: the code has no network seam between them.
#
# Layouts (service_layout):
#   split     two services, fast and slow, each its own task (AwsRuntime fast, AwsRuntime
#             slow). The Phase 14 shape.
#   combined  one service, query, one task running AwsRuntime both: fast on fast_port and
#             slow on slow_port. Phase 15. Query records, jobs and idempotency are
#             in-process, so /v1/evidence and /v1/explain work only in the task that
#             served /v1/query, and the whole S3 export is held in the heap once per task.
#
# Ingress (Phase 15, P15-3): none from outside the VPC. With enable_load_balancer an
# INTERNAL application load balancer sits in the private app subnets (no public IP);
# its security group admits only the load-client security group, and it forwards to
# slow_port (POST /v1/query, GET /healthz). There is no public subnet and no public
# endpoint. Approving any public ingress is a human decision and is not in this module.
locals {
  combined = var.service_layout == "combined"

  services_combined = tomap({
    query = { role = "both", ports = [var.fast_port, var.slow_port] }
  })

  services_split = tomap({
    fast = { role = "fast", ports = [var.fast_port] }
    slow = { role = "slow", ports = [var.slow_port] }
  })

  services = local.combined ? local.services_combined : local.services_split

  # The service the balancer and the scaling policies attach to: the one whose task
  # serves POST /v1/query on slow_port.
  query_service = local.combined ? "query" : "slow"

  # Services stack (Phase 15, OD-1). The resources session 2 adds beside the Phase 14
  # shape are created only with enable_services_stack: the load-client ECR repository and
  # its lifecycle policy, the worker and load-client log groups and task definitions, the
  # alb and load_client security groups with their seven rules, and Container Insights.
  # Off (the default) the module plans exactly the resources session 1 applied, so the
  # Experiment A and B applies add and destroy none of them. The balancer and the worker
  # service need the stack, so their switches are ANDed with it here (the environment
  # also refuses the combination at plan time).
  stack       = var.enable_services_stack
  use_lb      = var.enable_load_balancer && local.stack
  use_worker  = var.enable_worker && local.stack
  use_scaling = var.enable_autoscaling

  secret_env = {
    SCHOLARCHAIN_SERVICES_AUTH_QUERY_TOKEN   = var.secret_arns["auth-query-token"]
    SCHOLARCHAIN_SERVICES_AUTH_READ_TOKEN    = var.secret_arns["auth-read-token"]
    SCHOLARCHAIN_SERVICES_AUTH_CURSOR_SECRET = var.secret_arns["auth-cursor-secret"]
  }

  secrets = [for name, arn in local.secret_env : { name = name, valueFrom = arn }]

  environment = concat(
    [for name, value in var.container_environment : { name = name, value = value }],
    var.service_jvm_options == "" ? [] : [{ name = "JAVA_TOOL_OPTIONS", value = var.service_jvm_options }],
  )

  worker_environment = [for name, value in var.worker_environment : { name = name, value = value }]

  # The loader reads exactly the names the environment passes in
  # loader_environment (scholarchain.rag.AwsIngestMain, Phase 14 work order 30).
  # It does not share the services' names.
  loader_environment = [for name, value in var.loader_environment : { name = name, value = value }]

  embedding_queue_name = "${var.name_prefix}-embedding-work"
  worker_service_name  = "${var.name_prefix}-worker"
}

resource "aws_ecr_repository" "this" {
  for_each = toset(concat(["services", "loader"], local.stack ? ["load-client"] : []))

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
  for_each = toset(concat(keys(local.services), ["loader"], local.stack ? ["worker", "load-client"] : []))

  name              = "/${var.name_prefix}/${each.value}"
  retention_in_days = var.log_retention_days
}

# Container Insights (Phase 15, P15-6: on with the services stack, "disabled" otherwise,
# the value session 1 applied, so the A and B applies leave the cluster unchanged). It
# adds ECS/ContainerInsights metrics (DesiredTaskCount, RunningTaskCount, read by the
# worker scaling alarm and the dashboard) that bill per custom metric while tasks run,
# and none while the services are at zero.
resource "aws_ecs_cluster" "this" {
  name = "${var.name_prefix}-cluster"

  setting {
    name  = "containerInsights"
    value = local.stack ? "enabled" : "disabled"
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
  for_each = { for role, svc in local.services : role => svc if var.enable_service_discovery }

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

# The command names the main class (the image ENTRYPOINT is java ... -cp lib/* and
# its CMD is -version, which the command replaces). The role is the one program
# argument. Environment names are exactly those AwsSettings reads (WO-8, WO-8b).
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
      command      = ["scholarchain.services.runtime.AwsRuntime", each.value.role]
      portMappings = [for port in each.value.ports : { containerPort = port, protocol = "tcp" }]
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

# The embedding worker runs from the same services image. stopTimeout 40 is the
# worker's own SIGTERM grace (25 s, EmbeddingWorkerMain default) plus margin.
resource "aws_ecs_task_definition" "worker" {
  count = local.stack ? 1 : 0

  family                   = local.worker_service_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = var.execution_role_arn
  task_role_arn            = var.worker_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name        = "worker"
      image       = "${aws_ecr_repository.this["services"].repository_url}:${var.image_tag}"
      essential   = true
      command     = ["scholarchain.storage.EmbeddingWorkerMain"]
      stopTimeout = 40
      environment = local.worker_environment
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this["worker"].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

# The load client is a task definition only: no service. The human (or the experiment
# entry point) starts it with `ecs run-task`. ENTRYPOINT already runs load_client.py,
# so the run-task command override carries arguments only. The query bearer token
# arrives through the ECS secrets block from Secrets Manager (the execution role
# already reads the secret). The task makes no AWS API call, so it has no task role.
resource "aws_ecs_task_definition" "load_client" {
  count = local.stack ? 1 : 0

  family                   = "${var.name_prefix}-load-client"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.load_client_cpu
  memory                   = var.load_client_memory
  execution_role_arn       = var.execution_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  container_definitions = jsonencode([
    {
      name      = "load-client"
      image     = "${aws_ecr_repository.this["load-client"].repository_url}:${var.load_client_image_tag}"
      essential = true
      secrets   = [{ name = "SCHOLARCHAIN_QUERY_TOKEN", valueFrom = var.secret_arns["auth-query-token"] }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this["load-client"].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])
}

### Internal load balancer (P15-3) -------------------------------------------------

# Security groups of the chain load-client task -> ALB (listener port) -> query service
# (target port). The ALB group has exactly one ingress rule, from the load-client group,
# and no CIDR rule; the services group gets one more ingress rule, from the ALB group, on
# the target port only (the fast port is not reachable through the ALB). Both new groups
# are free and exist while the ALB does not.
resource "aws_security_group" "alb" {
  count = local.stack ? 1 : 0

  name        = "${var.name_prefix}-alb"
  description = "Internal load balancer in front of the query service"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-alb" }
}

resource "aws_security_group" "load_client" {
  count = local.stack ? 1 : 0

  name        = "${var.name_prefix}-load-client"
  description = "Load-client task for experiments D and E"
  vpc_id      = var.vpc_id

  tags = { Name = "${var.name_prefix}-load-client" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_load_client" {
  count = local.stack ? 1 : 0

  security_group_id            = aws_security_group.alb[0].id
  referenced_security_group_id = aws_security_group.load_client[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.alb_listener_port
  to_port                      = var.alb_listener_port
  description                  = "Listener port from load-client tasks"
}

resource "aws_vpc_security_group_egress_rule" "load_client_to_alb" {
  count = local.stack ? 1 : 0

  security_group_id            = aws_security_group.load_client[0].id
  referenced_security_group_id = aws_security_group.alb[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.alb_listener_port
  to_port                      = var.alb_listener_port
  description                  = "Listener port of the internal ALB"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_services" {
  count = local.stack ? 1 : 0

  security_group_id            = aws_security_group.alb[0].id
  referenced_security_group_id = var.services_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = var.slow_port
  to_port                      = var.slow_port
  description                  = "Target and health-check port of the query service"
}

resource "aws_vpc_security_group_ingress_rule" "services_from_alb" {
  count = local.stack ? 1 : 0

  security_group_id            = var.services_security_group_id
  referenced_security_group_id = aws_security_group.alb[0].id
  ip_protocol                  = "tcp"
  from_port                    = var.slow_port
  to_port                      = var.slow_port
  description                  = "Target port from the internal ALB"
}

# The load-client task pulls its image (ECR API and registry endpoints, layers from S3),
# reads its secret (Secrets Manager endpoint) and writes logs (CloudWatch Logs endpoint).
resource "aws_vpc_security_group_ingress_rule" "endpoints_from_load_client" {
  count = local.stack ? 1 : 0

  security_group_id            = var.endpoints_security_group_id
  referenced_security_group_id = aws_security_group.load_client[0].id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "HTTPS from load_client"
}

resource "aws_vpc_security_group_egress_rule" "load_client_to_endpoints" {
  count = local.stack ? 1 : 0

  security_group_id            = aws_security_group.load_client[0].id
  referenced_security_group_id = var.endpoints_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "HTTPS to interface endpoints"
}

resource "aws_vpc_security_group_egress_rule" "load_client_to_s3" {
  count = local.stack ? 1 : 0

  security_group_id = aws_security_group.load_client[0].id
  prefix_list_id    = var.s3_prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS to S3 gateway endpoint"
}

resource "aws_lb" "this" {
  count = local.use_lb ? 1 : 0

  name                       = "${var.name_prefix}-alb"
  internal                   = true
  load_balancer_type         = "application"
  subnets                    = var.app_subnet_ids
  security_groups            = [aws_security_group.alb[0].id]
  idle_timeout               = 120
  drop_invalid_header_fields = true
  enable_deletion_protection = false

  # The ALB targets slow_port of the ONE query service. In the split layout the fast
  # service would be handed a load-balancer grace period it cannot use.
  lifecycle {
    precondition {
      condition     = local.combined
      error_message = "enable_load_balancer needs service_layout = combined (one query service behind the ALB)."
    }
  }
}

resource "aws_lb_target_group" "this" {
  count = local.use_lb ? 1 : 0

  name                 = "${var.name_prefix}-query"
  port                 = var.slow_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = "/healthz"
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "this" {
  count = local.use_lb ? 1 : 0

  load_balancer_arn = aws_lb.this[0].arn
  port              = var.alb_listener_port
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this[0].arn
  }
}

### Services ---------------------------------------------------------------------

resource "aws_ecs_service" "this" {
  for_each = local.services

  name                              = "${var.name_prefix}-${each.key}"
  cluster                           = aws_ecs_cluster.this.id
  task_definition                   = aws_ecs_task_definition.service[each.key].arn
  desired_count                     = var.desired_count
  enable_execute_command            = false
  health_check_grace_period_seconds = var.health_check_grace_seconds

  capacity_provider_strategy {
    capacity_provider = var.use_fargate_spot ? "FARGATE_SPOT" : "FARGATE"
    weight            = 1
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.services_security_group_id]
    assign_public_ip = false
  }

  dynamic "load_balancer" {
    for_each = local.use_lb && each.key == local.query_service ? [1] : []

    content {
      target_group_arn = aws_lb_target_group.this[0].arn
      container_name   = each.key
      container_port   = var.slow_port
    }
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

  # The grace period is valid only for a service behind a load balancer: the caller passes
  # 0 while enable_load_balancer is off. AwsRuntime refuses to start without a graph run
  # ID (exit 2), so a service that is on must be given one, and the export run ID too.
  lifecycle {
    precondition {
      condition     = local.use_lb || var.health_check_grace_seconds == 0
      error_message = "health_check_grace_seconds must be 0 while enable_load_balancer is false (ECS accepts a grace period only with a load balancer)."
    }

    precondition {
      condition     = local.stack || !(var.enable_load_balancer || var.enable_worker)
      error_message = "enable_load_balancer and enable_worker need enable_services_stack = true (the alb and load_client security groups and the worker task definition exist only with it)."
    }

    precondition {
      condition     = var.desired_count == 0 || lookup(var.container_environment, "SCHOLARCHAIN_GRAPH_RUN_ID", "") != ""
      error_message = "desired_count is above 0 but SCHOLARCHAIN_GRAPH_RUN_ID is not set: set graph_run_id (run:v1:<uuid>) in the environment module."
    }

    precondition {
      condition     = var.desired_count == 0 || lookup(var.container_environment, "SCHOLARCHAIN_EXPORT_RUN_ID", "") != ""
      error_message = "desired_count is above 0 but SCHOLARCHAIN_EXPORT_RUN_ID is not set: set export_run_id (run-v1-<uuid>) in the environment module."
    }
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this, aws_lb_listener.this]
}

resource "aws_ecs_service" "worker" {
  count = local.use_worker ? 1 : 0

  name                   = local.worker_service_name
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.worker[0].arn
  desired_count          = var.worker_desired_count
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

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

### Query service scaling (course row: graph retriever) -----------------------------
# Two target-tracking policies on one target. Scale-out when EITHER metric is above its
# target; scale-in only when BOTH are below. See SESSION2.md for the arithmetic.

resource "aws_appautoscaling_target" "query" {
  count = local.use_scaling ? 1 : 0

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.this[local.query_service].name}"
  min_capacity       = var.query_min_tasks
  max_capacity       = var.query_max_tasks
}

resource "aws_appautoscaling_policy" "query_requests" {
  count = local.use_scaling && local.use_lb ? 1 : 0

  name               = "${var.name_prefix}-query-requests-per-target"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.query[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.query[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.query[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.request_count_target
    scale_out_cooldown = var.query_scale_out_cooldown_seconds
    scale_in_cooldown  = var.query_scale_in_cooldown_seconds

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.this[0].arn_suffix}/${aws_lb_target_group.this[0].arn_suffix}"
    }
  }
}

resource "aws_appautoscaling_policy" "query_cpu" {
  count = local.use_scaling ? 1 : 0

  name               = "${var.name_prefix}-query-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.query[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.query[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.query[0].resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_percent
    scale_out_cooldown = var.query_scale_out_cooldown_seconds
    scale_in_cooldown  = var.query_scale_in_cooldown_seconds

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Latency alarm of the graph-retriever row. It signals only (no scaling action).
resource "aws_cloudwatch_metric_alarm" "query_p95_latency" {
  count = local.use_lb ? 1 : 0

  alarm_name          = "${var.name_prefix}-query-p95-latency"
  alarm_description   = "p95 TargetResponseTime of the query target group at or above ${var.p95_latency_alarm_seconds} s for three minutes."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "TargetResponseTime"
  dimensions          = { LoadBalancer = aws_lb.this[0].arn_suffix, TargetGroup = aws_lb_target_group.this[0].arn_suffix }
  extended_statistic  = "p95"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 3
  threshold           = var.p95_latency_alarm_seconds
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}

### Embedding worker scaling (course row: embedding workers) -----------------------
# Step scaling on the backlog per task = visible messages / running tasks
# (ECS/ContainerInsights RunningTaskCount, hence Container Insights on). One-minute
# periods so the first reaction comes within about two minutes of the enqueue.

resource "aws_appautoscaling_target" "worker" {
  count = local.use_scaling && local.use_worker ? 1 : 0

  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.worker[0].name}"
  min_capacity       = var.worker_min_tasks
  max_capacity       = var.worker_max_tasks
}

resource "aws_appautoscaling_policy" "worker_out" {
  count = local.use_scaling && local.use_worker ? 1 : 0

  name               = "${var.name_prefix}-worker-backlog-out"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.worker[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.worker[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.worker[0].resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 60
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = 300
      scaling_adjustment          = 1
    }

    step_adjustment {
      metric_interval_lower_bound = 300
      scaling_adjustment          = 2
    }
  }
}

resource "aws_appautoscaling_policy" "worker_in" {
  count = local.use_scaling && local.use_worker ? 1 : 0

  name               = "${var.name_prefix}-worker-backlog-in"
  policy_type        = "StepScaling"
  service_namespace  = aws_appautoscaling_target.worker[0].service_namespace
  scalable_dimension = aws_appautoscaling_target.worker[0].scalable_dimension
  resource_id        = aws_appautoscaling_target.worker[0].resource_id

  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 180
    metric_aggregation_type = "Maximum"

    step_adjustment {
      metric_interval_lower_bound = -15
      metric_interval_upper_bound = 0
      scaling_adjustment          = -1
    }

    step_adjustment {
      metric_interval_upper_bound = -15
      scaling_adjustment          = -2
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "worker_backlog_high" {
  count = local.use_scaling && local.use_worker ? 1 : 0

  alarm_name          = "${var.name_prefix}-worker-backlog-high"
  alarm_description   = "Backlog per worker task at or above ${var.worker_backlog_high}: scale the worker out."
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  threshold           = var.worker_backlog_high
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_appautoscaling_policy.worker_out[0].arn]

  metric_query {
    id          = "e1"
    expression  = "FILL(m1, 0) / IF(FILL(m2, 1) > 0, FILL(m2, 1), 1)"
    label       = "Backlog per task"
    return_data = true
  }

  metric_query {
    id = "m1"

    metric {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesVisible"
      period      = 60
      stat        = "Maximum"
      dimensions  = { QueueName = local.embedding_queue_name }
    }
  }

  metric_query {
    id = "m2"

    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "RunningTaskCount"
      period      = 60
      stat        = "Average"
      dimensions  = { ClusterName = aws_ecs_cluster.this.name, ServiceName = aws_ecs_service.worker[0].name }
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "worker_backlog_low" {
  count = local.use_scaling && local.use_worker ? 1 : 0

  alarm_name          = "${var.name_prefix}-worker-backlog-low"
  alarm_description   = "Backlog per worker task below ${var.worker_backlog_low} for five minutes: scale the worker in."
  evaluation_periods  = 5
  datapoints_to_alarm = 5
  threshold           = var.worker_backlog_low
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_appautoscaling_policy.worker_in[0].arn]

  metric_query {
    id          = "e1"
    expression  = "FILL(m1, 0) / IF(FILL(m2, 1) > 0, FILL(m2, 1), 1)"
    label       = "Backlog per task"
    return_data = true
  }

  metric_query {
    id = "m1"

    metric {
      namespace   = "AWS/SQS"
      metric_name = "ApproximateNumberOfMessagesVisible"
      period      = 60
      stat        = "Maximum"
      dimensions  = { QueueName = local.embedding_queue_name }
    }
  }

  metric_query {
    id = "m2"

    metric {
      namespace   = "ECS/ContainerInsights"
      metric_name = "RunningTaskCount"
      period      = 60
      stat        = "Average"
      dimensions  = { ClusterName = aws_ecs_cluster.this.name, ServiceName = aws_ecs_service.worker[0].name }
    }
  }
}
