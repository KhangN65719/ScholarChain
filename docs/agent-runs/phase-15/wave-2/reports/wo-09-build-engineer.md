# WO-9 report, build-engineer (received 2026-11-17; summary of the report as received; author: build-engineer subagent)

Status: COMPLETE, READY-FOR-SBT. No sbt, docker or AWS run; nothing compiled.

## Files
- `build.sbt`: `"sqs"` added to the storage AWS SDK artifact list (existing `DependencyVersions.awsSdk` key, same client excludes). `buildDockerLocal` fails the stage if `lib/` lacks `scholarchain/services/runtime/AwsRuntime.class`, `scholarchain/storage/EmbeddingWorkerMain.class`, or the `sqs-`, `bedrockruntime-`, `url-connection-` jars.
- `packaging/load-client/Dockerfile` (new): `ARG PYTHON_BASE` (no default; digest-pinned reference supplied at build), `USER 10001:10001`, `ENTRYPOINT ["python3","/opt/load-client/load_client.py"]`, `CMD ["--help"]`.
- `packaging/README.md`: stage check and load-client image described.
- Service `Dockerfile` unchanged (`-cp /opt/scholarchain/lib/*` covers both mains).

## Dependencies
`bedrockruntime` already on `storage`; rag and services get it transitively. `sqs` was missing, added with the existing ledger key; no new version value; not blocked on dependency-updater. `jobSdkGuard` unchanged.

## Gate tests
Mains and jars in `lib/`: the new `dockerLocal` check (human). `sqs` compile: storage compile in `sbt quick`. Job modules SDK-free: `jobSdkGuard`. `build.sbt` format: `scalafmtSbtCheck`.

## Human commands (as proposed; orchestrator normalizes them in `../human-commands.md`)
1. `sbt dockerLocal` (human), expected `[success]`, tag `scholarchain-local:phase-2`.
2. `docker run --rm scholarchain-local:phase-2 -version`; same image with `scholarchain.services.runtime.AwsRuntime --help` and `scholarchain.storage.EmbeddingWorkerMain --help` (exit 0, no `ClassNotFoundException`/`NoClassDefFoundError`; `--help` form UNVERIFIED until WO-7/WO-8 report).
3. Load client: resolve `python:3.12-slim-bookworm` digest with `docker buildx imagetools inspect`, build with `--build-arg PYTHON_BASE=...@sha256:<digest>` from a temp stage holding the Dockerfile and `load_client.py`, run `--help`, run `id` (expect uid 10001).

## Open items
Base-image digest not looked up (network refused for the role). New tests stay in `quick` (no new slow suite reported).
