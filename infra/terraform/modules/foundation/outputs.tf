output "budget_name" {
  description = "Budget name, or null when budgets are not created."
  value       = one(aws_budgets_budget.total[*].name)
}
