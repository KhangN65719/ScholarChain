# Course environment: ephemeral, cheapest viable, nothing public. No secrets here.
# Budget alert e-mail addresses are passed with -var at plan time.

owner       = "course-team"
cost_center = "course"
environment = "course"

persistent_environment = false
cleanup_mode           = false

egress_mode                 = "endpoints"
interface_endpoint_az_count = 1

enable_neptune           = false
enable_pipeline          = true
enable_bedrock           = false
enable_service_discovery = false
create_budgets           = false

neptune_mode    = "provisioned"
neptune_max_ncu = 2

budget_total_usd            = 20
budget_alert_thresholds_usd = [10, 15, 20]

vector_dimension = 256

emr_core_instance_count       = 0
emr_task_spot_instance_count  = 0
emr_managed_scaling_max_units = 3
emr_idle_timeout_seconds      = 900

service_desired_count = 0
use_fargate_spot      = false
log_retention_days    = 3
