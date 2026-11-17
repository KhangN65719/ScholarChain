# Test fixture for tests/scripts/w2/tfcheck.py: the worker role and its policy exist only with the flag the
# environment ties to the services stack (OD-1).
resource "aws_iam_role" "worker" {
  count = var.enable_worker_role ? 1 : 0

  name = "fx-worker"
}

resource "aws_iam_role_policy" "worker" {
  count = var.enable_worker_role ? 1 : 0

  name = "worker-embed"
  role = aws_iam_role.worker[0].id
}
