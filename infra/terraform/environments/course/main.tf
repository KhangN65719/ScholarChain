provider "aws" {
  region = var.region

  default_tags {
    tags = {
      project       = var.project
      phase         = var.phase
      owner         = var.owner
      environment   = var.environment
      "cost-center" = var.cost_center
    }
  }
}

# Plan-time only call (sts:GetCallerIdentity). Not made by init or validate.
data "aws_caller_identity" "current" {}

locals {
  account_id  = data.aws_caller_identity.current.account_id
  name_prefix = "${var.project}-${var.environment}"

  # cleanup_mode wins over persistence: it exists to make teardown possible.
  force_destroy       = var.cleanup_mode
  protect_stateful    = var.persistent_environment && !var.cleanup_mode
  keep_final_snapshot = var.persistent_environment && !var.cleanup_mode

  bedrock_model_arns = concat(
    ["arn:aws:bedrock:${var.region}:${local.account_id}:inference-profile/${var.bedrock_haiku_profile_id}"],
    [for r in var.bedrock_haiku_regions : "arn:aws:bedrock:${r}::foundation-model/${var.bedrock_haiku_model_id}"],
  )
  bedrock_embedding_model_arns = ["arn:aws:bedrock:${var.region}::foundation-model/${var.bedrock_titan_model_id}"]

  endpoint_services = concat(var.interface_endpoint_services, var.enable_bedrock ? ["bedrock-runtime"] : [])

  # Loader environment (Phase 14 work order 31). These are the names
  # scholarchain.rag.AwsIngestMain reads, from work order 30; AwsIngestMain was not
  # present in the tree when this was written, so the names are UNVERIFIED against
  # code. The Neptune pair exists only when Neptune does: with it off the loader is
  # expected to skip the graph step (UNVERIFIED: whether it needs the names absent
  # or empty; absent is chosen). The Neptune port is not passed: the loader
  # defaults to 8182 and the cluster listens on var.neptune_port's default 8182.
  # tomap() on both branches because an object and an empty object do not unify.
  loader_environment = merge(
    tomap({
      AWS_REGION                 = var.region
      SCHOLARCHAIN_DATA_BUCKET   = module.data_lake.data_bucket
      SCHOLARCHAIN_VECTOR_BUCKET = module.s3vectors.bucket_name
      SCHOLARCHAIN_TABLE_PREFIX  = local.name_prefix
    }),
    var.enable_neptune ? tomap({
      SCHOLARCHAIN_NEPTUNE_HOST          = module.neptune[0].endpoint
      SCHOLARCHAIN_NEPTUNE_LOAD_ROLE_ARN = module.neptune[0].bulk_load_role_arn
    }) : tomap({}),
  )

  # PROPOSED names, for the fast and slow services only. No loader reads these.
  # Whether the current services code reads any of them is UNVERIFIED (it could
  # not be searched in work order 31), so the services map is unchanged.
  container_environment = {
    AWS_REGION                 = var.region
    SCHOLARCHAIN_TABLE_PREFIX  = local.name_prefix
    SCHOLARCHAIN_DATA_BUCKET   = module.data_lake.data_bucket
    SCHOLARCHAIN_VECTOR_BUCKET = module.s3vectors.bucket_name
    SCHOLARCHAIN_VECTOR_DIM    = tostring(var.vector_dimension)
    SCHOLARCHAIN_BEDROCK       = var.enable_bedrock ? "on" : "off"
    SCHOLARCHAIN_NEPTUNE_HOST  = var.enable_neptune ? module.neptune[0].endpoint : ""
  }
}

module "foundation" {
  source = "../../modules/foundation"

  name_prefix                   = local.name_prefix
  create_budgets                = var.create_budgets
  total_usd                     = var.budget_total_usd
  alert_thresholds_usd          = var.budget_alert_thresholds_usd
  notification_emails           = var.budget_notification_emails
  activate_cost_allocation_tags = var.activate_cost_allocation_tags
}

module "network" {
  source = "../../modules/network"

  name_prefix                 = local.name_prefix
  region                      = var.region
  availability_zones          = var.availability_zones
  vpc_cidr                    = var.vpc_cidr
  egress_mode                 = var.egress_mode
  interface_endpoint_services = local.endpoint_services
  interface_endpoint_az_count = var.interface_endpoint_az_count
}

module "data_lake" {
  source = "../../modules/data_lake"

  name_prefix        = local.name_prefix
  account_id         = local.account_id
  force_destroy      = local.force_destroy
  versioning_enabled = var.persistent_environment
}

module "dynamodb" {
  source = "../../modules/dynamodb"

  table_prefix           = local.name_prefix
  deletion_protection    = local.protect_stateful
  point_in_time_recovery = var.persistent_environment
}

module "s3vectors" {
  source = "../../modules/s3vectors"

  bucket_name             = "${local.name_prefix}-${local.account_id}-vectors"
  force_destroy           = local.force_destroy
  dimension               = var.vector_dimension
  acknowledge_vector_loss = var.acknowledge_vector_loss
}

module "neptune" {
  source = "../../modules/neptune"
  count  = var.enable_neptune ? 1 : 0

  name_prefix           = local.name_prefix
  data_subnet_ids       = module.network.data_subnet_ids
  security_group_ids    = [module.network.security_group_ids["neptune"]]
  data_bucket_arn       = module.data_lake.data_bucket_arn
  engine_version        = var.neptune_engine_version
  mode                  = var.neptune_mode
  instance_class        = var.neptune_instance_class
  max_ncu               = var.neptune_max_ncu
  backup_retention_days = var.persistent_environment ? 7 : 1
  deletion_protection   = local.protect_stateful
  skip_final_snapshot   = !local.keep_final_snapshot
}

module "identity" {
  source = "../../modules/identity"

  name_prefix                     = local.name_prefix
  region                          = var.region
  account_id                      = local.account_id
  data_bucket_arn                 = module.data_lake.data_bucket_arn
  artifacts_bucket_arn            = module.data_lake.artifacts_bucket_arn
  table_arns                      = module.dynamodb.table_arns
  vector_bucket_arn               = module.s3vectors.bucket_arn
  vector_index_arns               = values(module.s3vectors.index_arns)
  neptune_enabled                 = var.enable_neptune
  neptune_cluster_resource_id     = one(module.neptune[*].cluster_resource_id)
  enable_bedrock                  = var.enable_bedrock
  bedrock_model_arns              = local.bedrock_model_arns
  bedrock_embedding_model_arns    = local.bedrock_embedding_model_arns
  secret_recovery_days            = var.cleanup_mode ? 0 : 7
  deployer_trusted_principal_arns = var.deployer_trusted_principal_arns
  deployer_require_mfa            = var.deployer_require_mfa
}

module "emr" {
  source = "../../modules/emr"

  name_prefix               = local.name_prefix
  service_role_arn          = module.identity.role_arns["emr_service"]
  instance_profile_name     = module.identity.emr_instance_profile_name
  subnet_id                 = module.network.app_subnet_ids[0]
  master_security_group_id  = module.network.security_group_ids["emr_master"]
  core_security_group_id    = module.network.security_group_ids["emr_core"]
  service_security_group_id = module.network.security_group_ids["emr_service"]
  log_uri                   = "s3://${module.data_lake.artifacts_bucket}/emr-logs/"
  release_label             = var.emr_release_label
  master_instance_type      = var.emr_master_instance_type
  core_instance_count       = var.emr_core_instance_count
  task_spot_instance_count  = var.emr_task_spot_instance_count
  managed_scaling_max_units = var.emr_managed_scaling_max_units
  idle_timeout_seconds      = var.emr_idle_timeout_seconds
  install_java_bootstrap    = var.emr_install_java_bootstrap
  bootstrap_script_uri      = "s3://${module.data_lake.artifacts_bucket}/bootstrap/install-java-bootstrap.sh"
  bootstrap_java_major      = var.emr_bootstrap_java_major
}

module "runtime" {
  source = "../../modules/runtime"

  name_prefix                = local.name_prefix
  region                     = var.region
  vpc_id                     = module.network.vpc_id
  enable_service_discovery   = var.enable_service_discovery
  app_subnet_ids             = module.network.app_subnet_ids
  services_security_group_id = module.network.security_group_ids["services"]
  loader_security_group_id   = module.network.security_group_ids["loader"]
  execution_role_arn         = module.identity.role_arns["ecs_execution"]
  runtime_role_arn           = module.identity.role_arns["runtime"]
  loader_role_arn            = module.identity.role_arns["loader"]
  secret_arns                = module.identity.secret_arns
  image_tag                  = var.image_tag
  force_delete_repositories  = local.force_destroy
  log_retention_days         = var.log_retention_days
  desired_count              = var.service_desired_count
  use_fargate_spot           = var.use_fargate_spot
  cpu_architecture           = var.cpu_architecture
  loader_cpu                 = var.loader_cpu
  loader_memory              = var.loader_memory
  container_environment      = local.container_environment
  loader_environment         = local.loader_environment
}

module "workflow" {
  source = "../../modules/workflow"

  name_prefix                    = local.name_prefix
  enable_pipeline                = var.enable_pipeline
  sfn_role_arn                   = module.identity.role_arns["sfn"]
  cluster_definition             = module.emr.cluster_definition
  artifacts_bucket               = module.data_lake.artifacts_bucket
  data_bucket                    = module.data_lake.data_bucket
  ecs_cluster_arn                = module.runtime.cluster_arn
  loader_task_definition_arn     = module.runtime.loader_task_definition_arn
  app_subnet_ids                 = module.network.app_subnet_ids
  loader_security_group_id       = module.network.security_group_ids["loader"]
  log_retention_days             = var.log_retention_days
  max_run_seconds                = var.pipeline_max_run_seconds
  create_cluster_timeout_seconds = var.pipeline_create_cluster_timeout_seconds
  emr_step_timeout_seconds       = var.pipeline_emr_step_timeout_seconds
  loader_timeout_seconds         = var.pipeline_loader_timeout_seconds
  wait_before_terminate_seconds  = var.pipeline_wait_before_terminate_seconds
  skip_loader_after_pipeline     = var.pipeline_skip_loader
  pipeline_profile               = var.pipeline_profile
}

module "observability" {
  source = "../../modules/observability"

  name_prefix                = local.name_prefix
  region                     = var.region
  enable_dashboard           = var.enable_dashboard
  pipeline_enabled           = var.enable_pipeline
  neptune_enabled            = var.enable_neptune
  state_machine_arn          = module.workflow.state_machine_arn
  dead_letter_queue_names    = module.workflow.dead_letter_queue_names
  ecs_cluster_name           = module.runtime.cluster_name
  ecs_service_names          = module.runtime.service_names
  neptune_cluster_identifier = one(module.neptune[*].cluster_identifier)
  neptune_mode               = var.neptune_mode
  neptune_max_ncu            = var.neptune_max_ncu
  alarm_actions              = var.alarm_sns_topic_arns
}
