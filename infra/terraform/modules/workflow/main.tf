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

  # TerminateCluster and CleanupOnFailure keep a fixed 900 s each (not variables).
  terminate_timeout_seconds = 900
  cleanup_timeout_seconds   = 900

  # The script-fetch step copies a few small files from S3. Its own fixed bound, not a
  # variable. Rule A below still uses 2 x emr_step_timeout_seconds, which now
  # over-covers: a run has one long step plus this 300 s step.
  fetch_timeout_seconds = 300

  # On-node scripts live under this key prefix of the artifacts bucket
  # (scripts/upload-inputs.sh puts scripts/emr/* there) and are copied to this
  # directory on the primary node by the fetch step.
  script_prefix = "s3://${var.artifacts_bucket}/bootstrap/"
  script_dir    = "/home/hadoop/sc"

  # AmazonEMRServicePolicy_v2 lets the service role create instances, volumes and
  # network interfaces only when the request carries this tag (work order 40). EMR
  # propagates a cluster user tag to those resources. The subnet and security groups
  # carry the same tag from the network module.
  emr_cluster_tags = [{ Key = "for-use-with-amazon-emr-managed-policies", Value = "true" }]

  on_failure = [{
    ErrorEquals = ["States.ALL"]
    ResultPath  = "$.error"
    Next        = "CleanupOnFailure"
  }]

  # Execution input contract (the on-node scripts are scripts/emr/*.sh):
  #   capability-check: { "mode": "capability-check" }
  #   pipeline: { "mode": "pipeline", "snapshot": "<raw/ snapshot id>",
  #               "snapshots": "<value of -Dscholarchain.run.snapshots>",
  #               "run_id": "<run id>", "loader_command": ["MainClass", "arg", ...] }
  # There are no free-form argument lists for EMR: each step runs a fixed script and
  # only the three scalar fields reach it, as separate arguments (never through a
  # shell string). The scripts validate them again. loader_command goes to the ECS
  # loader task only.
  #
  # Steps use command-runner.jar, which is local to the node. script-runner.jar is not
  # used: it lives in the regional EMR bucket (s3://us-east-2.elasticmapreduce/libs/
  # script-runner/script-runner.jar, UNVERIFIED from memory), which the emr_ec2 role
  # cannot read (it reads only the data bucket raw/, curated/ and graph/ prefixes and
  # the artifacts bucket jars/ and bootstrap/ prefixes), so using it would need an IAM
  # change that this design avoids. That command-runner.jar runs `bash <script>` on the
  # primary node is UNVERIFIED until the first cluster.
  mode_is_capability = [
    { Variable = "$.mode", IsPresent = true },
    { Variable = "$.mode", StringEquals = "capability-check" },
  ]
  mode_is_pipeline = [
    { Variable = "$.mode", IsPresent = true },
    { Variable = "$.mode", StringEquals = "pipeline" },
  ]
  pipeline_fields_present = [
    { Variable = "$.snapshot", IsPresent = true },
    { Variable = "$.snapshots", IsPresent = true },
    { Variable = "$.run_id", IsPresent = true },
    { Variable = "$.loader_command", IsPresent = true },
  ]

  definition = {
    Comment        = "Bounded pipeline. Mode capability-check: create the cluster, run one check script, terminate. Mode pipeline: create the cluster, run the on-node script (Hadoop, entity resolution, Spark, export, publish), terminate, then load. The cluster is terminated before the load."
    TimeoutSeconds = var.max_run_seconds
    StartAt        = "ValidateInput"
    States = {
      ValidateInput = {
        Type = "Choice"
        Choices = [
          {
            And  = local.mode_is_capability
            Next = "CreateCluster"
          },
          {
            And  = concat(local.mode_is_pipeline, local.pipeline_fields_present)
            Next = "CreateCluster"
          },
        ]
        Default = "InvalidInput"
      }
      InvalidInput = {
        Type  = "Fail"
        Error = "InvalidInput"
        Cause = "The execution input needs mode capability-check, or mode pipeline with snapshot, snapshots, run_id and loader_command. No cluster was created."
      }
      CreateCluster = {
        Type           = "Task"
        Resource       = "arn:aws:states:::elasticmapreduce:createCluster.sync"
        Parameters     = merge(var.cluster_definition, { Tags = local.emr_cluster_tags })
        ResultPath     = "$.cluster"
        TimeoutSeconds = var.create_cluster_timeout_seconds
        Next           = "FetchScripts"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.error"
          Next        = "Failed"
        }]
      }
      FetchScripts = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "fetch-scripts"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar = "command-runner.jar"
              Args = [
                "bash", "-c",
                "mkdir -p ${local.script_dir} && aws s3 cp ${local.script_prefix} ${local.script_dir}/ --recursive --only-show-errors",
              ]
            }
          }
        }
        ResultPath     = "$.fetch"
        TimeoutSeconds = local.fetch_timeout_seconds
        Next           = "ChooseStep"
        Catch          = local.on_failure
      }
      ChooseStep = {
        Type = "Choice"
        Choices = [
          {
            And  = local.mode_is_capability
            Next = "CapabilityStep"
          },
          {
            And  = local.mode_is_pipeline
            Next = "PipelineStep"
          },
        ]
        Default = "CleanupOnFailure"
      }
      CapabilityStep = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "capability-check"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar = "command-runner.jar"
              Args = [
                "bash", "${local.script_dir}/capability-check.sh",
                var.data_bucket, var.artifacts_bucket,
              ]
            }
          }
        }
        ResultPath     = "$.capability"
        TimeoutSeconds = var.emr_step_timeout_seconds
        Next           = "TerminateCluster"
        Catch          = local.on_failure
      }
      PipelineStep = {
        Type     = "Task"
        Resource = "arn:aws:states:::elasticmapreduce:addStep.sync"
        Parameters = {
          "ClusterId.$" = "$.cluster.ClusterId"
          Step = {
            Name            = "pipeline"
            ActionOnFailure = "CONTINUE"
            HadoopJarStep = {
              Jar      = "command-runner.jar"
              "Args.$" = "States.Array('bash', '${local.script_dir}/run-s-pipeline.sh', '${var.data_bucket}', '${var.artifacts_bucket}', $.snapshot, $.snapshots, $.run_id)"
            }
          }
        }
        ResultPath     = "$.pipeline"
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
        Next           = "AfterTerminate"
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.error"
          Next        = "Failed"
        }]
      }
      AfterTerminate = {
        Type = "Choice"
        Choices = [{
          And  = local.mode_is_pipeline
          Next = "RunLoader"
        }]
        Default = "Done"
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
