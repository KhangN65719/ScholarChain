terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# EMR runs as a transient cluster that the Step Functions pipeline creates and
# terminates. No cluster exists in Terraform, so nothing bills while idle. EMR
# Serverless is not carried: it runs Spark and Hive only, and the Hadoop stage is
# MapReduce (modules/hadoop), which needs EMR on EC2.
#
# Design B (Phase 14 decision D21): release emr-7.10.0 with the Hadoop application
# only. EMR's own Spark (3.5.5 on Scala 2.12) is NOT installed, so /usr/bin/spark-submit,
# /etc/spark/conf and SPARK_HOME do not exist on the node. Our jobs target Spark 4.0.5
# on Scala 2.13: the on-node scripts (scripts/emr/) stage the Apache Spark 4.0.5
# distribution from the artifacts bucket and run spark-submit --master yarn from it
# against the cluster's YARN. Hadoop (MapReduce on YARN) comes from the Hadoop application.
#
# This module owns what is free to keep: the security configuration, and the
# bounded cluster definition handed to the state machine.
locals {
  has_core = var.core_instance_count > 0
  has_task = local.has_core && var.task_spot_instance_count > 0

  primary_group = {
    Name          = "primary"
    InstanceRole  = "MASTER"
    InstanceType  = var.master_instance_type
    InstanceCount = 1
    Market        = "ON_DEMAND"
  }

  core_group = {
    Name          = "core"
    InstanceRole  = "CORE"
    InstanceType  = var.core_instance_type
    InstanceCount = var.core_instance_count
    Market        = "ON_DEMAND"
  }

  task_group = {
    Name          = "task-spot"
    InstanceRole  = "TASK"
    InstanceType  = var.core_instance_type
    InstanceCount = var.task_spot_instance_count
    Market        = "SPOT"
  }

  instance_groups = concat(
    [local.primary_group],
    local.has_core ? [local.core_group] : [],
    local.has_task ? [local.task_group] : [],
  )

  # Fixed node count: 1 primary + core nodes + Spot task nodes (task nodes exist
  # only with a core node). Example: 1 + 4 core + 2 task = 7. The variable
  # validates that the cap covers this sum; max() is a second line of defense.
  fixed_nodes = 1 + var.core_instance_count + (local.has_task ? var.task_spot_instance_count : 0)
  max_units   = max(var.managed_scaling_max_units, local.fixed_nodes)

  managed_scaling = {
    ComputeLimits = {
      UnitType                     = "Instances"
      MinimumCapacityUnits         = var.core_instance_count + 1
      MaximumCapacityUnits         = local.max_units
      MaximumCoreCapacityUnits     = max(var.core_instance_count, 1)
      MaximumOnDemandCapacityUnits = local.max_units
    }
  }

  # Optional bootstrap action: installs Corretto (17 or 21) and records JAVA_HOME
  # when the capability check finds no usable JDK. Off by default. The script is
  # scripts/emr/install-java-bootstrap.sh, uploaded by scripts/upload-inputs.sh to
  # the artifacts bucket key bootstrap/install-java-bootstrap.sh (read by the
  # emr_ec2 role, which already reads bootstrap/*). A bootstrap action that fails
  # fails the cluster launch, so it is not enabled until the capability check says
  # it is needed.
  bootstrap_actions = [{
    Name = "install-java"
    ScriptBootstrapAction = {
      Path = var.bootstrap_script_uri
      Args = [tostring(var.bootstrap_java_major)]
    }
  }]

  # RunJobFlow shape, as Step Functions elasticmapreduce:createCluster.sync expects.
  cluster_base = {
    Name                  = "${var.name_prefix}-pipeline"
    ReleaseLabel          = var.release_label
    ServiceRole           = var.service_role_arn
    JobFlowRole           = var.instance_profile_name
    LogUri                = var.log_uri
    VisibleToAllUsers     = false
    SecurityConfiguration = aws_emr_security_configuration.this.name
    Applications          = [{ Name = "Hadoop" }]
    AutoTerminationPolicy = { IdleTimeout = var.idle_timeout_seconds }
    Instances = {
      Ec2SubnetId                   = var.subnet_id
      EmrManagedMasterSecurityGroup = var.master_security_group_id
      EmrManagedSlaveSecurityGroup  = var.core_security_group_id
      ServiceAccessSecurityGroup    = var.service_security_group_id
      KeepJobFlowAliveWhenNoSteps   = true
      TerminationProtected          = false
      InstanceGroups                = local.instance_groups
    }
  }

  cluster_definition = merge(
    local.cluster_base,
    { for k, v in { ManagedScalingPolicy = local.managed_scaling } : k => v if local.has_core },
    { for k, v in { BootstrapActions = local.bootstrap_actions } : k => v if var.install_java_bootstrap },
  )
}

# S3 server-side encryption for EMRFS writes, with the AWS-owned key. Local disk
# and in-transit encryption need certificates or a KMS key and are not enabled.
resource "aws_emr_security_configuration" "this" {
  name = "${var.name_prefix}-emr"

  configuration = jsonencode({
    EncryptionConfiguration = {
      EnableInTransitEncryption = false
      EnableAtRestEncryption    = true
      AtRestEncryptionConfiguration = {
        S3EncryptionConfiguration = {
          EncryptionMode = "SSE-S3"
        }
      }
    }
  })
}
