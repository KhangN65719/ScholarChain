# M-2 report, experiment-engineer (received 2026-11-17; summary of the report as received; author: experiment-engineer subagent)

Status: COMPLETE, not executed. Source: `infra/terraform/COST_ESTIMATE.md` "Session 2 (services) hourly burn and HIGH", "Provisional experiment burns" (line 137).

| Exp | MAX_MINUTES | Burn | Ceiling | Projection | Headroom |
|---|---|---|---|---|---|
| C | 60 | 0.70 -> 0.45 | 1.00 -> 0.50 | 0.45 | 0.05 |
| D | 70 | 0.85 -> 0.55 | 1.50 -> 0.65 | 0.6417 | 0.0083 |
| E | 60 | 0.62 -> 0.42 | 0.80 -> 0.45 | 0.42 | 0.03 |
| F | 30 | 0.62 -> 0.40 | 0.50 -> 0.25 | 0.20 | 0.05 |

PROVISIONAL markers removed (values now from COST_ESTIMATE.md); each header cites the source and carries a CAVEAT that the rates behind the 0.37 USD/h steady burn are UNVERIFIED. JSON copies updated to match. Flags: C's Titan token (0.02-0.06) and S3 Vectors PUT (0.05-0.067) costs sit outside the 0.50 ceiling (HIGH sum about 0.58); D headroom 0.0083; F note corrected (40 min would need a higher ceiling); approval records must bind the new manifest sha256; ACCOUNT_ID and OUTPUT_ROOT stay placeholders.

Human check: `bash experiments/check-manifest-json.sh` (expected `MANIFEST_JSON_CHECK OK manifests=6`).
