# Experiment B: EMR capacity over time

Derived from the raw files in `/Users/winnguyen/scholarchain-data/experiments/runs/exp-B-20261117T185928Z-5c69ba90/collected` only. Times are UTC. Definitions are in the header of `derive-capacity.py`.

| Quantity | Value |
|---|---|
| scale_out_observed | yes |
| scale_out_instances | 2 |
| scale_out_first_requested_utc | 2026-11-17T19:08:13Z |
| scale_out_first_provisioned_utc | 2026-11-17T19:10:51Z |
| scale_out_request_to_ready_seconds | 157 |
| scale_out_after_pipeline_step_start_seconds | 166 |
| floor_restoring_instances | 0 |
| increase_without_demand_evidence | 0 |
| instances_role_not_recorded | 0 |
| ended_and_replaced_instances | 0 |
| policy_available | yes |
| policy_unit_type | Instances |
| policy_minimum_units | 1 |
| policy_maximum_units | 3 |
| policy_maximum_core_units | 3 |
| policy_maximum_on_demand_units | 3 |
| peak_nonprimary_provisioned | 3 |
| scale_in_observed | yes |
| scale_in_instances | 3 |
| scale_in_first_released_utc | 2026-11-17T19:34:14Z |
| scale_in_last_released_utc | 2026-11-17T20:04:34Z |
| scale_in_before_terminate_seconds | 878 |
| peak_requested_instances | 4 |
| peak_provisioned_instances | 4 |
| cluster_created_utc | 2026-11-17T18:59:33Z |
| cluster_ready_utc | 2026-11-17T19:03:45Z |
| pipeline_step_entered_utc | 2026-11-17T19:05:26Z |
| pipeline_step_exited_utc | 2026-11-17T20:04:12Z |
| pipeline_step_seconds | 3526 |
| wait_entered_utc | 2026-11-17T20:04:12Z |
| wait_exited_utc | 2026-11-17T20:19:12Z |
| wait_seconds | 900 |
| terminate_cluster_entered_utc | 2026-11-17T20:19:12Z |
| cluster_ended_utc | 2026-11-17T20:05:37Z |

## CloudWatch series (period 300 s)

| Metric | Points | First | Max | Last |
|---|---|---|---|---|
| TotalNodesRequested | 12 | 2 | 3 | 1 |
| TotalNodesRunning | 12 | 1 | 3 | 1 |
| TotalUnitsRequested | 0 | no data | no data | no data |
| TotalUnitsRunning | 0 | no data | no data | no data |
| CoreNodesRunning | 12 | 1 | 3 | 1 |
| CoreNodesRequested | 12 | 2 | 3 | 1 |
| TaskNodesRunning | 12 | 0 | 0 | 0 |
| ContainerPending | 12 | 8 | 348 | 0 |
| ContainerPendingRatio | 12 | 2.66667 | 21.75 | 0 |
| ContainerAllocated | 12 | 4 | 16 | 0 |
| AppsPending | 12 | 1 | 1 | 0 |
| AppsRunning | 12 | 1 | 1 | 0 |
| YARNMemoryAvailablePercentage | 12 | 0 | 100 | 100 |
| IsIdle | 12 | 1 | 1 | 1 |

## Scale events

| Time (UTC) | Kind | Instance | Role | Detail |
|---|---|---|---|---|
| 2026-11-17T19:08:13Z | scale-out-requested | ci-001062711A75EA3R8P21 | CORE | created | demand-linked | 61 | 6.1 |
| 2026-11-17T19:10:51Z | scale-out-provisioned | ci-001062711A75EA3R8P21 | CORE | ready | demand-linked | 61 | 6.1 |
| 2026-11-17T19:12:14Z | scale-out-requested | ci-07035699C76S3LKELIT | CORE | created | demand-linked | 348 | 21.75 |
| 2026-11-17T19:14:52Z | scale-out-provisioned | ci-07035699C76S3LKELIT | CORE | ready | demand-linked | 348 | 21.75 |
| 2026-11-17T19:34:14Z | scale-in-released | ci-001062711A75EA3R8P21 | CORE | TERMINATED | scale-in | 94 | 6.13333 |
| 2026-11-17T19:34:14Z | scale-in-released | ci-06835842PBI6AKWV9ZOK | CORE | TERMINATED | scale-in | 94 | 6.13333 |
| 2026-11-17T20:04:34Z | scale-in-released | ci-07035699C76S3LKELIT | CORE | INSTANCE_FAILURE | scale-in | 0 | 0 |

## Input files (sha256)

- `COLLECTED.txt` 782a726dac68d72dc6462ad943e5c81d340a3e43e846fe61c3f209eb71997c62
- `cloudwatch-metric-data.json` 7c11c16a134445a56e05a11630c46e904de4c7e22d704b487d06bb027335986c
- `emr-describe-cluster.json` ebd3b86ebb8be33794243e8ed4ee53b81a4555b5988737685cc82bff7d9cdc14
- `emr-list-instances.json` c909034201cee55688f5ba94f553fa8e546dcbfc7c4aa2931332160a52ba75be
- `emr-managed-scaling-policy.json` 1c50347956219a1cf68cb9e4e05a1a743f6074cafc4261904e8ce5b1523066cf
- `sfn-history.json` 19628baea581b9fc9c82b4453680382d6f4f642138a78e45edd2364539508379
