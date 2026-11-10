terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Deliberately small: one dashboard and a few standard alarms (about $0.10 a month
# each). Covered: pipeline failures, dead-letter depth, ECS CPU, Neptune capacity.
# NOT covered, and listed in the README: EMR, DynamoDB, S3 Vectors client, API
# latency and LLM usage. The services expose /metrics text, and the AWS-side
# metrics for them would add custom-metric charges, so they wait for a decision.
locals {
  has_pipeline = var.pipeline_enabled
  has_neptune  = var.neptune_enabled

  has_neptune_serverless = var.neptune_enabled && var.neptune_mode == "serverless"
  neptune_metric_name    = var.neptune_mode == "serverless" ? "ServerlessDatabaseCapacity" : "CPUUtilization"

  pipeline_widgets = local.has_pipeline ? [{
    type   = "metric"
    x      = 0
    y      = 0
    width  = 12
    height = 6
    properties = {
      title  = "Pipeline executions"
      region = var.region
      stat   = "Sum"
      period = 300
      metrics = [
        ["AWS/States", "ExecutionsStarted", "StateMachineArn", var.state_machine_arn],
        ["AWS/States", "ExecutionsSucceeded", "StateMachineArn", var.state_machine_arn],
        ["AWS/States", "ExecutionsFailed", "StateMachineArn", var.state_machine_arn],
      ]
    }
  }] : []

  dlq_widget = [{
    type   = "metric"
    x      = 12
    y      = 0
    width  = 12
    height = 6
    properties = {
      title  = "Dead-letter queue depth"
      region = var.region
      stat   = "Maximum"
      period = 300
      metrics = [
        for name in values(var.dead_letter_queue_names) :
        ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", name]
      ]
    }
  }]

  ecs_widget = [{
    type   = "metric"
    x      = 0
    y      = 6
    width  = 12
    height = 6
    properties = {
      title  = "ECS service CPU"
      region = var.region
      stat   = "Average"
      period = 300
      metrics = [
        for service in values(var.ecs_service_names) :
        ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", service]
      ]
    }
  }]

  neptune_widgets = local.has_neptune ? [{
    type   = "metric"
    x      = 12
    y      = 6
    width  = 12
    height = 6
    properties = {
      title  = var.neptune_mode == "serverless" ? "Neptune serverless capacity (NCU)" : "Neptune CPU utilization (%)"
      region = var.region
      stat   = var.neptune_mode == "serverless" ? "Maximum" : "Average"
      period = 300
      metrics = [
        ["AWS/Neptune", local.neptune_metric_name, "DBClusterIdentifier", var.neptune_cluster_identifier],
      ]
    }
  }] : []
}

resource "aws_cloudwatch_dashboard" "this" {
  count = var.enable_dashboard ? 1 : 0

  dashboard_name = "${var.name_prefix}-overview"

  dashboard_body = jsonencode({
    widgets = concat(local.pipeline_widgets, local.dlq_widget, local.ecs_widget, local.neptune_widgets)
  })
}

resource "aws_cloudwatch_metric_alarm" "pipeline_failed" {
  count = local.has_pipeline ? 1 : 0

  alarm_name          = "${var.name_prefix}-pipeline-failed"
  alarm_description   = "A pipeline execution failed. Check that the EMR cluster was terminated."
  namespace           = "AWS/States"
  metric_name         = "ExecutionsFailed"
  dimensions          = { StateMachineArn = var.state_machine_arn }
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "dead_letter" {
  for_each = var.dead_letter_queue_names

  alarm_name          = "${var.name_prefix}-${each.key}-dlq-not-empty"
  alarm_description   = "Messages reached the ${each.key} dead-letter queue."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = each.value }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}

# Neptune Serverless does not scale to zero, so sustained maximum capacity is the
# budget signal worth an alarm. The metric exists only in serverless mode, so the
# alarm does too.
resource "aws_cloudwatch_metric_alarm" "neptune_capacity" {
  count = local.has_neptune_serverless ? 1 : 0

  alarm_name          = "${var.name_prefix}-neptune-at-max-capacity"
  alarm_description   = "Neptune Serverless has sat at its maximum capacity for 30 minutes."
  namespace           = "AWS/Neptune"
  metric_name         = "ServerlessDatabaseCapacity"
  dimensions          = { DBClusterIdentifier = var.neptune_cluster_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 6
  threshold           = var.neptune_max_ncu
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_actions
}
