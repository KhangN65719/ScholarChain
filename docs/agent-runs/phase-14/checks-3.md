# Checks 3: us-east-2 availability and manifest digests (human run, observed by the orchestrator from the pasted output)

Date: 2026-11-13. Log: /Users/winnguyen/scholarchain-data/logs/phase14-availability-check.log. Profile `scholarchain`, region us-east-2, read-only calls. Exit 0.

- Neptune `db.t4g.medium` is orderable in us-east-2: the call returned the class for engine versions 1.1.1.0 up to 1.5.0.1 (the output repeats many versions twice, which is how the API lists orderable options). Closes D7 for Neptune. (Docs listed this as NOT CONFIRMED; the Phase 12 R? note and the neptune variables.tf note can now say "confirmed by human CLI check, 2026-11-13".)
- EC2 offers `m5.xlarge` in us-east-2 (`INSTANCETYPEOFFERINGS m5.xlarge us-east-2 region`). That is the EC2 side only. EMR support for m5.xlarge in us-east-2 was not read from the EMR per-Region page (the page was too large to extract); EMR-on-EC2 supports m5 in all Regions where EMR runs, and the first CreateCluster will confirm. Residual risk: low, stays open until the S run.
- manifest sha256: the screen showed only four of six digests because the command ended in `tail -n 60`; the full list is in the log. Visible: c `93d163f1...b6f2`, d `9298cb9d...f374`, e `14fb3dcf...2d0a`... (see log for all six, including a and b).

Open from this check: the Terraform Neptune `engine_version` must be one of the listed orderable versions. Routed to infra-engineer (work order 08).
