output "role_arns" {
  description = "Role ARNs by name. deployer is null when no trusted principal is configured."
  value = {
    emr_service   = aws_iam_role.emr_service.arn
    emr_ec2       = aws_iam_role.emr_ec2.arn
    ecs_execution = aws_iam_role.ecs_execution.arn
    runtime       = aws_iam_role.runtime.arn
    loader        = aws_iam_role.loader.arn
    sfn           = aws_iam_role.sfn.arn
    deployer      = one(aws_iam_role.deployer[*].arn)
  }
}

output "emr_instance_profile_name" {
  description = "Instance profile for EMR nodes."
  value       = aws_iam_instance_profile.emr_ec2.name
}

output "secret_arns" {
  description = "Secret ARNs by name. These are references only. The secrets have no value in Terraform."
  value       = { for name, secret in aws_secretsmanager_secret.this : name => secret.arn }
}
