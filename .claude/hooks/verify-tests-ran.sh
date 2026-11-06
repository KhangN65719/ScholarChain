#!/usr/bin/env bash
set -euo pipefail
command -v python3 >/dev/null || { echo "ScholarChain test gate requires Python 3." >&2; exit 2; }
exec python3 -B "$(dirname "$0")/policy.py" tests
