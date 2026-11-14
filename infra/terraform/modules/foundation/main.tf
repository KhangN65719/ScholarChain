terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

# Optional because an AWS Budget of $25 with alerts at $10, $15 and $20 already
# exists in the account, created outside Terraform (Phase 14 decision D1). This
# resource would be a second budget, capped at the code ceiling of $20 (D6).
# Creating it is a human decision.
resource "aws_budgets_budget" "total" {
  count = var.create_budgets ? 1 : 0

  name         = "${var.name_prefix}-total"
  budget_type  = "COST"
  limit_amount = tostring(var.total_usd)
  limit_unit   = "USD"
  time_unit    = var.time_unit

  cost_types {
    include_credit = false
    include_refund = false
  }

  dynamic "notification" {
    for_each = var.alert_thresholds_usd

    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "ABSOLUTE_VALUE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.notification_emails
    }
  }

  lifecycle {
    precondition {
      condition     = length(var.notification_emails) > 0
      error_message = "create_budgets needs at least one notification email."
    }
  }
}

# Activating a tag needs Cost Explorer and the tag to have appeared on a billed
# resource, so this is off by default.
resource "aws_ce_cost_allocation_tag" "this" {
  for_each = var.activate_cost_allocation_tags ? toset(var.cost_allocation_tag_keys) : toset([])

  tag_key = each.value
  status  = "Active"
}
