output "table_arns" {
  description = "Table ARNs keyed by table suffix (for example run-ledger)."
  value       = { for name, table in aws_dynamodb_table.this : name => table.arn }
}

output "table_names" {
  description = "Table names keyed by table suffix."
  value       = { for name, table in aws_dynamodb_table.this : name => table.name }
}
