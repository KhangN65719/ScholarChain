output "state_machine_arn" {
  description = "Pipeline state machine ARN, or null when disabled."
  value       = one(aws_sfn_state_machine.pipeline[*].arn)
}

output "state_machine_name" {
  description = "Pipeline state machine name, or null when disabled."
  value       = one(aws_sfn_state_machine.pipeline[*].name)
}

output "queue_urls" {
  description = "Work queue URLs keyed by short name."
  value       = { for name, queue in aws_sqs_queue.work : name => queue.url }
}

output "dead_letter_queue_names" {
  description = "Dead-letter queue names keyed by short name."
  value       = { for name, queue in aws_sqs_queue.dead_letter : name => queue.name }
}
