# Runbook repair 4: billed total, cosmetics, P15-33 (deploy-engineer, received 2026-11-17; summary as received)

- P15-32: `BILLED_USD=5.33` in the 0.1 command; prose and Expected state 5.33 + 1.15 = 6.48 <= 10.10 and 10.38 <= 14; OD-7 allowance kept. Status and placeholder lines (3, 5) and open items (~1094-1109) show only `REPLACE_WITH_CLUSTER_ID`; items renumbered 1-6.
- Cosmetics: `--no-renames` in prose 0.3 (~196), 0.7 (~244); B1.8 Expected (~1059) lists `MANIFEST_TABLES_COUNT=17 (must be 17)`.
- P15-33: A fails -> step 5, stop; no B, no b1 step in the session. Changed: step index B1 row (42), 2.11 (~632), 4.3 (~834), Failure branches A row (~1075), Step B1 heading and intro (977-989; one-sentence limitation citing P15-32 and P15-33); B1.0 post-session trigger; four-condition B option removed; B1.5, B1.7, B1.9 (~1037, 1049, 1061-1065) say post-session; B1 transfer allowance out of session cost; HIGH 3.90; B1.x and both BLOCKED-ON lines kept.
- Left as history: "Validation evidence" text still says revision 3 includes Step B1 as a fallback.
- Shell reads refused by the guard; worked with Read and Edit; read lines 1-1117 once.
- Next gate per owner: narrow re-review; orchestrator copies the file to `human-commands.md` before 0.4; 0.0 acknowledgement line still required.
