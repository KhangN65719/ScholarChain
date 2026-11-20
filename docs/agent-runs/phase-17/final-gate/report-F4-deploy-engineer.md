# WO-F4 report (deploy-engineer), verbatim as received 2026-11-20

Orchestrator note: billing readings are recorded in `decisions.md` P17-17 (10.39 USD month-to-date on 2026-11-19) and P17-33 (10.44 USD on 2026-11-20 12:58). The F5 review checks whether the manifest should cite them.

---

**STATUS:** COMPLETE. The manifest is written. The `run.sh` result and the commit and tag fields are PENDING, as the work order expected.

**OWNER:** deploy-engineer

**WORK ORDER:** WO-F4, Phase 17 release manifest (plan item B9)

**FILES CREATED:** `/Users/winnguyen/ScholarChain/docs/releases/phase-17/README.md`

**FILES MODIFIED:** none

**COMMANDS RUN:** read-only only. I used Read on the records and Bash `ls` on three directories. `find`, `grep` and pipelines were refused by the guard, so I read files directly. I ran no build, package, deploy, plan, AWS, sbt or git command.

**VALIDATION:**
- Every value in the manifest carries a `file:line` source or is marked PENDING or NOT RECORDED.
- I re-read the prompt versions and model ids against the source. `Model.scala:65-67` gives Planner `planner-classify` 1.0.0, and `:76-78` gives Synthesis `synthesis` 1.2.0. The Haiku profile id is at `variables.tf:212` and the Titan id at `:230`.
- I checked the digit runs in the hex strings by eye against a 12-digit pattern. Two values contained a long digit run, so I truncated them and cited the line instead: the DBLP DTD md5 and the Crossref sample sha256.
- I did not scan the file for account ids, ARNs, bucket names or grader-directed text with a tool. The guard refused `grep`, so that check is by eye only.
- None of those appear that I know of. The only account value is `000000000000`.
- A reviewer's grep scan is still owed.

**Two things you should know about the content:**
- **Images do not match the code commit.** The last deployed images were built from the Phase 15 tag `38421c6f`, not from `264f24b`. The manifest says so. The digests are in section 3: Phase 15 services, loader and load-client, and the Phase 14 session-15 services and loader.
- **P16-34 note.** `services-smoke.tfvars:26` sets `slow_deadline_millis = 60000`. It was not deployed in Phase 17. Whether an earlier session deployed that line is not established by the records I read, and the manifest says so.

**RISKS:**
- The Terraform CLI version cited (v1.17.1) is the human's laptop reading from 2026-11-19. It is not the version `init` printed in the gate clone, and the manifest labels it that way.
- The working-tree description in section 2 comes from the session-start git status snapshot, not a fresh `git status`.
- I read the Phase 15 records for tags and digests only. I did not read `smoke-tn.md` or the Phase 14 session 12 to 14 records, so the manifest points to them rather than quoting them.

**BLOCKED-ON:** none for the manifest. The pending gates are owned by the human: the final documentation commit, the tag, and the `run.sh` result.

**PENDING and NOT RECORDED fields:**
- **PENDING, human (P17-6):** final documentation commit hash.
- **PENDING, human (P17-6):** release tag.
- **PENDING, human:** `tests/scripts/run.sh` result for this tree. The last full pass was 1949/0 on `2151c01`.
- **NOT RECORDED:** Terraform and provider versions printed by `init` in the gate clone.
- **NOT RECORDED:** Phase 17 plan checksum, because no plan ran.
- **NOT RECORDED:** prompt text hashes. They are in the Phase 16 run manifests.
- **NOT RECORDED:** agent `sbt quick` per-group totals beyond 77 and 1306, and its exit code.
- **NOT RECORDED:** build artifact and package checksums, because `package.sh` was not replayed.
- **NOT RECORDED:** run id of the Phase 15 reload, and run ids of SMOKE1, SMOKE2 and STUB1.
- **NOT RECORDED:** reconciliation of the Phase 14 run-6 snapshot labels (dblp 2026-11-05, others 2026-11-07) with the registered snapshot versions.
- **NOT RECORDED:** resource inventory at submission, unless demo item 22 is run.
- **NOT RECORDED:** billed spend for Phases 15 to 17 in the files I read.

**Next gate:** independent review through the orchestrator. That review should include the account-id, bucket-name and ARN scan.
