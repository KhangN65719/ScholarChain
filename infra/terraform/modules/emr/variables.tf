variable "name_prefix" {
  description = "Prefix for names."
  type        = string
}

variable "service_role_arn" {
  description = "EMR service role ARN."
  type        = string
}

variable "instance_profile_name" {
  description = "EC2 instance profile for nodes."
  type        = string
}

variable "subnet_id" {
  description = "Private application subnet for the cluster."
  type        = string
}

variable "master_security_group_id" {
  description = "EMR primary node security group."
  type        = string
}

variable "core_security_group_id" {
  description = "EMR core and task node security group."
  type        = string
}

variable "service_security_group_id" {
  description = "EMR service access security group."
  type        = string
}

variable "log_uri" {
  description = "s3:// URI under the artifacts bucket emr-logs/ prefix."
  type        = string
}

variable "release_label" {
  description = "EMR release label. Design B (Phase 14 D21): emr-7.10.0 (Hadoop 3.4.1-amzn-2, YARN MapReduce) with the Hadoop application only. Its Spark 3.5.5 on Scala 2.12 is not installed or used: Spark 4.0.5 comes from our own distribution (scripts/emr/). Whether the default JDK of this release is 17 is UNVERIFIED: the capability check decides."
  type        = string
  default     = "emr-7.10.0"
}

variable "install_java_bootstrap" {
  description = "Add a bootstrap action that installs Corretto and records JAVA_HOME (scripts/emr/install-java-bootstrap.sh). Off by default; turn it on only when the capability check reports no usable JDK. A failing bootstrap action fails the cluster launch. Needs the script uploaded to bootstrap/install-java-bootstrap.sh in the artifacts bucket first."
  type        = bool
  default     = false
}

variable "bootstrap_script_uri" {
  description = "s3:// URI of the java bootstrap script, under the artifacts bucket bootstrap/ prefix. Required when install_java_bootstrap is true."
  type        = string
  default     = ""

  validation {
    condition     = !var.install_java_bootstrap || startswith(var.bootstrap_script_uri, "s3://")
    error_message = "bootstrap_script_uri must be an s3:// URI when install_java_bootstrap is true."
  }
}

variable "bootstrap_java_major" {
  description = "Corretto major version the bootstrap installs: 17 (the Spark 4 minimum) or 21 (use it if the job jars were compiled for Java 21 bytecode and Corretto 17 fails with UnsupportedClassVersionError). Used only with install_java_bootstrap."
  type        = number
  default     = 17

  validation {
    condition     = contains([17, 21], var.bootstrap_java_major)
    error_message = "bootstrap_java_major must be 17 or 21."
  }
}

variable "master_instance_type" {
  description = "Primary node type. A single node (core_instance_count 0) is the cheapest shape that still runs both MapReduce and Spark."
  type        = string
  default     = "m5.xlarge"
}

variable "core_instance_type" {
  description = "Core node type."
  type        = string
  default     = "m5.xlarge"
}

variable "core_instance_count" {
  description = "Core nodes. 0 means a one-node cluster. Capped at 4, which the Phase 15 elasticity run needs (master plus 4 core nodes)."
  type        = number
  default     = 0

  validation {
    condition     = var.core_instance_count >= 0 && var.core_instance_count <= 4
    error_message = "core_instance_count must be 0 to 4."
  }
}

variable "task_spot_instance_count" {
  description = "Spot task nodes. 0 by default. Tasks hold no HDFS data, and a lost node costs a retried task, not a failed job, but only with at least one core node."
  type        = number
  default     = 0

  validation {
    condition     = var.task_spot_instance_count >= 0 && var.task_spot_instance_count <= 2
    error_message = "task_spot_instance_count must be 0 to 2."
  }
}

variable "managed_scaling_max_units" {
  description = "Managed scaling cap in instance units (master plus core plus task: the primary is counted here and subtracted in main.tf, because EMR ComputeLimits cover core and task nodes only). Applied only when there is at least one core node. Must cover the fixed node count, 1 + core + Spot task nodes (task nodes only with a core node), at most 1 + 4 + 2 = 7; the module also raises it to that count if it is lower. Default 3; the Phase 15 elasticity example uses 5."
  type        = number
  default     = 3

  validation {
    condition     = var.managed_scaling_max_units >= 1 && var.managed_scaling_max_units <= 7
    error_message = "managed_scaling_max_units must be 1 to 7."
  }

  validation {
    condition     = var.managed_scaling_max_units >= 1 + var.core_instance_count + (var.core_instance_count > 0 ? var.task_spot_instance_count : 0)
    error_message = "managed_scaling_max_units must be at least 1 + core nodes + Spot task nodes (task nodes count only with a core node)."
  }
}

variable "idle_timeout_seconds" {
  description = "Auto-termination after this idle time (60 to 86400 here, tighter than the service maximum)."
  type        = number
  default     = 900

  validation {
    condition     = var.idle_timeout_seconds >= 60 && var.idle_timeout_seconds <= 86400
    error_message = "idle_timeout_seconds must be 60 to 86400."
  }
}
