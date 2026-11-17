resource "aws_cloudwatch_dashboard" "scaling" {
  count = var.enable_dashboard && var.enable_scaling_dashboard ? 1 : 0

  dashboard_name = "fx-scaling"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric"
        properties = {
          metrics = [
            ["AWS/ElasticMapReduce", "YARNMemoryAvailablePercentage"],
            ["ECS/ContainerInsights", "DesiredTaskCount"],
            ["ECS/ContainerInsights", "RunningTaskCount"],
            ["AWS/ECS", "CPUUtilization"],
            ["AWS/ECS", "MemoryUtilization"],
            ["AWS/ApplicationELB", "TargetResponseTime", { stat = "p50" }],
            ["AWS/ApplicationELB", "TargetResponseTime", { stat = "p95" }],
            ["AWS/ApplicationELB", "TargetResponseTime", { stat = "p99" }],
            ["AWS/ApplicationELB", "RequestCount"],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count"],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", "fx-embedding-work"],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", "fx-embedding-work-dlq"],
            ["AWS/DynamoDB", "ThrottledRequests"],
            ["AWS/Neptune", "CPUUtilization"]
          ]
        }
      }
    ]
  })
}
