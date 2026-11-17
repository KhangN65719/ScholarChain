variable "max_receive_count" {
  type    = number
  default = 3
}

resource "aws_sqs_queue" "work" {
  name = "fx-embedding-work"

  redrive_policy = jsonencode({
    deadLetterTargetArn = "fx-dlq-arn"
    maxReceiveCount     = var.max_receive_count
  })
}
