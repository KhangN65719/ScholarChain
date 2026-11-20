# Hadoop job contracts

Contract of each MapReduce job and stage in `modules/hadoop`, written from the source on 2026-11-19 (work order WO-B14, decision P17-29). Nothing was executed to write it. Every statement about runtime behaviour is read from code or from a committed test and is not a measurement. A fact the code does not show is marked UNVERIFIED.

Citation form. `jobs/X.scala:N` is `modules/hadoop/src/main/scala/scholarchain/hadoop/jobs/X.scala` line N. `pipeline/X.scala:N` is the same directory with `pipeline/`. `test/X.scala:N` is `modules/hadoop/src/test/scala/scholarchain/hadoop/X.scala`. `run-s-pipeline.sh:N` is `scripts/emr/run-s-pipeline.sh`. A derived number (arithmetic on cited constants) is labelled "derived".

## 0. Facts shared by all seven jobs

### 0.1 Stages and job graph

Seven stages, one MapReduce job each, except source normalization, which runs once per dataset (`pipeline/RunContext.scala:185-202`, `jobs/PipelineDriver.scala:195-209`). Execution order is `PipelineDriver.run` (`jobs/PipelineDriver.scala:183-240`): dblp-extraction, source-normalization per dataset (sorted by name, `:164`), identifier-join, blocking, candidate-features, team-expansion, graph-export. The first stage that returns false stops the run and the driver returns 1 (`:241-246`). The published edge list is `jobs/PipelineDriver.scala:40-50`.

### 0.2 Path layout

All paths come from `RunContext` (`pipeline/RunContext.scala`):

- run prefix: `<outputRoot>/run=<runSlug>/snapshot=<snapshotId>` (`:101`); `runSlug` is the run id with `:` replaced by `-` (`:69`); `snapshotId` is the first 12 hex characters of a SHA-256 over the length-framed, sorted dataset-snapshot pairs (`:58-67`).
- stage directory: `<run prefix>/stage=<stage>` (`:108`); for source normalization `<stage dir>/dataset=<dataset>` (`:117-118`).
- artifact: `<stage dir>/<artifact segment>/v1` (`:129-134`, `:139-145`). The `v1` is `ContractSchema.currentVersionNumber` (`:93`); the literal `v1` is pinned by `test/LocalPipelineSuite.scala:213`.
- committed files: `ArtifactSink` writes base name `<segment>/v1/part` (`jobs/HadoopSink.scala:91-96`); the part files begin `part-` (filters at `test/MapReduceEndToEndSuite.scala:160`). The exact suffix (`-r-NNNNN`) is Hadoop `MultipleOutputs` behaviour, not read here: UNVERIFIED.
- rejects: `<stage dir>/rejects/v1/<REASON_CODE>/part` (`jobs/HadoopSink.scala:111-118`). For source normalization the stage dir includes `dataset=<d>`, so the reject directory is beneath it, not at `RunContext.rejectPath(stage)` (`pipeline/RunContext.scala:150-151`, which has no dataset segment). Reading both as the same place would be wrong.
- rerun: `FileOutputFormat` refuses an existing output directory, so reusing a run id over surviving output fails at submission (`pipeline/RunContext.scala:19-25`, `jobs/PipelineOptions.scala:9-18`; executed evidence `test/MapReduceEndToEndSuite.scala:434-451`). `run-s-pipeline.sh:101` applies the same rule to the work directory.
- Artifact to owning stage map: `pipeline/RunContext.scala:219-287`. A job may write only artifacts it owns (`jobs/JobSupport.scala:35-38`, `jobs/HadoopSink.scala:84-87`).

### 0.3 Shuffle key, partitioner and reducer count

- Map output key and value are `Text`, `Text` for every job (`jobs/JobSupport.scala:42-43`). The key is `<stream label>\u0001<logical key>` (`pipeline/PartitionKey.scala:37-45`, `jobs/HadoopSink.scala:31-36`). Reducer output is `(NullWritable, Text)` routed through `MultipleOutputs` (`jobs/JobSupport.scala:39-41`, `:52-61`).
- Partitioner: `StableKeyPartitioner` for every job (`jobs/JobSupport.scala:44`). It hashes the logical key, label removed, with SHA-256 and takes the first four bytes, top bit masked, modulo the partition count (`jobs/StableKeyPartitioner.scala:28-34`, `pipeline/PartitionKey.scala:16-23`). Covered by `test/PartitionerWiringSuite.scala:26-46`.
- Reducers: `setNumReduceTasks(run.partitionCount)` (`jobs/JobSupport.scala:45`). Default 4 (`jobs/JobConfig.scala:68`, `jobs/PipelineOptions.scala:79`). Setting: `-Dscholarchain.run.partitionCount=<n>` (`jobs/JobConfig.scala:20`). The count must be positive (`pipeline/RunContext.scala:38`). On EMR, `PARTITION_COUNT` is passed only when greater than 0 (`run-s-pipeline.sh:263`, `:267-270`); its value is 0 in `scripts/emr/spark-yarn.conf:67` and 8 for profile `b-burst` (`spark-yarn.conf:99`). Reducer counts default and 16 are asserted for every stage by `test/PartitionCountSettingSuite.scala:78-96`.
- Mapper count: not set in code; it follows the input splits of the input format. EMR passes `mapreduce.input.fileinputformat.split.maxsize` only when `SPLIT_MAX_BYTES` is above 0 (`run-s-pipeline.sh:264`, `:271-274`; 262144 for `b-burst`, `spark-yarn.conf:100`).
- Combiner: none. None of the seven `configure` methods calls `setCombinerClass` (`jobs/DblpExtractionJob.scala:179-193`, `jobs/SourceNormalizationJob.scala:175-193`, `jobs/IdentifierJoinJob.scala:128-142`, `jobs/BlockingJob.scala:163-177`, `jobs/CandidateFeatureJob.scala:139-153`, `jobs/PublicationTeamJob.scala:129-143`, `jobs/GraphExportJob.scala:200-220`).
- Speculative execution is off for map and reduce (`jobs/JobConfig.scala:98-104`, applied at `jobs/JobSupport.scala:67`).

### 0.4 Output format and codec

- Output format: `TextOutputFormat[NullWritable, Text]`, one JSON record per line, wrapped in `LazyOutputFormat` so empty default `part-r-*` files are not created (`jobs/JobSupport.scala:39`, `:62-65`, `:76-82`). Records are encoded with `ContractWire.encode` (`pipeline/TaskSink.scala:155-161` for `emitRecord`; rejects at `jobs/HadoopSink.scala:54`, `:101`). `HadoopSink.scala:36` and `:88` only write an already-encoded string.
- Final output compression: not set in code. The EMR command passes `-Dmapreduce.output.fileoutputformat.compress=false` (`run-s-pipeline.sh:313`). The Hadoop default when unset is not read here: UNVERIFIED.
- Intermediate (map output) compression: always on, codec `DefaultCodec` (zlib) unless `mapreduce.map.output.compress.codec` is already configured (`jobs/JobConfig.scala:81-96`). EMR sets the same codec explicitly (`run-s-pipeline.sh:312`, `scripts/emr/lib-emr.sh:49`).
- Input codecs: `-Dio.compression.codecs=$SC_CODECS` is the Default, Gzip and BZip2 codecs (`run-s-pipeline.sh:311`, `scripts/emr/lib-emr.sh:43`).

### 0.5 Bounds (one set for all jobs)

Read from the job configuration in every task (`jobs/JobConfig.scala:21-23`, `:59-72`), set from `-D` options at launch (`jobs/PipelineOptions.scala:80-82`):

| Bound | Key | Default | Constraint |
|---|---|---|---|
| block cap | `scholarchain.bounds.maxBlockSize` | 50 | greater than 1 (`pipeline/RunContext.scala:39`) |
| author-team cap | `scholarchain.bounds.maxAuthorTeamSize` | 50 | greater than 1 (`:40`) |
| pairs per work | `scholarchain.bounds.maxPairsPerWork` | 10000 | greater than 0 (`:41`) |

The EMR command passes no `scholarchain.bounds.*` option (`run-s-pipeline.sh:263-282`, `:308-321`), so an EMR run uses the defaults. The round trip of a non-default bound through the job configuration is `test/JobWiringSuite.scala:59-69`.

### 0.6 Counters

Declared once in `pipeline/TaskSink.scala:12-52`. A Hadoop counter spans the map and reduce phases of one job, which is why pair counts are split into `ExpandedPairs` (map side) and `EmittedPairs` (reduce side) (`:21-30`; executed check `test/MapReduceEndToEndSuite.scala:419-432`).

| Counter | Group | Meaning (from the declaration) |
|---|---|---|
| InputRecords | `scholarchain.input` | records read by a mapper |
| AcceptedRecords | `scholarchain.outcome` | records a mapper accepted |
| RejectedRecords | `scholarchain.outcome` | rejects, counted once where detected (`jobs/HadoopSink.scala:51-55`, `:99-101`); a reducer committing a map-detected reject does not count it again (`:103-118`) |
| DuplicateRecords | `scholarchain.outcome` | duplicates collapsed |
| ConflictRecords | `scholarchain.outcome` | same identity, different content |
| OversizeBlocks | `scholarchain.bounds` | block observed above `maxBlockSize` |
| OversizeAuthorTeams | `scholarchain.bounds` | team above `maxAuthorTeamSize` |
| SuppressedBlockingKeys | `scholarchain.bounds` | coauthor keys withheld by the per-work bound |
| ExpandedPairs, EmittedPairs, SuppressedPairs, SingleAuthorWorks | `scholarchain.pairs` | see job 6 |
| MissingKeyRecords, SchemaFailureRecords, ZeroAuthorWorks | `scholarchain.quality` | see per job |
| EmittedRecords | `scholarchain.output` | map-output records written with `emitRecord` (`pipeline/TaskSink.scala:155-161`); reducers use `emit` and do not add to it |

Per-stream written counters, group `scholarchain.streams` with one counter per stream label, are incremented by each reducer commit (`pipeline/TaskSink.scala:213-214`, `jobs/HadoopSink.scala:97`). The driver reads them to learn which artifacts a job really wrote (`jobs/PipelineDriver.scala:75-82`). `MultipleOutputs` counters are enabled (`jobs/JobSupport.scala:64`).

### 0.7 Reject handling

- A reject is a `RejectRecord` (`pipeline/TaskSink.scala:96-138`): schema version, stage, reason code, detail (whitespace collapsed, cut to 512 characters, `:134`), source record id, payload excerpt (cut to 2048 characters, `:115`, `:136`) and provenance.
- Reason codes: `MALFORMED_XML`, `MALFORMED_JSON`, `UNSUPPORTED_RECORD_TYPE`, `MISSING_REQUIRED_FIELD`, `MISSING_JOIN_KEY`, `SCHEMA_VIOLATION`, `IDENTIFIER_CONFLICT`, `OVERSIZE_BLOCK`, `OVERSIZE_AUTHOR_TEAM`, `OVERSIZE_COAUTHOR_FAN_OUT` (`pipeline/TaskSink.scala:57-74`). `MISSING_JOIN_KEY` is declared; no job read here emits it: UNVERIFIED for the unread normalizers and parsers.
- A mapper cannot write a reject directly, so it sends it through the shuffle under the key `rejects\u0001<reason code>`; the stage's reducer commits it (`jobs/HadoopSink.scala:38-55`). Consequence (derived from the partitioner rule in 0.3): every reject of one reason code lands on one reducer.
- A reject does not fail the job. The job fails on an uncaught exception, for example a reducer receiving an unowned stream (`jobs/DblpExtractionJob.scala:168-172`).
- The driver fails the run, not the job, when a dataset's expected artifact is absent: `PipelineDriver.requireExpected` (`jobs/PipelineDriver.scala:170-182`).

### 0.8 Entry points and launch

- `scholarchain.hadoop.jobs.PipelineTool`, for `hadoop jar` (`jobs/PipelineDriver.scala:249-264`, `:349-350`). Arguments: `<outputRoot> <dblpInput> [<dataset>=<path>]...` (`:318-328`). Exactly one DBLP input path (`:345`). Snapshot ids as `-Dscholarchain.run.snapshots=dblp=<id>,<dataset>=<id>,...` (`:251-254`); the set must name exactly dblp plus the supplied datasets (`jobs/PipelineOptions.scala:33-64`); with none set, built-in defaults apply with a warning (`:303-313`; defaults `pipeline/RunContext.scala:175-180`). Optional `-Dscholarchain.run.id` fixes the run id; otherwise a new one is minted (`jobs/PipelineOptions.scala:71-73`). Known datasets: those in `SourceNormalizers.byDataset`, which are `openalex`, `orcid`, `crossref` (`pipeline/SourceNormalizers.scala:538-545`).
- Exit codes: 0 all stages succeeded; 1 a stage failed (`jobs/PipelineDriver.scala:243-246`); 2 an argument or dataset error before any job (`:260-264`).
- Per-stage tools exist for each job (`DblpExtractionTool`, `SourceNormalizationTool`, `IdentifierJoinTool`, `BlockingTool`, `CandidateFeatureTool`, `PublicationTeamTool`, `GraphExportTool`); argument forms are in each section.
- Generic options given to the tool reach every stage's `Job` (`configure` copies the base configuration; asserted by `test/ToolConfigurationPropagationSuite.scala:35-70`).
- No committed standalone local `hadoop jar` recipe exists. Every `hadoop jar` mention in the repository (`scripts/emr/*`, including the EMR run at `run-s-pipeline.sh:308-309`; two scaladoc lines, `jobs/PipelineDriver.scala:248` and `jobs/DblpExtractionJob.scala:195`; `README.md:93`; `docs/demo-script.md:175`; `tests/scripts/README.md:116`; and the harness pins in `tests/scripts/run.sh`, for example `:4392`, `:6329`, `:11047`, `:11048`) refers to the EMR command or states there is no local recipe; none is a local recipe. The list comes from the independent review B12e and its re-review. Decision P17-20 documents the test-based sample instead (section 9).

### 0.9 S3 prefix pattern on EMR

`scripts/emr/run-s-pipeline.sh` (header says "Written, not executed" at `:14`; whether and when it later ran is in the phase records, not re-read here: UNVERIFIED in this file):

- Inputs: `s3://<data-bucket>/raw/<snapshot>/{dblp-subset.xml,openalex.jsonl,crossref.jsonl,orcid.jsonl}` are copied to the node (`:32`, `:122-128`). The Hadoop command reads them as `file://<work>/raw/...` by default, or `hdfs:///user/hadoop/sc/run/<run-id>/raw/...` when HDFS mode is selected (`:250-259`). Datasets passed: `crossref`, `openalex`, `orcid`, each as `<name>=<input dir>/<name>.jsonl` (`:284-287`); DBLP is `<input dir>/dblp-subset.xml` (`:321`).
- Output root passed to `PipelineTool`: `file://<work>/hadoop-out` or `hdfs:///user/hadoop/sc/run/<run-id>/hadoop-out` (`:251`, `:257`, `:321`). The run id is passed as the stable form `-Dscholarchain.run.id=run:v1:<uuid>` (`:315`; slug rules `:26-29`).
- The script requires exactly one `snapshot=<id>` directory under the Hadoop output (`:443-446`).
- Published prefix: `s3://<data-bucket>/curated/runs/<run-id>/hadoop/` (`:35`, `:577`). Beneath it the layout of 0.2 repeats: `run=<runSlug>/snapshot=<12 hex>/stage=<stage>/[dataset=<d>/]<artifact>/v1/part-...` (derived from `:577` and `pipeline/RunContext.scala:101-145`; the `<run-id>` slug and `<runSlug>` are equal by `:26-29` and `pipeline/RunContext.scala:69`, derived).
- Expected MapReduce applications: 1 + number of datasets + 5, which is 9 for three datasets (`:339-345`); command time limit 1500 s (`:308`).

---

## 1. dblp-extraction (DBLP XML to works, researchers, venues)

Class: `DblpExtractionJob` (`jobs/DblpExtractionJob.scala:27`). Stage name `dblp-extraction` (`pipeline/RunContext.scala:186`).

- **Input.** Input paths given to `FileInputFormat.addInputPath` (`:189`). In the driver: the single DBLP path argument (`jobs/PipelineDriver.scala:185`). Input format `DblpXmlInputFormat` (`:188`): reads whole XML elements, so no mapper sees a partial record (`jobs/DblpXmlInputFormat.scala:20-32`). A compressed file (gzip) is not splittable and becomes one split and one mapper (`:34-37`); an uncompressed file may split, and a reader claims a record only if its opening `<` is in its split (`:58-60`). Record tags default to `XmlRecordStream.allTags`, overridable with `scholarchain.input.recordTags` (`:46-50`, `jobs/JobConfig.scala:25`); the default tag set is in `XmlRecordStream`, not read: UNVERIFIED.
- **Output.** `<run>/stage=dblp-extraction/{works,researchers,venues}/v1` (`:35-39`, `pipeline/RunContext.scala:282-285`). Author mentions are not written here; they travel inside the work record and the graph-export stage projects them (`:30-34`). Rejects at `stage=dblp-extraction/rejects/v1/<code>/part`.
- **Mapper.** `ExtractMapper extends Mapper[LongWritable, Text, Text, Text]` (`:41`). In: byte offset of the element's `<`, element text (`jobs/DblpXmlInputFormat.scala:52-56`). Out: `Text "<stream>\u0001<workId|researcherId|venueId>"`, `Text json` (`:21-26`, `:63-88`). A person-record tag goes through `DblpParser.parseIdentity` to a researcher (`:59-69`); any other tag through `DblpParser.parse` to a work, its venue and its editors (`:70-88`).
- **Reducer.** `DeduplicateReducer extends Reducer[Text, Text, NullWritable, Text]` (`:121`). Keeps the lexicographically smallest encoding per key (`:143`), so deduplication does not depend on arrival order (`:117-120`).
- **Partitioner and reducers.** 0.3.
- **Bounds.** None applied in this job.
- **Counters.** InputRecords (`:57`); AcceptedRecords (`:62`, `:73`); RejectedRecords through `sink.reject`; SchemaFailureRecords only for reasons `MalformedXml` and `SchemaViolation` (`:98-100`); EmittedRecords by `emitRecord`; DuplicateRecords = copies beyond the first of a key (`:144-148`); ConflictRecords = distinct normalized titles minus one for one DBLP key (`:149-156`). MissingKeyRecords is not incremented here (a missing required field is a reject, not a missing-key count; `test/MapReduceEndToEndSuite.scala:245-259`).
- **Rejects.** A parse failure from `DblpParser` becomes a reject with source record id `dblp:offset/<offset>`, payload the XML, provenance source `dblp` (`:92-115`). A key with several distinct titles produces an `IDENTIFIER_CONFLICT` reject in the reducer, payload the titles (`:152-167`). Which parser failures map to which reason is in `DblpParser`, not read: UNVERIFIED.
- **Driver check.** After the job, the artifacts written are read from stream counters and `works` must be among them (`jobs/PipelineDriver.scala:184-194`).
- **Standalone tool.** `DblpExtractionTool`, arguments `<outputRoot> <input>...` (`jobs/DblpExtractionJob.scala:196-208`).
- **Tests.** Section 9.

## 2. source-normalization (OpenAlex, ORCID, Crossref JSON lines to canonical records)

Class: `SourceNormalizationJob` (`jobs/SourceNormalizationJob.scala:26`). Stage `source-normalization` (`pipeline/RunContext.scala:187`). One job per dataset (`jobs/PipelineDriver.scala:195-209`).

- **Input.** The dataset's JSON-lines paths (`:188`), `TextInputFormat` (`:187`). The dataset name travels in `scholarchain.input.dataset` (`:183`, `:192`, `jobs/JobConfig.scala:24`); a task without it throws (`:51-57`). In the driver: the paths given as `<dataset>=<path>` (`jobs/PipelineDriver.scala:199`). EMR: `<name>=<input dir>/<name>.jsonl` (`run-s-pipeline.sh:284-287`).
- **Output.** `<run>/stage=source-normalization/dataset=<d>/{works,researchers,topics,institutions,venues}/v1` (`:29-35`, `:191`, `pipeline/RunContext.scala:262-281`). A dataset writes only the artifacts its records contain; for example ORCID only researchers, Crossref no topics or institutions (`jobs/PipelineDriver.scala:52-59`; asserted `test/PipelineDriverOutputsSuite.scala:162-181`). Rejects at `.../dataset=<d>/rejects/v1/<code>/part` (see 0.2).
- **Mapper.** `NormalizeMapper extends Mapper[LongWritable, Text, Text, Text]` (`:37`). In: byte offset, one JSON line (blank lines skipped, `:65-66`). Out: `Text "<stream>\u0001<entityId>"`, `Text json` for works, researchers, topics, institutions, venues (`:100-124`). The normalizer is picked by dataset from `SourceNormalizers.byDataset` (`:58`).
- **Reducer.** `NormalizeReducer extends Reducer[Text, Text, NullWritable, Text]` (`:140`). Collapses identical encodings silently (`.distinct`, `:154`); more than one distinct encoding for one entity id is a conflict, and the smallest encoding is kept (`:160-164`).
- **Partitioner and reducers.** 0.3.
- **Bounds.** None applied in this job.
- **Counters.** InputRecords (`:67`); AcceptedRecords (`:101`); RejectedRecords; SchemaFailureRecords for `MalformedJson` and for every record of a dataset with no normalizer (`:70`, `:85-86`); MissingKeyRecords for `MissingRequiredField` (`:87-88`) and for an accepted record that has no exact identifier (`:125-126`; such a record is counted both accepted and missing-key); ConflictRecords (`:163`). DuplicateRecords is not incremented; identical repeats vanish without a count (`:154`).
- **Rejects.** `UNSUPPORTED_RECORD_TYPE` when no normalizer is registered (`:69-79`); normalizer failures with their reason, source record id `<dataset>:offset/<offset>` (`:81-98`). The mapping of failures to reasons inside each normalizer is in `SourceNormalizers`, only partly read (`pipeline/SourceNormalizers.scala:78-100`): UNVERIFIED for the rest.
- **Driver check.** Per dataset, the artifacts written are recorded (`jobs/PipelineDriver.scala:201-202`) and compared with `SourceNormalizers.expectedOutputs`: openalex and crossref must write works; orcid must write researchers (`pipeline/SourceNormalizers.scala:557-561`, `jobs/PipelineDriver.scala:203-207`). A shortfall fails the run at this stage (executed check `test/PipelineDriverOutputsSuite.scala:218-231`).
- **Standalone tool.** `SourceNormalizationTool`, arguments `<outputRoot> <dataset> <input>...` (`jobs/SourceNormalizationJob.scala:195-214`).

## 3. identifier-join (exact identifier join preparation)

Class: `IdentifierJoinJob` (`jobs/IdentifierJoinJob.scala:26`). Stage `identifier-join` (`pipeline/RunContext.scala:188`).

- **Input.** Every normalized-works path that was written, plus DBLP works: `PipelineDriver.workPaths` (`jobs/PipelineDriver.scala:103-105`, used at `:213`). `TextInputFormat` (`:137`).
- **Output.** `<run>/stage=identifier-join/identifier-keys/v1` (`:29-31`, `pipeline/RunContext.scala:235-240`).
- **Mapper.** `ClaimMapper extends Mapper[LongWritable, Text, Text, Text]` (`:33`). In: offset, work JSON. Out: one claim per normalized identifier the work carries, key `<namespace>|<value>` (the key shape is from the scaladoc at `:22` and `pipeline/IdentifierJoin.scala:95`; the format itself is built by `ExactIdentifiers.joinKey`, not read: UNVERIFIED), label `identity-link-inputs` (`pipeline/TaskSink.scala:239`, `pipeline/IdentifierJoin.scala:124-133`). Claims cover DOI, arXiv, the source's native id, and person identifiers (ORCID, OpenAlex author) attributed to the carrying mention (`pipeline/IdentifierJoin.scala:59-122`).
- **Reducer.** `JoinReducer extends Reducer[Text, Text, NullWritable, Text]` (`:73`). Resolves all claims for one identifier into one `IdentifierJoinKey` record (entity ids, sources, duplicate sources, conflicting titles; `pipeline/IdentifierJoin.scala:34-44`, `:141-197`) and writes it with `ContractWire.encode` (`:115-119`).
- **Partitioner and reducers.** 0.3.
- **Bounds.** None. The reducer materializes every claim of one identifier key (`:101-103`), so a very frequent identifier is not bounded by `maxBlockSize` or any other cap. Not exercised beyond the samples: UNVERIFIED.
- **Counters.** InputRecords, AcceptedRecords (`:50`, `:53`); SchemaFailureRecords for an undecodable work line (`:56`); MissingKeyRecords for a work with no identifier at all (mapper, `pipeline/IdentifierJoin.scala:130`) and for a key that does not resolve (reducer, `:121`); DuplicateRecords = extra same-source claims for a work-scoped identifier (`pipeline/IdentifierJoin.scala:155-166`); ConflictRecords = title disagreements plus a person identifier on several mentions of one source record (`:168-195`); EmittedRecords counted for the claims in the mapper.
- **Rejects.** Undecodable work line: `SCHEMA_VIOLATION`, source record id `works:offset/<offset>` (`:57-71`). A nonzero conflict count writes an `IDENTIFIER_CONFLICT` reject naming the titles and mentions (`:108-112`, `pipeline/IdentifierJoin.scala:234-268`).
- **Standalone tool.** `IdentifierJoinTool`, arguments `<outputRoot> <worksInput>...` (`:156-171`).

## 4. blocking (bounded candidate blocks)

Class: `BlockingJob` (`jobs/BlockingJob.scala:27`), logic in `pipeline/Blocking.scala`. Stage `blocking` (`pipeline/RunContext.scala:189`).

- **Input.** Same work paths as job 3 (`jobs/PipelineDriver.scala:216-220`). `TextInputFormat` (`:172`).
- **Output.** `<run>/stage=blocking/{blocking-groups,blocking-members}/v1` (`:30-33`, `pipeline/RunContext.scala:241-244`). Groups are `BlockingGroup` (block key, kind, strategy, observed size, retained size, truncated flag, member ids; `pipeline/Blocking.scala:92-100`). Members are `BlockEnvelope(blockKey, member)` (`:31`); a block of one member is not written (`jobs/BlockingJob.scala:140-156`).
- **Mapper.** `BlockingMapper extends Mapper[LongWritable, Text, Text, Text]` (`:35`). In: offset, work JSON. Out: `Text "blocking-keys\u0001<kind>|<strategy>|<parts>[|s<salt>]"`, `Text` `BlockMember` JSON (`pipeline/TaskSink.scala:238`, `pipeline/Blocking.scala:131-132`, `:335-376`). Work keys: title prefix of at most 24 code points, with year and alone (`:233-263`, cut at `:239-246`). Researcher keys: name and year, venue, initials, coauthor pair, affiliation (`:151-228`).
- **Salt.** Non-selective strategies get a salt segment from a SHA-256 of the member id, 8 buckets (`:123-141`). The doc names `NameInitials` and `TitlePrefix` as the non-selective ones (`:114-120`); the `selective` flag is defined in core `BlockStrategy`, not read: UNVERIFIED.
- **Reducer.** `BoundingReducer extends Reducer[Text, Text, NullWritable, Text]` (`:83`). Streams members into a `BoundedBlockSelector` that keeps the `maxBlockSize` smallest members by `(source, memberId)` and counts every record received (`jobs/BlockingJob.scala:113-115`, `pipeline/Blocking.scala:48-89`), so the retained subset depends on content, not arrival order.
- **Partitioner and reducers.** 0.3. The partition key is the block key including the salt, so the salt spreads a hot key across reducers.
- **Bounds.**
  - block cap `maxBlockSize` (default 50): a block observed above it is truncated to 50 distinct members (`jobs/BlockingJob.scala:121-139`).
  - per-work coauthor fan-out: `n * (n - 1)` coauthor keys against `maxPairsPerWork` (`pipeline/Blocking.scala:309-311`, `:343-357`). Above it the coauthor keys are withheld and the selective keys are kept. With the default 10000 the limit is 100 authors (derived: 100 x 99 = 9900, 101 x 100 = 10100).
- **Counters.** InputRecords, AcceptedRecords (`:52`, `:55`); SchemaFailureRecords (`:58`); EmittedRecords (map side); SuppressedBlockingKeys = expected key count of a suppressed work (`pipeline/Blocking.scala:346`); OversizeBlocks (`:123`); RejectedRecords.
- **Rejects.** `SCHEMA_VIOLATION` for an undecodable line (`:59-72`); `OVERSIZE_COAUTHOR_FAN_OUT` from the mapper (`pipeline/Blocking.scala:347-357`); `OVERSIZE_BLOCK` from the reducer with the observed size and the retained member ids (`jobs/BlockingJob.scala:124-139`).
- **Standalone tool.** `BlockingTool`, arguments `<outputRoot> <worksInput>...` (`:179-191`).

## 5. candidate-features (pair features inside each block)

Class: `CandidateFeatureJob` (`jobs/CandidateFeatureJob.scala:26`), features in `pipeline/MatchCandidates.scala`. Stage `candidate-features` (`pipeline/RunContext.scala:190`).

- **Input.** `<run>/stage=blocking/blocking-members/v1` only (`jobs/PipelineDriver.scala:221-228`). `TextInputFormat` (`:148`).
- **Output.** `<run>/stage=candidate-features/match-candidates/v1` (`:29-31`, `pipeline/RunContext.scala:245-250`).
- **Mapper.** `RegroupMapper extends Mapper[LongWritable, Text, Text, Text]` (`:33`). In: offset, `BlockEnvelope` JSON. Out: `Text "blocking-members\u0001<blockKey>"`, `Text` member JSON (`:51-58`).
- **Reducer.** `FeatureReducer extends Reducer[Text, Text, NullWritable, Text]` (`:79`). Re-applies the block bound rather than trusting job 4 (`:107-116`), then builds every pair of the retained members (`pipeline/MatchCandidates.scala:188-199`). A pair is dropped when the two members are the same, of different kinds, or two researcher mentions of one work (`:145-154`). The candidate orders its ends so `leftId < rightId` (`:156-157`). Features are 17 values (counted from the map literal) (names, title, year, venue, affiliation, coauthor, source, same work; `:73-128`). The job writes features and exact-identifier agreements and conflicts, with no match decision (`jobs/CandidateFeatureJob.scala:13-18`).
- **Partitioner and reducers.** 0.3, on the block key.
- **Bounds.** `maxBlockSize` re-applied (`:110`, `:114-115`). Largest number of candidates from one block: 1225, which is 50 times 49 divided by 2 (derived).
- **Counters.** InputRecords, AcceptedRecords (`:50`, `:53`); SchemaFailureRecords (`:61`); EmittedRecords (map side); OversizeBlocks when the observed block exceeds the cap (`:114-115`, no reject written here); ConflictRecords = number of exact-identifier conflicts on a candidate (`:119-123`).
- **Rejects.** Undecodable line: `SCHEMA_VIOLATION`, source record id `blocking-members:offset/<offset>` (`:59-77`). No other reject.
- **Cross-block duplicates.** The reducer groups by block key (`:94-101`) and `ArtifactSink.emit` ignores the key it is given (`jobs/HadoopSink.scala:77-96`); this job therefore does not collapse a pair that appears in two blocks. Whether entity resolution deduplicates is outside this module: UNVERIFIED.
- **Standalone tool.** `CandidateFeatureTool`, arguments `<outputRoot> <blockingMembers>...` (`:155-170`).

## 6. team-expansion (publication teams and coauthor pairs)

Classes: `PublicationTeamJob` (`jobs/PublicationTeamJob.scala:28`), logic in `pipeline/TeamExpansion.scala`. Stage `team-expansion` (`pipeline/RunContext.scala:191`).

- **Input.** Same work paths as job 3 (`jobs/PipelineDriver.scala:230-234`). `TextInputFormat` (`:138`).
- **Output.** `<run>/stage=team-expansion/{teams,author-papers,pair-candidates}/v1` (`:31-35`, `pipeline/RunContext.scala:230-234`).
- **Mapper.** `ExpandMapper extends Mapper[LongWritable, Text, Text, Text]` (`:37`). In: offset, work JSON. Out: `teams` keyed by work id, `author-papers` keyed by mention id, `pair-candidates` keyed by `<mentionA>|<mentionB>` with `mentionA < mentionB` (`pipeline/TeamExpansion.scala:105-147`, order at `:81-83`). A work of `n` authors yields `n * (n - 1) / 2` pairs (`:43-45`).
- **Reducer.** `ConsolidateReducer extends Reducer[Text, Text, NullWritable, Text]` (`:77`). Pairs: keep one record per work for a mention pair, ordered by work id, retaining the minimum under a total order over run id, source record, provenance and pair fields (`pipeline/TeamExpansion.scala:164-192`, `jobs/PublicationTeamJob.scala:93-106`). Teams and author-papers: drop identical repeats, keep the smallest (`:107-114`).
- **Partitioner and reducers.** 0.3, on the key of each stream.
- **Bounds.**
  - team cap `maxAuthorTeamSize` (default 50): an oversize team is counted and rejected but its pairs are still produced (`pipeline/TeamExpansion.scala:70`, `:116-128`).
  - pair cap `maxPairsPerWork` (default 10000): expansion is suppressed for the whole work when `n * (n - 1) / 2` exceeds it (`:71-72`, `:129-141`). The team and author-paper records are still written. With the defaults the first suppressed size is 142 authors (derived: 141 -> 9870, 142 -> 10011).
- **Counters.** InputRecords, AcceptedRecords (`:54`, `:57`); SchemaFailureRecords (`:60`); EmittedRecords (map side, 18 for the two-work test input, `test/MapReduceEndToEndSuite.scala:425`); ZeroAuthorWorks and SingleAuthorWorks (`pipeline/TeamExpansion.scala:113-115`); OversizeAuthorTeams (`:117`); SuppressedPairs (`:130`); ExpandedPairs, map side (`:151`); DuplicateRecords (`jobs/PublicationTeamJob.scala:98-99`, `:109-113`); EmittedPairs, reduce side, after consolidation (`:105`).
- **Rejects.** `OVERSIZE_AUTHOR_TEAM` for an oversize team, and a second `OVERSIZE_AUTHOR_TEAM` for a suppressed expansion, so one work can produce two rejects with the same code (`pipeline/TeamExpansion.scala:116-141`). Undecodable line: `SCHEMA_VIOLATION` (`jobs/PublicationTeamJob.scala:59-75`).
- **Resolution.** Author-paper records carry `researcherId = None`; resolution is left to entity resolution (`pipeline/TeamExpansion.scala:61-63`).
- **Standalone tool.** `PublicationTeamTool`, arguments `<outputRoot> <worksInput>...` (`jobs/PublicationTeamJob.scala:145-160`).

## 7. graph-export (canonical inputs for Spark)

Class: `GraphExportJob` (`jobs/GraphExportJob.scala:37`). Stage `graph-export` (`pipeline/RunContext.scala:192`).

- **Input.** Typed inputs built by `PipelineDriver.exportInputs` (`jobs/PipelineDriver.scala:118-142`): work paths to `WorkExportMapper`; researcher paths (DBLP and normalized) to `ResearcherExportMapper`; normalized topics, institutions and venues (plus DBLP venues) to their mappers. Each path is read only where a job reported writing it (`:52-59`). `MultipleInputs` binds the record class to the input path, so a misrouted input is a configuration error at submission (`jobs/GraphExportJob.scala:25-30`, `:210-217`). At least one input is required (`:205`). `TextInputFormat` per input.
- **Output.** `<run>/stage=graph-export/{works,author-mentions,researchers,topics,institutions,venues}/v1` (`:40-47`, `pipeline/RunContext.scala:225-254`). `Artifact.Manifest` (`run-manifest`) names this stage as owner (`:286-287`) but is not in `GraphExportJob.streams`; which code writes it is UNVERIFIED.
- **Mappers.** Abstract `ExportMapper extends Mapper[LongWritable, Text, Text, Text]` (`:53`); subclasses `WorkExportMapper`, `ResearcherExportMapper`, `TopicExportMapper`, `InstitutionExportMapper`, `VenueExportMapper` (`:105-149`). `WorkExportMapper` also emits each author mention as an `author-mentions` record, from the same decoded record (`:108-113`). A decode failure is caught and becomes a reject (`:78-99`).
- **Reducer.** `MergeReducer extends Reducer[Text, Text, NullWritable, Text]` (`:159`). Merges records several sources claim for one entity id: identical repeats are duplicates, more than one distinct encoding is a conflict, and the smallest is kept (`:179-186`).
- **Partitioner and reducers.** 0.3.
- **Bounds.** None applied in this job.
- **Counters.** InputRecords, AcceptedRecords (`:77`, `:80`); SchemaFailureRecords (`:83`); EmittedRecords (map side); DuplicateRecords (`:181-184`); ConflictRecords, which counts without writing a reject (`:185`); RejectedRecords.
- **Rejects.** `SCHEMA_VIOLATION`, source record id `<label>:offset/<offset>` (`:84-99`).
- **Standalone tool.** `GraphExportTool`, arguments `<outputRoot> <kind>=<path>...` with kind one of `works`, `researchers`, `topics`, `institutions`, `venues` (`:222-259`).

---

## 8. Local bounded sample: expected values

Inputs: `datasets/samples/{dblp.xml,openalex.jsonl,crossref.jsonl,orcid.jsonl}` (`test/TestRun.scala:42-48`). Expected values below are read from test assertions. The suites that hold them passed inside the `sbt check` run on `dc15299` recorded at H-8 part 1 (`docs/agent-runs/phase-17/human-commands.md`) and inside the human's gate `sbt clean check` on a fresh clone of `264f24b` (P17-39, `docs/agent-runs/phase-17/decisions.md`); their per-suite output was not read, and no separate run was made for this document.

- DBLP: InputRecords 2, AcceptedRecords 2, no rejects; 2 works, 2 venues (`test/LocalPipelineSuite.scala:53-60`). Each of openalex, orcid, crossref: 2 and 2, no rejects (`:62-77`). ORCID writes no works and 2 researchers (`:79-83`). Six works from dblp, openalex, crossref (`:85-91`).
- Team expansion: 30 pairs, EmittedPairs 30, SingleAuthorWorks 3, 6 teams, 18 author-papers, no DuplicateRecords (`:110-124`). Largest team 5, largest pair expansion 10 (`:104-108`). OversizeAuthorTeams, SuppressedPairs, OversizeBlocks all 0 at the default bounds (`:126-130`). Every pair has `mentionA < mentionB` (`:146`).
- A repeated run is identical, record for record and counter for counter (`:150-178`).
- Real-job run over the four samples through all seven stages: driver returns 0; identifier-keys, blocking-members, teams, pair-candidates, works, topics, institutions, venues are non-empty; researchers include "Michael Ley"; no `dataset=orcid` works directory (`test/PipelineDriverOutputsSuite.scala:183-216`).
- Two-job DBLP and team run over a four-record input with one duplicate and one titleless record: map input 4, accepted 3, rejected 1, DuplicateRecords 2, reduce groups 5; second job EmittedPairs 10 (`test/MapReduceEndToEndSuite.scala:191-283`, `:419-432`).

Run commands (human, NOT REPLAYED): `sbt quick` runs `LocalPipelineSuite` and the other fast suites; `sbt check` also runs the three slow suites that execute real jobs under `LocalJobRunner` (`build.sbt:125-129`); one suite: `sbt "hadoop / testOnly scholarchain.hadoop.PipelineDriverOutputsSuite"` (WO-B7a report; not run, no record). `build.sbt` comment gives about 10 s for the seven-stage driver run (`build.sbt:125-126`); no other timing is recorded for this module here.

## 9. Test coverage per job

"Real" means the job runs under Hadoop's `LocalJobRunner` (`test/MapReduceEndToEndSuite.scala:35-40`), which runs one reduce task whatever the configured count (`:60-69`), so multi-reducer spread is not exercised by any suite read here. "Logic" means the transformation code runs in-process through `LocalPipelineRunner` with the same partition function, with no job submission (`test/LocalPipelineSuite.scala:15-26`).

Slow suites, run by `sbt check` and skipped by `sbt quick`: `MapReduceEndToEndSuite`, `PipelineDriverOutputsSuite`, `PipelineDriverUnknownDatasetSuite` (`build.sbt:125-129`).

| Job | Suite and lines | What it covers |
|---|---|---|
| all seven | `PipelineDriverOutputsSuite.scala:183-216` (real) | seven stages over the four samples, exit 0, non-empty artifacts |
| all seven | `PartitionCountSettingSuite.scala:78-96` | reduce count 4 by default and 16 when set, on every stage's configured job; a DBLP run with a larger count (`:140-`, remainder not read) |
| all seven | `ToolConfigurationPropagationSuite.scala:35-70` | generic options reach each `Job` (configuration only, nothing executed, `:12-14`) |
| all seven | `JobWiringSuite.scala:42-103` | run context and bounds round-trip through `JobConfig`; missing identity is refused |
| all seven | `LocalPipelineSuite.scala` (logic) | counters, bounds, reject outputs, determinism, path functions, one writer per artifact (`:53-263`) |
| partitioner | `PartitionerWiringSuite.scala:26-66`; `IdentifierAndKeySuite.scala` (key functions) | routing on the logical key, stable and in range |
| 1 dblp-extraction | `MapReduceEndToEndSuite.scala:191-314` (real); `:496-550` (real, rejects) | counters InputRecords 4, AcceptedRecords 3, RejectedRecords 1; split calculation over several splits, duplicate collapse, provenance, reject commit at the versioned path. `:496-550`: a six-record input with malformed and conflicting records gives InputRecords 6, AcceptedRecords 3, DuplicateRecords 1, ConflictRecords 1, RejectedRecords 4, SchemaFailureRecords 2, two works committed, and four rejects across `MALFORMED_XML`, `MISSING_REQUIRED_FIELD` and `IDENTIFIER_CONFLICT`; the job does not fail |
| 1 dblp-extraction | `DblpXmlRecordReaderLookbackSuite.scala:24-`; `XmlRecordStreamSuite.scala`; `PhaseTwelveDefectFixSuite.scala:30-` (comment and CDATA at split boundaries) | record reader and split-boundary behaviour (the last two only by name and header: remainder UNVERIFIED) |
| 1 dblp-extraction | `DblpParserSuite.scala`, `DblpEntitySuite.scala`, `DblpDtdEntitiesSuite.scala` | parser behaviour; not read: UNVERIFIED |
| 2 source-normalization | `PipelineDriverOutputsSuite.scala:138-181` (real, `:162-181`), `:218-231`; `LocalPipelineSuite.scala:62-83`; `SourceNormalizerSuite.scala`, `CrossrefAffiliationPrivacySuite.scala`, `NonLatinFoldingSuite.scala` | expected outputs match the samples; written-artifact counters; a dataset that writes no works fails the run; normalizer suites not read: UNVERIFIED |
| driver and tool | `PipelineToolArgumentsSuite.scala:14-`, `PipelineSnapshotSuite.scala:18-`, `PipelineDriverUnknownDatasetSuite.scala:33-` | argument parsing, snapshot validation and citation, unknown dataset refused |
| 3 identifier-join | `PipelineDriverOutputsSuite.scala:196-198` (real); `IdentifierAndKeySuite.scala:16-` | non-empty identifier-keys; DOI and key normalization. Join resolution rules (duplicates, conflicts) have no suite named here: UNVERIFIED |
| 4 blocking, 5 candidate-features | `BlockingAndCandidateSuite.scala:21-` (logic); `PipelineDriverOutputsSuite.scala:196-198` (real, blocking-members non-empty) | block bound and order-independent retention (header `:21-26`); match-candidates output is not asserted in the real run (UNVERIFIED) |
| 6 team-expansion | `MapReduceEndToEndSuite.scala:316-432` (real); `TeamExpansionSuite.scala:21-` (logic) | 18 reduce records in 18 groups, 3 artifacts, 10 pairs, pair counters not doubled; pair counts for 1, 2, 3 and larger teams and the oversize bound (header `:21-25`) |
| 7 graph-export | `PipelineDriverOutputsSuite.scala:101-119`, `:203-215` (real) | typed inputs list exactly what was written; works, topics, institutions, venues, researchers non-empty |
| rerun policy | `MapReduceEndToEndSuite.scala:434-465` (real) | same run id over existing output fails; a new run id writes a separate prefix |

Not covered by a suite read here: a multi-reducer or YARN run of any job in the test tree; reject output of jobs 3, 4, 5 and 7 under `LocalJobRunner` (job 1 rejects are covered at `MapReduceEndToEndSuite.scala:496-550`). Evidence from EMR runs, if any, is in the phase records and is not cited by this file.

## 10. Risks and open items

- Stale scaladoc at `jobs/PipelineDriver.scala:28-29`: "no stage has executed on a Hadoop runtime, so this submission-time behavior is read from Hadoop's contract, not observed". The real-job suites in section 9 execute every stage under `LocalJobRunner` (`test/PipelineDriverOutputsSuite.scala:183-216`), and `run-s-pipeline.sh` drives the same class on EMR. The sentence is out of date at least for `LocalJobRunner`. Not changed: a code change needs human approval (WO-B14). Owner of the fix: hadoop-engineer.
- No standalone local `hadoop jar` command is committed (0.8, confirmed by the B12e search); the bounded sample is test-based (P17-20).
- The identifier-join reducer holds every claim of one key in memory (job 3); no cap exists for it.
- Job 5 does not collapse a pair that occurs in two blocks (job 5).
- All rejects of one reason code share one reducer (0.7, derived).
- Several source files were not read for this document: `DblpParser`, `SourceNormalizers` (beyond the registry), `ExactIdentifiers`, `XmlRecordStream`, `TextNormalizer`, `LocalPipelineRunner`, and the suites marked above. Statements that depend on them are marked UNVERIFIED.
- `build.sbt:342` (Hadoop is `Provided`) is taken from the WO-B7a report and was not re-read here.
- No per-suite runtime output is recorded here. Counters and invariants in section 8 are assertions from tests; they passed within the aggregate H-8 part 1 `sbt check` (section 8) and within the human's gate `sbt clean check` on a fresh clone of `264f24b` (exit 0, all groups Failed 0 and Errors 0; `docs/agent-runs/phase-17/decisions.md`, P17-39). No per-suite output was read from either run, and no standalone run was replayed.
