# Runbook repair after review 2 (deploy-engineer, received 2026-11-17; summary as received)

- MINOR-1: `--no-renames` added to `git diff --name-only` in 0.3 (:201) and 0.7 (:247). Prose at :196 still reads `git diff --name-only 6cd7341 HEAD` (unchanged).
- MINOR-2: B1.8 intro (:1053) and command (:1056) read the manifest once into `MAN`; print `MANIFEST_TABLES_COUNT=${NT} (must be 17)`; write branch requires `NT == 17` plus the existing slug and `sourceRunId` checks, otherwise `FAIL: ... STOP; nothing written` and `false`. `NT` = number of keys in the manifest `tables` object (`JsonlExport.scala` `manifestBytes`, :762-775).
- MINOR-3: :1020 uses `find ${DEST} -maxdepth 1 -type d -name 'run=*'` (form of `run-s-pipeline.sh:506`); `N == 1` check unchanged.
- Cosmetic: open-items list renumbered 5, 6, 7 (about :1107-1109).
Unverified: edited shell unrun and not read back; `NT` one-liner not run on a real manifest; with `pipefail`, an `aws s3 cp` failure in `$(...)` leaves `MAN` empty and the gate fails closed.
