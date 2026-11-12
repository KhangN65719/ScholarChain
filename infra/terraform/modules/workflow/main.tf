terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Queues are SSE-SQS encrypted (free). Names are fixed by the identity module's
# ARN patterns. EventBridge rules are not created: nothing schedules the pipeline,
# and the managed rules Step Functions needs for .sync are created by the service.
locals {
  queues = toset(["run-requests", "embedding-work"])

  hadoop_jar_uri = "s3://${var.artifacts_bucket}/${var.hadoop_jar_key}"

  # TerminateCluster and CleanupOnFailure keep a fixed 900 s each (not variables).
  terminate_timeout_seconds = 900
  cleanup_timeout_seconds   = 900

  on_failure = [{
    ErrorEquals = ["States.ALL"]
    ResultPath  = "$.error"
    Next        = "CleanupOnFailure"
  }]

  # Execution input: { hadoop_args: [...], spark_args: [...], loader_command: [...] }.
  # Spark runs through command-runner.jar, so spark_args starts with spark-submit.
  definition = {
    Comment        = "Bounded pipeline: Hadoop, then Spark, then load. The cluster is terminated before the load."
    TimeoutSeconds = var.max_run_seconds
    StartAt        = "CreateCluster"
    States = {
      CreateCluster = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:createCluster.sync"
        Parameters     = var.cluster_definition
        ResultPath     = "$.cluster"
        TimeoutSeconds = var.create_cluster_timeout_seconds
        Next           = "HadoopStep"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.error"
          Next        = "Failed"
        }]
      }
      HadoopStep = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "hadoop"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar      = local.hadoop_jar_uri
              "Args.$" = "$.hadoop_args"
            }
          }
        }
        ResultPath     = "$.hadoop"
        TimeoutSeconds = var.emr_step_timeout_seconds
        Next           = "SparkStep"
        Catch          = local.on_failure
      }
      SparkStep = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "spark"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar      = "command-runner.jar"
              "Args.$" = "$.spark_args"
            }
          }
        }
        ResultPath     = "$.spark"
        TimeoutSeconds = var.emr_step_timeout_seconds
        Next           = "TerminateCluster"
        Catch          = local.on_failure
      }
      TerminateCluster = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:terminateCluster.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
        }
        ResultPath     = "$.terminated"
        TimeoutSeconds = local.terminate_timeout_seconds
        Next           = "RunLoader"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.error"
          Next        = "Failed"
        }]
      }
      RunLoader = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = var.ecs_cluster_arn
          TaskDefinition = var.loader_task_definition_arn
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = var.app_subnet_ids
              SecurityGroups = [var.loader_security_group_id]
              AssignPublicIp = "DISABLED"
            }
          }
          Overrides = {
            ContainerOverrides = [{
              Name        = "loader"
              "Command.$" = "$.loader_command"
            }]
          }
        }
        ResultPath     = "$.load"
        TimeoutSeconds = var.loader_timeout_seconds
        Next           = "Done"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.error"
          Next        = "Failed"
        }]
      }
      CleanupOnFailure = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:terminateCluster.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
        }
        ResultPath     = "$.terminated"
        TimeoutSeconds = local.cleanup_timeout_seconds
        Next           = "Failed"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.cleanup_error"
          Next        = "Failed"
        }]
      }
      Failed = {
        Type  = "Fail"
        Error = "PipelineFailed"
        Cause = "A pipeline step failed. See the execution history and the cluster termination state."
      }
      Done = {
        Type = "Succeed"
      }
    }
  }
}

# No Rule B check. The sum create + 2 x EMR step + terminate + loader exceeds
# max_run_seconds on every tier by design: it is the accepted human decision O1
# (phase 13 decisions D10), recorded as a known limitation in README "Pipeline
# bounds". A warning that fires on every plan would only teach readers to ignore
# warnings. Rule A is a validation on max_run_seconds.

resource "aws_sqs_queue" "dead_letter" {
  for_each = local.queues

  name                      = "${var.name_prefix}-${each.value}-dlq"
  message_retention_seconds = 345600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "work" {
  for_each = local.queues

  name                       = "${var.name_prefix}-${each.value}"
  message_retention_seconds  = var.queue_retention_seconds
  visibility_timeout_seconds = var.queue_visibility_seconds
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter[each.value].arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_cloudwatch_log_group" "pipeline" {
  count = var.enable_pipeline ? 1 : 0

  name              = "/aws/vendedlogs/states/${var.name_prefix}-pipeline"
  retention_in_days = var.log_retention_days
}

resource "aws_sfn_state_machine" "pipeline" {
  count = var.enable_pipeline ? 1 : 0

  name       = "${var.name_prefix}-pipeline"
  role_arn   = var.sfn_role_arn
  type       = "STANDARD"
  definition = jsonencode(local.definition)

  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.pipeline[0].arn}:*"
    include_execution_data = false
    level                  = "ERROR"
  }
}
