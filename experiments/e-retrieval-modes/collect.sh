#!/usr/bin/env bash
# Experiment E collector: the same read-only collector as Experiment D (the two run on the same
# stack and use the same SCHOLARCHAIN_QL_* settings); see experiments/d-query-load/collect.sh.
# Usage: AWS_PROFILE=scholarchain bash experiments/e-retrieval-modes/collect.sh --run-dir /abs/<OUTPUT_ROOT>/<run id>
set -euo pipefail
SC_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SC_HERE/../d-query-load/collect.sh" "$@"
