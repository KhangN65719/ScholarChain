output "cluster_definition" {
  description = "RunJobFlow parameters for the pipeline state machine. Contains no secrets."
  value       = local.cluster_definition
}

output "security_configuration_name" {
  description = "EMR security configuration name."
  value       = aws_emr_security_configuration.this.name
}
