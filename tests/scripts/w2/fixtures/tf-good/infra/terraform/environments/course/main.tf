# Test fixture for tests/scripts/w2/tfcheck.py: the three module calls that tie the Wave 2 switches of the
# identity, runtime and observability modules to the one services stack flag (OD-1).
module "identity" {
  source = "../../modules/identity"

  enable_worker_role = var.enable_services_stack
}

module "runtime" {
  source = "../../modules/runtime"

  enable_services_stack = var.enable_services_stack
}

module "observability" {
  source = "../../modules/observability"

  enable_scaling_dashboard = var.enable_services_stack
}
