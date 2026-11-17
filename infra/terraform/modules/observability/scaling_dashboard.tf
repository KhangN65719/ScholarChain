# Phase 15 (WO-10): one dashboard that puts the scaling metrics of every experiment on
# ONE time axis (a dashboard has one time range, so every widget below shares it):
#   EMR (B), ECS desired and running tasks, ECS CPU and memory, ALB p50/p95/p99, request
#   count, 5xx, SQS depth and dead-letter count, DynamoDB throttles, Neptune.
# The overview dashboard of main.tf is unchanged. Two dashboards stay inside the three
# free ones (CloudWatch pricing, COST_ESTIMATE.md V10). This one is created only with
# enable_scaling_dashboard (the services stack, session 2): the Experiment A and B applies
# keep the overview dashboard alone, as session 1 did (OD-1).
#
# UNVERIFIED metric names (no AWS documentation tool in the authoring turn; the
# experiment collectors save `cloudwatch list-metrics` so a wrong name shows up there):
# the AWS/ElasticMapReduce names CoreNodesRunning, TaskNodesRunning, ContainerPending and
# YARNMemoryAvailablePercentage; ECS/ContainerInsights DesiredTaskCount and
# RunningTaskCount (dimensions ClusterName, ServiceName); AWS/ApplicationELB
# TargetResponseTime with percentile statistics p50, p95 and p99 in a widget; Neptune
# MainRequestQueuePendingRequests. The EMR cluster ID is not known at plan time, so the
# EMR widgets use a SEARCH expression over every JobFlowId.
locals {
  has_alb    = var.alb_enabled
  has_worker = var.worker_enabled

  scaling_ecs_services = merge(
    var.ecs_service_names,
    local.has_worker ? tomap({ worker = var.worker_service_name }) : tomap({}),
  )

  emr_widgets = [
    {
      type   = "metric"
      x      = 0
      y      = 0
      width  = 12
      height = 6
      properties = {
        title  = "EMR nodes running (B)"
        region = var.region
        period = 60
        stat   = "Maximum"
        view   = "timeSeries"
        metrics = [
          [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"CoreNodesRunning\"', 'Maximum', 60)", id = "core", label = "core nodes running" }],
          [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"TaskNodesRunning\"', 'Maximum', 60)", id = "task", label = "task nodes running" }],
        ]
      }
    },
    {
      type   = "metric"
      x      = 12
      y      = 0
      width  = 12
      height = 6
      properties = {
        title  = "EMR pending containers and free YARN memory (B)"
        region = var.region
        period = 60
        stat   = "Maximum"
        view   = "timeSeries"
        metrics = [
          [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"ContainerPending\"', 'Maximum', 60)", id = "pending", label = "containers pending" }],
          [{ expression = "SEARCH('{AWS/ElasticMapReduce,JobFlowId} MetricName=\"YARNMemoryAvailablePercentage\"', 'Minimum', 60)", id = "yarnmem", label = "YARN memory available percent" }],
        ]
      }
    },
  ]

  ecs_tasks_widget = [{
    type   = "metric"
    x      = 0
    y      = 6
    width  = 12
    height = 6
    properties = {
      title  = "ECS desired and running tasks"
      region = var.region
      period = 60
      stat   = "Average"
      view   = "timeSeries"
      metrics = concat(
        [for name, service in local.scaling_ecs_services : ["ECS/ContainerInsights", "DesiredTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { label = "${name} desired" }]],
        [for name, service in local.scaling_ecs_services : ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { label = "${name} running" }]],
      )
    }
  }]

  ecs_utilization_widget = [{
    type   = "metric"
    x      = 12
    y      = 6
    width  = 12
    height = 6
    properties = {
      title  = "ECS CPU and memory utilization (percent)"
      region = var.region
      period = 60
      stat   = "Average"
      view   = "timeSeries"
      metrics = concat(
        [for name, service in local.scaling_ecs_services : ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { label = "${name} cpu" }]],
        [for name, service in local.scaling_ecs_services : ["AWS/ECS", "MemoryUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", service, { label = "${name} memory" }]],
      )
    }
  }]

  alb_latency_widget = local.has_alb ? [{
    type   = "metric"
    x      = 0
    y      = 12
    width  = 12
    height = 6
    properties = {
      title  = "ALB target response time p50, p95, p99 (seconds)"
      region = var.region
      period = 60
      view   = "timeSeries"
      metrics = [
        ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p50", label = "p50" }],
        ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p95", label = "p95" }],
        ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p99", label = "p99" }],
      ]
    }
  }] : []

  alb_requests_widget = local.has_alb ? [{
    type   = "metric"
    x      = 12
    y      = 12
    width  = 12
    height = 6
    properties = {
      title  = "ALB request count and 5xx"
      region = var.region
      period = 60
      stat   = "Sum"
      view   = "timeSeries"
      metrics = [
        ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { label = "requests" }],
        ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { label = "target 5xx" }],
        ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix, { label = "elb 5xx" }],
        ["AWS/ApplicationELB", "HealthyHostCount", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "Average", label = "healthy hosts" }],
      ]
    }
  }] : []

  sqs_widget = [{
    type   = "metric"
    x      = 0
    y      = 18
    width  = 12
    height = 6
    properties = {
      title  = "SQS depth and dead-letter count"
      region = var.region
      period = 60
      stat   = "Maximum"
      view   = "timeSeries"
      metrics = concat(
        [for q in compact([var.embedding_queue_name]) : ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", q, { label = "embedding-work visible" }]],
        [for q in compact([var.embedding_queue_name]) : ["AWS/SQS", "ApproximateNumberOfMessagesNotVisible", "QueueName", q, { label = "embedding-work in flight" }]],
        [for name, queue in var.dead_letter_queue_names : ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", queue, { label = "${name} dead-letter" }]],
      )
    }
  }]

  dynamodb_widget = [{
    type   = "metric"
    x      = 12
    y      = 18
    width  = 12
    height = 6
    properties = {
      title  = "DynamoDB throttle events"
      region = var.region
      period = 60
      stat   = "Sum"
      view   = "timeSeries"
      metrics = concat(
        [for name, table in var.dynamodb_table_names : ["AWS/DynamoDB", "ReadThrottleEvents", "TableName", table, { label = "${name} read throttles" }]],
        [for name, table in var.dynamodb_table_names : ["AWS/DynamoDB", "WriteThrottleEvents", "TableName", table, { label = "${name} write throttles" }]],
      )
    }
  }]

  neptune_scaling_widget = local.has_neptune ? [{
    type   = "metric"
    x      = 0
    y      = 24
    width  = 12
    height = 6
    properties = {
      title  = "Neptune CPU (percent) and pending requests"
      region = var.region
      period = 60
      stat   = "Maximum"
      view   = "timeSeries"
      metrics = [
        ["AWS/Neptune", "CPUUtilization", "DBClusterIdentifier", var.neptune_cluster_identifier, { label = "cpu" }],
        ["AWS/Neptune", "MainRequestQueuePendingRequests", "DBClusterIdentifier", var.neptune_cluster_identifier, { label = "pending requests" }],
      ]
    }
  }] : []
}

resource "aws_cloudwatch_dashboard" "scaling" {
  count = var.enable_dashboard && var.enable_scaling_dashboard ? 1 : 0

  dashboard_name = "${var.name_prefix}-scaling"

  dashboard_body = jsonencode({
    widgets = concat(
      local.emr_widgets,
      local.ecs_tasks_widget,
      local.ecs_utilization_widget,
      local.alb_latency_widget,
      local.alb_requests_widget,
      local.sqs_widget,
      local.dynamodb_widget,
      local.neptune_scaling_widget,
    )
  })
}
