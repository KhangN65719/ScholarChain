# Work order 10: spark-engineer, repair round 1 (L3)

Owner: spark-engineer (modules/spark/**). Source: `docs/agent-runs/phase-08/review.md` round 1, L3. Same rules as work order 01: local only, no new dependency, no weakening or deleting tests, no edits outside modules/spark.

`JsonlExport.scala` ~546-553 and ~572-573:
1. The check that the output directory does not overlap the run directory (and the repo and `target` refusals) uses `normalize`, so a symlink can bypass it. Resolve both sides with `toRealPath()` (for a not-yet-existing output path, resolve its nearest existing ancestor) and compare real paths for overlap, repo and `target`. Refuse an output directory that is, or lies under, or contains the run directory after resolution, including via a symlinked ancestor.
2. `deleteTree(staging)` deletes any existing `.staging-<name>` directory beside the output directory. Make it safe: only delete a staging directory this tool created (for example one containing a marker file the tool writes with the export version and a random run token, and nothing else it did not write); if an existing `.staging-<name>` lacks the marker, refuse with a clear error and delete nothing. Never follow symlinks when deleting.
3. Feature tests in `JsonlExportSuite` (extend it; do not delete tests): an output path that reaches the run directory through a symlink is refused and nothing is written; an output directory that is a symlink into the repo or a `target` directory is refused; a stray `.staging-<name>` directory without the marker is not deleted and the export refuses; a stale staging directory with the marker is cleaned and the export proceeds.

Test-to-item list FIRST in your report. Run `sbt quick`; never two sbt commands at once; no `sbt check`. Put the COMPLETE report in your final message text and give gate status after it. Orchestrator archives it as `report-10-spark-repair1.md`.
