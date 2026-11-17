output "vpc_id" {
  description = "VPC ID."
  value       = aws_vpc.this.id
}

output "app_subnet_ids" {
  description = "Private application subnets (one per AZ)."
  value       = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  description = "Private data subnets (one per AZ), no default route."
  value       = aws_subnet.data[*].id
}

output "security_group_ids" {
  description = "Security group IDs by role."
  value = {
    endpoints   = aws_security_group.endpoints.id
    services    = aws_security_group.services.id
    loader      = aws_security_group.loader.id
    client      = aws_security_group.client.id
    neptune     = aws_security_group.neptune.id
    emr_master  = aws_security_group.emr_master.id
    emr_core    = aws_security_group.emr_core.id
    emr_service = aws_security_group.emr_service.id
  }
}

output "s3_prefix_list_id" {
  description = "Prefix list of the S3 gateway endpoint."
  value       = aws_vpc_endpoint.s3.prefix_list_id
}

output "egress_mode" {
  description = "The egress mode in effect."
  value       = var.egress_mode
}
