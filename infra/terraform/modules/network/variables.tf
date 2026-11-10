variable "name_prefix" {
  description = "Prefix for resource names."
  type        = string
}

variable "region" {
  description = "AWS region, used for endpoint service names."
  type        = string
}

variable "availability_zones" {
  description = "Exactly two Availability Zones."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Provide exactly two Availability Zones."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR. A /16 is split into /20 subnets."
  type        = string
  default     = "10.20.0.0/16"
}

variable "egress_mode" {
  description = "endpoints: no internet path, VPC endpoints only. nat: one NAT gateway in one public subnet. none: gateway endpoints only (S3 and DynamoDB)."
  type        = string
  default     = "endpoints"

  validation {
    condition     = contains(["endpoints", "nat", "none"], var.egress_mode)
    error_message = "egress_mode must be endpoints, nat or none."
  }
}

variable "interface_endpoint_services" {
  description = "Short names of interface endpoints created when egress_mode is endpoints. Each bills per hour per AZ. s3vectors and sqs are needed because the in-VPC services and loader call S3 Vectors and SQS (IAM grants both) and their security groups allow egress only to the endpoint group and the S3 and DynamoDB prefix lists. s3vectors: com.amazonaws.<region>.s3vectors with private DNS s3vectors.<region>.api.aws (documented in the S3 Vectors PrivateLink page). sqs: the standard com.amazonaws.<region>.sqs (not read from the docs). Whether each service is offered in the subnet's Availability Zone is UNVERIFIED until a Phase 14 plan."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "logs", "secretsmanager", "sts", "elasticmapreduce", "s3vectors", "sqs"]
}

variable "interface_endpoint_az_count" {
  description = "How many AZs get interface endpoint network interfaces (1 or 2). One halves the hourly cost and adds cross-AZ data charges."
  type        = number
  default     = 1

  validation {
    condition     = contains([1, 2], var.interface_endpoint_az_count)
    error_message = "interface_endpoint_az_count must be 1 or 2."
  }
}

variable "service_ports" {
  description = "TCP ports the fast and slow services listen on. Proposed: the current code has no production entrypoint yet."
  type        = list(number)
  default     = [8080, 8081]
}

variable "neptune_port" {
  description = "Neptune listener port."
  type        = number
  default     = 8182
}
