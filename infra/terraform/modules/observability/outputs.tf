output "dashboard_name" {
  description = "Dashboard name, or null when disabled."
  value       = one(aws_cloudwatch_dashboard.this[*].dashboard_name)
}
