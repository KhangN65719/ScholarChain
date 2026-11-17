#!/usr/bin/env bash
# Rehearsal of the Experiment B derive step on SYNTHETIC fixture JSON. No AWS, no network.
# It runs derive-capacity.py on two fixtures into a fresh temporary directory and checks the
# outcome with grep. Exit 0 and the line "REHEARSAL pass=<n> fail=0" mean the derive step
# behaves as specified; it says nothing about a real run.
#
# Usage: bash experiments/b-burst/rehearse.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sc-b-rehearse.XXXXXX")"
PASS=0
FAIL=0

check() {
  local name="$1" file="$2" pattern="$3"
  if grep -q -F -- "$pattern" "$file"; then
    PASS=$((PASS + 1))
    printf 'PASS %s\n' "$name"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL %s (expected to find: %s in %s)\n' "$name" "$pattern" "$file"
  fi
}

python3 -I "$HERE/derive-capacity.py" derive \
  --collected-dir "$HERE/fixtures/synthetic-scaling/collected" \
  --out-dir "$TMP_ROOT/scaling" \
  --title "SYNTHETIC FIXTURE: scale-out and scale-in"
python3 -I "$HERE/derive-capacity.py" derive \
  --collected-dir "$HERE/fixtures/synthetic-no-scaling/collected" \
  --out-dir "$TMP_ROOT/no-scaling" \
  --title "SYNTHETIC FIXTURE: fixed size"

S="$TMP_ROOT/scaling/derived.json"
check scaling.scale-out-yes "$S" '"scale_out_observed": "yes"'
check scaling.scale-out-count "$S" '"scale_out_instances": 2'
check scaling.scale-out-first-requested "$S" '"scale_out_first_requested_utc": "2026-11-18T15:20:40Z"'
check scaling.scale-out-first-provisioned "$S" '"scale_out_first_provisioned_utc": "2026-11-18T15:25:33Z"'
check scaling.scale-out-request-to-ready "$S" '"scale_out_request_to_ready_seconds": 293'
check scaling.scale-in-yes "$S" '"scale_in_observed": "yes"'
check scaling.scale-in-count "$S" '"scale_in_instances": 2'
check scaling.scale-in-last "$S" '"scale_in_last_released_utc": "2026-11-18T15:50:22Z"'
check scaling.peak-requested "$S" '"peak_requested_instances": 4'
check scaling.peak-provisioned "$S" '"peak_provisioned_instances": 4'
check scaling.wait-seconds "$S" '"wait_seconds": 900'
check scaling.step-seconds "$S" '"pipeline_step_seconds": 2140'
check scaling.cloudwatch-with-data "$S" '"TotalNodesRunning"'
check scaling.timeline-peak "$TMP_ROOT/scaling/capacity-timeline.csv" '2026-11-18T15:25:35Z,4,4,0,1,3,0'
check scaling.timeline-after-scale-in "$TMP_ROOT/scaling/capacity-timeline.csv" '2026-11-18T15:50:22Z,2,2,2,1,1,0'
check scaling.scale-events-csv "$TMP_ROOT/scaling/scale-events.csv" 'scale-in-released,ci-CORE0000002'
check scaling.svg-written "$TMP_ROOT/scaling/capacity.svg" '<svg xmlns='
check scaling.svg-title "$TMP_ROOT/scaling/capacity.svg" 'SYNTHETIC FIXTURE: scale-out and scale-in'
check scaling.summary-table "$TMP_ROOT/scaling/summary.md" '| TotalNodesRequested | 14 | 2 | 4 | 0 |'

N="$TMP_ROOT/no-scaling/derived.json"
check no-scaling.scale-out-no "$N" '"scale_out_observed": "no"'
check no-scaling.scale-in-no "$N" '"scale_in_observed": "no"'
check no-scaling.no-cloudwatch-listed "$N" '"cloudwatch_metrics_with_data": []'

# ---- R5 / M3: managed-scaling policy, floor-restoring labels, pending containers near events
check_absent() {
  local name="$1" file="$2" pattern="$3"
  if grep -q -F -- "$pattern" "$file"; then
    FAIL=$((FAIL + 1))
    printf 'FAIL %s (must NOT contain: %s in %s)\n' "$name" "$pattern" "$file"
  else
    PASS=$((PASS + 1))
    printf 'PASS %s\n' "$name"
  fi
}

python3 -I "$HERE/derive-capacity.py" derive \
  --collected-dir "$HERE/fixtures/synthetic-floor-restore/collected" \
  --out-dir "$TMP_ROOT/floor" \
  --title "SYNTHETIC FIXTURE: floor restore"
python3 -I "$HERE/derive-capacity.py" derive \
  --collected-dir "$HERE/fixtures/synthetic-unconfirmed/collected" \
  --out-dir "$TMP_ROOT/unconfirmed" \
  --title "SYNTHETIC FIXTURE: no demand evidence"
python3 -I "$HERE/derive-capacity.py" queries --cluster-id j-SYNTH0000001 > "$TMP_ROOT/queries.json"

check scaling.policy-minimum "$S" '"policy_minimum_units": 1'
check scaling.policy-maximum "$S" '"policy_maximum_units": 3'
check scaling.policy-max-core "$S" '"policy_maximum_core_units": 3'
check scaling.policy-max-on-demand "$S" '"policy_maximum_on_demand_units": 3'
check scaling.policy-available "$S" '"policy_available": "yes"'
check scaling.floor-restoring-zero "$S" '"floor_restoring_instances": 0'
check scaling.pending-near-scale-out "$TMP_ROOT/scaling/scale-events.csv" 'scale-out-requested,ci-CORE0000002,CORE,created,demand-linked,9,1.5'
check scaling.pending-near-scale-in-no-data "$TMP_ROOT/scaling/scale-events.csv" 'scale-in-released,ci-CORE0000002,CORE,INSTANCE_FAILURE,scale-in,no data,no data'
check scaling.pending-ratio-table "$TMP_ROOT/scaling/summary.md" '| ContainerPendingRatio | 5 | 0 | 1.5 | 0 |'
check scaling.summary-policy-row "$TMP_ROOT/scaling/summary.md" '| policy_maximum_core_units | 3 |'
check no-scaling.policy-absent "$N" '"policy_available": "no"'
check queries.pending-ratio "$TMP_ROOT/queries.json" '"MetricName": "ContainerPendingRatio"'
check queries.pending "$TMP_ROOT/queries.json" '"MetricName": "ContainerPending"'

F="$TMP_ROOT/floor/derived.json"
FE="$TMP_ROOT/floor/scale-events.csv"
check floor.scale-out-yes "$F" '"scale_out_observed": "yes"'
check floor.scale-out-count "$F" '"scale_out_instances": 1'
check floor.scale-out-first-requested "$F" '"scale_out_first_requested_utc": "2026-11-18T15:40:10Z"'
check floor.floor-restoring-count "$F" '"floor_restoring_instances": 1'
check floor.floor-restoring-row "$FE" 'floor-restoring-requested,ci-CORE0000002,CORE,created,floor-restoring,0,0'
check_absent floor.floor-restore-not-scale-out "$FE" 'scale-out-requested,ci-CORE0000002'
check floor.demand-linked-row "$FE" 'scale-out-requested,ci-CORE0000003,CORE,created,demand-linked,5,0.75'
check floor.no-evidence-count "$F" '"increase_without_demand_evidence": 1'
check floor.no-evidence-row "$FE" 'increase-without-demand-evidence-requested,ci-CORE0000004,CORE,created,no-pending-evidence,0,0'
check_absent floor.no-evidence-not-scale-out "$FE" 'scale-out-requested,ci-CORE0000004'
check floor.replaced-count "$F" '"ended_and_replaced_instances": 1'
check floor.replaced-row "$FE" 'ended-and-replaced,ci-CORE0000001,CORE,INSTANCE_FAILURE,not-scale-in,0,0'
check floor.scale-in-count "$F" '"scale_in_instances": 1'
check floor.scale-in-row "$FE" 'scale-in-released,ci-CORE0000003,CORE,INSTANCE_FAILURE,scale-in,2,0.25'
check_absent floor.replaced-not-scale-in "$FE" 'scale-in-released,ci-CORE0000001'
check floor.peak-nonprimary "$F" '"peak_nonprimary_provisioned": 2'

U="$TMP_ROOT/unconfirmed/derived.json"
UE="$TMP_ROOT/unconfirmed/scale-events.csv"
check unconfirmed.scale-out-unconfirmed "$U" '"scale_out_observed": "unconfirmed"'
check unconfirmed.scale-out-count "$U" '"scale_out_instances": 0'
check unconfirmed.no-evidence-count "$U" '"increase_without_demand_evidence": 1'
check unconfirmed.policy-absent "$U" '"policy_available": "no"'
check unconfirmed.row "$UE" 'increase-without-demand-evidence-requested,ci-CORE0000002,CORE,created,demand-unknown,no data,no data'
check_absent unconfirmed.not-scale-out "$UE" 'scale-out-requested'

# ---- R14: roles come from Cluster.InstanceGroups of emr-describe-cluster.json (the CLI has no
# `emr list-instance-groups`); with no InstanceGroups the role is reported NOT RECORDED, never guessed.
check scaling.roles-all-recorded "$S" '"instances_role_not_recorded": 0'
mkdir "$TMP_ROOT/no-groups-in"
cp -R "$HERE/fixtures/synthetic-no-scaling/collected/." "$TMP_ROOT/no-groups-in/"
printf '%s\n' '{"Cluster":{"Id":"j-SYNTH0000002","Status":{"State":"TERMINATED","Timeline":{"CreationDateTime":"2026-11-18T15:00:03+00:00","ReadyDateTime":"2026-11-18T15:06:10+00:00","EndDateTime":"2026-11-18T15:50:40+00:00"}}}}' > "$TMP_ROOT/no-groups-in/emr-describe-cluster.json"
python3 -I "$HERE/derive-capacity.py" derive \
  --collected-dir "$TMP_ROOT/no-groups-in" \
  --out-dir "$TMP_ROOT/no-groups" \
  --title "SYNTHETIC FIXTURE: no instance groups in describe-cluster"
check no-groups.role-not-recorded-in-csv "$TMP_ROOT/no-groups/instances.csv" 'NOT RECORDED'
check_absent no-groups.count-is-not-zero "$TMP_ROOT/no-groups/derived.json" '"instances_role_not_recorded": 0'

if [[ -e "$HERE/fixtures/synthetic-scaling/collected/derived.json" ]]; then
  FAIL=$((FAIL + 1))
  printf 'FAIL the derive step wrote into the fixture directory\n'
else
  PASS=$((PASS + 1))
  printf 'PASS raw fixture directory untouched\n'
fi

if python3 -I "$HERE/derive-capacity.py" derive --collected-dir "$HERE/fixtures/synthetic-scaling/collected" --out-dir "$TMP_ROOT/scaling" >/dev/null 2>&1; then
  FAIL=$((FAIL + 1))
  printf 'FAIL the derive step overwrote an existing output directory\n'
else
  PASS=$((PASS + 1))
  printf 'PASS an existing output directory is refused\n'
fi

printf 'OUTPUT_DIR=%s\n' "$TMP_ROOT"
printf 'REHEARSAL pass=%s fail=%s\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]]
