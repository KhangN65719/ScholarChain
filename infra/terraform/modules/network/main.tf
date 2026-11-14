terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

locals {
  use_nat       = var.egress_mode == "nat"
  use_endpoints = var.egress_mode == "endpoints"

  # The EMR managed service policy (v2) only acts on resources carrying this tag.
  emr_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }

  endpoint_services = local.use_endpoints ? toset(var.interface_endpoint_services) : toset([])
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge({ Name = "${var.name_prefix}-vpc" }, local.emr_tag)
}

# The default security group is emptied so nothing can use it by accident.
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-default-closed" }
}

resource "aws_subnet" "app" {
  count = 2

  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false

  tags = merge({ Name = "${var.name_prefix}-app-${count.index}", tier = "app" }, local.emr_tag)
}

resource "aws_subnet" "data" {
  count = 2

  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index + 2)
  map_public_ip_on_launch = false

  tags = { Name = "${var.name_prefix}-data-${count.index}", tier = "data" }
}

# Public subnets exist only to host the NAT gateway. No ingress component is
# deployed in them.
resource "aws_subnet" "public" {
  count = local.use_nat ? 2 : 0

  vpc_id                  = aws_vpc.this.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index + 4)
  map_public_ip_on_launch = false

  tags = { Name = "${var.name_prefix}-public-${count.index}", tier = "public" }
}

resource "aws_internet_gateway" "this" {
  count = local.use_nat ? 1 : 0

  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-igw" }
}

resource "aws_eip" "nat" {
  count = local.use_nat ? 1 : 0

  domain = "vpc"

  tags = { Name = "${var.name_prefix}-nat" }
}

resource "aws_nat_gateway" "this" {
  count = local.use_nat ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  subnet_id     = aws_subnet.public[0].id

  tags = { Name = "${var.name_prefix}-nat" }

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  count = local.use_nat ? 1 : 0

  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this[0].id
  }

  tags = { Name = "${var.name_prefix}-public" }
}

resource "aws_route_table_association" "public" {
  count = local.use_nat ? 2 : 0

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public[0].id
}

resource "aws_route_table" "app" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-app" }
}

resource "aws_route" "app_nat" {
  count = local.use_nat ? 1 : 0

  route_table_id         = aws_route_table.app.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[0].id
}

resource "aws_route_table_association" "app" {
  count = 2

  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app.id
}

# Data subnets never get a default route.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-data" }
}

resource "aws_route_table_association" "data" {
  count = 2

  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.app.id, aws_route_table.data.id]

  tags = { Name = "${var.name_prefix}-s3" }
}

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.app.id, aws_route_table.data.id]

  tags = { Name = "${var.name_prefix}-dynamodb" }
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.endpoint_services

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = slice(aws_subnet.app[*].id, 0, var.interface_endpoint_az_count)
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true

  tags = { Name = "${var.name_prefix}-${each.value}" }
}

# Security groups, one per role. Rules reference roles, never broad CIDRs,
# except 443 to the internet in nat mode.
resource "aws_security_group" "endpoints" {
  name        = "${var.name_prefix}-endpoints"
  description = "Interface VPC endpoints"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-endpoints" }
}

resource "aws_security_group" "services" {
  name        = "${var.name_prefix}-services"
  description = "Runtime fast and slow services"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-services" }
}

resource "aws_security_group" "loader" {
  name        = "${var.name_prefix}-loader"
  description = "Loader tasks"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-loader" }
}

resource "aws_security_group" "client" {
  name        = "${var.name_prefix}-client"
  description = "In-VPC smoke test and experiment clients"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-client" }
}

resource "aws_security_group" "neptune" {
  name        = "${var.name_prefix}-neptune"
  description = "Neptune cluster"
  vpc_id      = aws_vpc.this.id

  tags = { Name = "${var.name_prefix}-neptune" }
}

resource "aws_security_group" "emr_master" {
  name                   = "${var.name_prefix}-emr-master"
  description            = "EMR primary node"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  tags = merge({ Name = "${var.name_prefix}-emr-master" }, local.emr_tag)
}

resource "aws_security_group" "emr_core" {
  name                   = "${var.name_prefix}-emr-core"
  description            = "EMR core and task nodes"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  tags = merge({ Name = "${var.name_prefix}-emr-core" }, local.emr_tag)
}

resource "aws_security_group" "emr_service" {
  name                   = "${var.name_prefix}-emr-service"
  description            = "EMR service access for private subnets"
  vpc_id                 = aws_vpc.this.id
  revoke_rules_on_delete = true

  tags = merge({ Name = "${var.name_prefix}-emr-service" }, local.emr_tag)
}

# Endpoints accept 443 from every in-VPC role that calls AWS APIs.
resource "aws_vpc_security_group_ingress_rule" "endpoints_from_role" {
  for_each = {
    services   = aws_security_group.services.id
    loader     = aws_security_group.loader.id
    client     = aws_security_group.client.id
    emr_master = aws_security_group.emr_master.id
    emr_core   = aws_security_group.emr_core.id
  }

  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "HTTPS from ${each.key}"
}

# Roles that call AWS APIs: HTTPS to the endpoints, S3 and DynamoDB prefix lists.
resource "aws_vpc_security_group_egress_rule" "to_endpoints" {
  for_each = {
    services   = aws_security_group.services.id
    loader     = aws_security_group.loader.id
    client     = aws_security_group.client.id
    emr_master = aws_security_group.emr_master.id
    emr_core   = aws_security_group.emr_core.id
  }

  security_group_id            = each.value
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
  description                  = "HTTPS to interface endpoints"
}

# The neptune entry (work order 31, finding L1): the Neptune group had no egress
# rule (Terraform removes the default allow-all), and the bulk loader fetches its
# files from S3 from inside the cluster. The S3 gateway endpoint is attached to the
# data route table (see aws_vpc_endpoint.s3), so the route exists; this rule adds
# the 443 egress to the S3 prefix list only. UNVERIFIED that AWS requires it (the
# documentation was not read this round); it is a narrow rule, added as the safe
# side of a guess, and a live load settles it. It exists even when Neptune is off,
# because the group does.
resource "aws_vpc_security_group_egress_rule" "to_s3" {
  for_each = {
    services   = aws_security_group.services.id
    loader     = aws_security_group.loader.id
    emr_master = aws_security_group.emr_master.id
    emr_core   = aws_security_group.emr_core.id
    neptune    = aws_security_group.neptune.id
  }

  security_group_id = each.value
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS to S3 gateway endpoint"
}

resource "aws_vpc_security_group_egress_rule" "to_dynamodb" {
  for_each = {
    services = aws_security_group.services.id
    loader   = aws_security_group.loader.id
  }

  security_group_id = each.value
  prefix_list_id    = aws_vpc_endpoint.dynamodb.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS to DynamoDB gateway endpoint"
}

# Only in nat mode: HTTPS to the internet for AWS APIs without an endpoint.
resource "aws_vpc_security_group_egress_rule" "nat_https" {
  for_each = {
    for role, id in {
      services   = aws_security_group.services.id
      loader     = aws_security_group.loader.id
      emr_master = aws_security_group.emr_master.id
      emr_core   = aws_security_group.emr_core.id
    } : role => id if local.use_nat
  }

  security_group_id = each.value
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS through NAT"
}

# Neptune: only the services and the loader reach it.
resource "aws_vpc_security_group_ingress_rule" "neptune_from_role" {
  for_each = {
    services = aws_security_group.services.id
    loader   = aws_security_group.loader.id
  }

  security_group_id            = aws_security_group.neptune.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  description                  = "Neptune from ${each.key}"
}

resource "aws_vpc_security_group_egress_rule" "to_neptune" {
  for_each = {
    services = aws_security_group.services.id
    loader   = aws_security_group.loader.id
  }

  security_group_id            = each.value
  referenced_security_group_id = aws_security_group.neptune.id
  ip_protocol                  = "tcp"
  from_port                    = var.neptune_port
  to_port                      = var.neptune_port
  description                  = "Neptune"
}

# Services accept traffic only from the in-VPC client role.
resource "aws_vpc_security_group_ingress_rule" "services_from_client" {
  for_each = toset([for p in var.service_ports : tostring(p)])

  security_group_id            = aws_security_group.services.id
  referenced_security_group_id = aws_security_group.client.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  description                  = "Service port from client"
}

resource "aws_vpc_security_group_egress_rule" "client_to_services" {
  for_each = toset([for p in var.service_ports : tostring(p)])

  security_group_id            = aws_security_group.client.id
  referenced_security_group_id = aws_security_group.services.id
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  description                  = "Service port to services"
}

# EMR: all traffic inside the cluster (required by EMR), plus service access.
resource "aws_vpc_security_group_ingress_rule" "emr_master_from_core" {
  security_group_id            = aws_security_group.emr_master.id
  referenced_security_group_id = aws_security_group.emr_core.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic from core"
}

resource "aws_vpc_security_group_ingress_rule" "emr_master_from_self" {
  security_group_id            = aws_security_group.emr_master.id
  referenced_security_group_id = aws_security_group.emr_master.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic within primary"
}

resource "aws_vpc_security_group_ingress_rule" "emr_core_from_master" {
  security_group_id            = aws_security_group.emr_core.id
  referenced_security_group_id = aws_security_group.emr_master.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic from primary"
}

resource "aws_vpc_security_group_ingress_rule" "emr_core_from_self" {
  security_group_id            = aws_security_group.emr_core.id
  referenced_security_group_id = aws_security_group.emr_core.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic within core"
}

resource "aws_vpc_security_group_egress_rule" "emr_master_to_core" {
  security_group_id            = aws_security_group.emr_master.id
  referenced_security_group_id = aws_security_group.emr_core.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic to core"
}

resource "aws_vpc_security_group_egress_rule" "emr_master_to_self" {
  security_group_id            = aws_security_group.emr_master.id
  referenced_security_group_id = aws_security_group.emr_master.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic within primary"
}

resource "aws_vpc_security_group_egress_rule" "emr_core_to_master" {
  security_group_id            = aws_security_group.emr_core.id
  referenced_security_group_id = aws_security_group.emr_master.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic to primary"
}

resource "aws_vpc_security_group_egress_rule" "emr_core_to_self" {
  security_group_id            = aws_security_group.emr_core.id
  referenced_security_group_id = aws_security_group.emr_core.id
  ip_protocol                  = "-1"
  description                  = "Cluster-internal traffic within core"
}

resource "aws_vpc_security_group_ingress_rule" "emr_service_from_master" {
  security_group_id            = aws_security_group.emr_service.id
  referenced_security_group_id = aws_security_group.emr_master.id
  ip_protocol                  = "tcp"
  from_port                    = 9443
  to_port                      = 9443
  description                  = "EMR service access from primary"
}

resource "aws_vpc_security_group_egress_rule" "emr_master_to_service" {
  security_group_id            = aws_security_group.emr_master.id
  referenced_security_group_id = aws_security_group.emr_service.id
  ip_protocol                  = "tcp"
  from_port                    = 9443
  to_port                      = 9443
  description                  = "EMR service access"
}

resource "aws_vpc_security_group_egress_rule" "emr_service_to_role" {
  for_each = {
    master = aws_security_group.emr_master.id
    core   = aws_security_group.emr_core.id
  }

  security_group_id            = aws_security_group.emr_service.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  description                  = "EMR service access to ${each.key}"
}
