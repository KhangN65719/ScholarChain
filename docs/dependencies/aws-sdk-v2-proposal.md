# AWS SDK for Java v2: version proposal for the AWS loader

Author: dependency-updater. Work order: docs/agent-runs/phase-14/work-order-21-dependency-updater-aws-sdk.md. Date: 2026-11-14.
Status: PROPOSAL, NOT APPROVED. The exact version is not yet chosen (see section 1). No project file was edited.

## Evidence limits

This session had no network tool. The Bash guard refused `curl`, and no web fetch or documentation tool was available. Nothing about the SDK was read from an upstream URL. Every SDK fact below is therefore UNVERIFIED and comes from prior knowledge. Facts read in this repository are marked VERIFIED (repo) with the file. The orchestrator or human must verify each UNVERIFIED item against the cited URL before approving.

## 1. Version and modules

Exact version: NOT DETERMINED. I cannot read the newest stable release, so I will not invent one. Candidate rule: the highest stable (non-preview, no `-PREVIEW`/`-alpha` suffix) `2.x` release listed in
`https://repo1.maven.org/maven2/software/amazon/awssdk/s3vectors/maven-metadata.xml` (UNVERIFIED), which by construction contains `s3vectors`. Cross-check that the same number exists for `bom` at `https://repo1.maven.org/maven2/software/amazon/awssdk/bom/maven-metadata.xml` (UNVERIFIED), because all modules release in lockstep.

First release with `s3vectors`: UNVERIFIED. My recollection is a 2.31.x release in July 2025 (S3 Vectors preview announcement), with the service GA later in 2025. Verify from the first version listed in the s3vectors maven-metadata and from `https://github.com/aws/aws-sdk-java-v2/blob/master/CHANGELOG.md`. Any proposed version must be at or after GA behavior, so do not accept a version that predates the GA API (preview request shapes may differ). UNVERIFIED.

sbt approach (design, not verified against upstream docs): the Maven BOM is not needed. Use one ledger value `awsSdk` and apply it to every module as a plain `"software.amazon.awssdk" % "<module>" % DependencyVersions.awsSdk`. All modules share a version number, so one key keeps them aligned. A single-version-per-module scheme would risk skew between `aws-core`, `sdk-core` and `auth`, so do not split the key.

Module coordinates (group `software.amazon.awssdk`, all Java, no Scala suffix, so use `%` not `%%`). Names UNVERIFIED against Maven Central, from memory:

| Module | Purpose | Declare directly |
|---|---|---|
| `s3` | object reads and writes, data lake | yes |
| `s3vectors` | S3 Vectors index and query | yes |
| `bedrockruntime` | Titan embeddings via InvokeModel | yes |
| `neptunedata` | data-plane API, including the bulk loader calls (StartLoaderJob, GetLoaderJob, ListLoaderJobs, openCypher, Gremlin) | yes; I believe it carries the loader, UNVERIFIED. The `neptune` module is the management plane (clusters, parameter groups) and not the loader, UNVERIFIED |
| `dynamodb` | optional adapter | only if the optional adapter is built |
| `url-connection-client` | the one sync HTTP client | yes |
| `sts` | needed on the runtime classpath only for web-identity or assume-role credential providers; the default provider chain loads it reflectively and fails if absent when a role or web-identity profile is used | yes if tasks or profiles assume roles, otherwise optional; decide with the infra-engineer's role design |
| `auth`, `regions`, `aws-core`, `sdk-core`, `http-client-spi`, `utils`, `retries` and others | transitives | no, they arrive through the service modules |

`neptunedata` loader reachability note: the Neptune endpoint is VPC-private, so the loader adapter can only run from inside the VPC. That is a deployment constraint, not a dependency one.

## 2. Compatibility

- Language and JDK: the SDK is a Java library targeting Java 8 bytecode (UNVERIFIED, `https://github.com/aws/aws-sdk-java-v2`). The build uses `-release:17` for scalac and javac (VERIFIED (repo), build.sbt lines 5 and 6), so there is no bytecode conflict and JDK 21 runs it. Scala 3.3.7 (VERIFIED (repo), DependencyVersions.scala) consumes Java classes directly. Builders use `Class.builder().build()` and Java `Consumer` overloads, which Scala 3 handles; use the non-consumer overloads with explicit request objects to avoid SAM ambiguity. Java 21 support: UNVERIFIED, expected since v2 supports newer LTS JDKs.
- Confinement. Verified from build.sbt (VERIFIED (repo)): `hadoop`, `entityResolution` and `sparkAnalytics` each have `.dependsOn(core)` only, and `core` has only upickle. `storage` is `.dependsOn(core)`, `rag` is `.dependsOn(core, storage)`, `services` is `.dependsOn(core, storage, rag)`. If the SDK is declared only in `storage`, it reaches `storage`, `rag` and `services` at runtime and nothing else. The job jars staged by `stageJob` read each job's own Runtime classpath, so they stay SDK-free. Two things to watch: (a) `root` has `storage % "test->compile"` and `rag`/`services` likewise, so the root test classpath gains the SDK, which is intended for `tests/` and harmless, but `sparkAnalytics`'s Test classpath does not; (b) do not add the SDK to `core` or to `root` Compile. A guard test could assert the three job classpaths contain no `software.amazon.awssdk` jar. That belongs to test-engineer and build-engineer, not to me.
- Transitive tree size: UNMEASURED and UNVERIFIED. Measure with `sbt storage/dependencyTree`-style output (not an agent-permitted sbt form) or by reading the resolved POMs. Expect roughly 25 to 40 SDK jars for the five or six modules, dominated by `aws-core`, `sdk-core`, the protocol modules and each service jar. The `s3` and `bedrockruntime` jars are large (several MB each). Treat all of this as an estimate.
- slf4j: `sdk-core` depends on `org.slf4j:slf4j-api` at the 1.7.x line (UNVERIFIED). The ledger pins 2.0.18 and `services` already declares it, so sbt evicts the older api. 2.0.x is a drop-in replacement for a 1.7 api caller, as build.sbt already argues for Hadoop. Expect an sbt eviction warning, not a failure. The SDK uses the api only and adds no binding.
- upickle: the SDK does not use upickle. Its JSON protocol uses a repackaged `third-party-jackson-core` (UNVERIFIED), so no Jackson jar competes with anything on the classpath. No conflict expected.
- hadoop 3.5.0 and spark 4.0.5: the SDK is not on those classpaths if confined as above. Hadoop's own `hadoop-aws` or bundled SDK is not a dependency of this build (VERIFIED (repo), the only Hadoop artifacts are `hadoop-client-api`, `hadoop-client-runtime` and `hadoop-client-minicluster`, all Provided or Test). The EMR cluster brings its own SDK, which is a reason to keep ours out of the job jars.
- HTTP client: the `s3`, `bedrockruntime` and other service modules pull `apache-client` and `netty-nio-client` at runtime scope by default (UNVERIFIED, standard SDK v2 packaging). Required handling: add `url-connection-client` and exclude the other two on every SDK module, in sbt for example `.excludeAll(ExclusionRule("software.amazon.awssdk", "apache-client"), ExclusionRule("software.amazon.awssdk", "netty-nio-client"))`, and select the client explicitly with `.httpClient(UrlConnectionHttpClient.create())` or `-Dsoftware.amazon.awssdk.http.service.impl=software.amazon.awssdk.http.urlconnection.UrlConnectionSdkHttpService`. With only one client on the classpath, the SDK autodetects it, but the explicit builder call removes ambiguity. Tradeoff: the URL connection client has fewer tuning options and no HTTP/2 (UNVERIFIED), so Bedrock streaming (`InvokeModelWithResponseStream`) and S3 async/transfer manager paths are out of scope and need an async client, which would reintroduce netty. Titan embeddings via the plain `InvokeModel` need neither. Note: the `s3` module's CRT-based transfer acceleration is not used.
- Vulnerability advisories: NOT CHECKED. Sources to read for the chosen version: `https://github.com/aws/aws-sdk-java-v2/security/advisories` and `https://github.com/aws/aws-sdk-java-v2/security` (UNVERIFIED), the GitHub Advisory Database filter for the Maven package `software.amazon.awssdk:aws-core`, and OSV (`https://osv.dev`) for each transitive that the resolved tree includes (`reactive-streams`, `slf4j-api`, `eventstream`, `third-party-jackson-core`). From memory I know of no standing critical advisory for the SDK itself, but that is not evidence. Netty advisories do not apply to us once `netty-nio-client` is excluded.

## 3. Image size and rollback

- Services image size effect: UNMEASURED. The services image copies every Runtime jar (VERIFIED (repo), `buildDockerLocal` copies `services / Runtime / dependencyClasspathAsJars`), so the SDK jars land in `lib`. Estimate only: tens of MB. Measure with `sbt dockerLocal` plus `docker image ls` before and after; that is outside my permitted commands and belongs to build-engineer or deploy-engineer.
- Rollback: delete the `awsSdk` line from project/DependencyVersions.scala and the SDK dependency lines in build.sbt, then rebuild. Because the key exists only for these lines and no module outside storage declares it, removal touches nothing else. Adapters that import `software.amazon.awssdk` will stop compiling, so roll back together with the adapter commits (git revert of the same change set). To change versions only, edit the single value.

## 4. Ledger key and line

Key: `awsSdk` (single key for all SDK modules). Matches the existing flat `val name = "x.y.z"` style.

Line to add to `object DependencyVersions` in project/DependencyVersions.scala, once the human approves the exact value (text only, not applied):

```scala
  val awsSdk = "<EXACT_APPROVED_VERSION>"
```

Authority note: adding a new key is routed to build-engineer (the dependency-updater may edit existing values only). The value is mine to maintain afterward. Build-engineer then adds, in `storage` only, the module lines for `s3`, `s3vectors`, `bedrockruntime`, `neptunedata`, `url-connection-client`, plus `sts` and `dynamodb` if the human wants them, each with `% DependencyVersions.awsSdk` and the two exclusions.

## Approval requested

The human approves one exact version after the orchestrator verifies the UNVERIFIED items. A major upgrade is not involved: this is a new dependency within v2.
