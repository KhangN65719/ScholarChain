# Phase 4 work order: workflow speedup (constitutional)

- Date: 2026-11-06 · Phase 4 · Dispatched by: orchestrator (main session) · Owner: `factory-engineer`
- Branch `main` at `6d89e3d`, clean except untracked `docs/agent-runs/.DS_Store`
- Goal stated by the human: **faster, cheaper phases.** Every item below serves that and nothing else.
- Gate: constitution audit by the author, then independent read-only review by `code-reviewer`, then human
  ratification, then a fresh orchestrator session. **Stop for review. Do not commit. Do not ratify.**

## Authority

Owned paths in scope: `CLAUDE.md`, `docs/agents.md` (only if an item below requires it), `.claude/agents/**`,
`.claude/hooks/**`, `.claude/settings.json`. Nothing else. This order **narrows** authority to the four items
below; it widens nothing. No application, build, dataset, Terraform or module source changes.

`docs/agent-runs/**` is orchestrator-owned. Return your report as your message; the orchestrator archives it.

---

## Item 1 — let agents run exactly `sbt check` and `sbt Test/compile` outside the sandbox

### Documented mechanism (verified before dispatch)

The orchestrator verified the setting against the official Claude Code documentation before writing this
order. Verbatim findings, with sources, are archived in
[`sandbox-exclusion-doc-evidence.md`](sandbox-exclusion-doc-evidence.md). Summary of what the docs establish:

- The mechanism is `sandbox.excludedCommands`, an **array of strings** under the `sandbox` key. A matching
  command runs with no filesystem restrictions and no network proxy. There is no permissions-based way to
  exclude a command from the sandbox; permission rules only decide approval.
- Patterns use `Bash(...)` permission-rule syntax, **not** regex. A pattern with no `*` is an **exact match**:
  `sbt check` matches only the exact text `sbt check`, not `sbt check --foo` and not `sbt checkAll`.
- In a compound command **every** subcommand must match an entry, so `sbt check && rm -rf x` stays sandboxed.
  A redirect, a `cd`, or `$(...)` keeps the whole call sandboxed. Matching is on call text, so a script or
  `make` target that invokes sbt internally does not match.
- Scope: valid in user, project, local and managed settings. Under an admin-required sandbox, entries in
  `.claude/settings.json` and `.claude/settings.local.json` are ignored.
- Whether a change to this key applies to a running session is **not documented**. Assume a restart may be
  required and record which you observed.
- A sandbox exclusion does **not** bypass hooks or permissions. PreToolUse hooks still run, and an excluded
  command still goes through the permission flow. `autoAllowBashIfSandboxed` covers sandboxed commands, so an
  excluded command may prompt unless an allow rule covers it.

### Required change

In `.claude/settings.json`, merge into the existing `sandbox` object, preserving every existing key
(`enabled`, the read and write deny lists, the unsandboxing switches) exactly as they are:

```json
"excludedCommands": ["sbt check", "sbt Test/compile"]
```

Exactly these two entries. **No wildcards, no `sbt *`, no `:*` suffix, no third entry.** If the permission
flow then prompts on these two commands, you may add exactly `Bash(sbt check)` and `Bash(sbt Test/compile)`
to `permissions.allow`; record it if you do, and add nothing broader.

Do not touch the read or write deny lists. Do not reintroduce a sandbox write allowlist — Phase 1 measured
that it gained nothing and it was deliberately removed.

Check whether the `guard-dangerous.sh` permitted-command list already admits both commands for the roles that
need them. Phase 1 evidence shows bare `sbt check` passing the guard while `java -version` was refused, so
`sbt Test/compile` may or may not pass today. If it does not, add exactly those two command forms to the
guard's permitted list and nothing else. Keep the guard's chaining, redirection and expansion refusals intact,
and keep the constitutional-path and sensitive-path scans ahead of any allowance.

### Probe and measure

Run, as policy probes of your own change, exactly:

1. `sbt Test/compile`
2. `sbt check`

Record for each: the exact command, the exit code, whether it ran inside or outside the sandbox, whether any
hook or permission prompt intercepted it, and verbatim the decisive output lines. Phase 1 recorded four
distinct sandbox-level failure modes; name explicitly whether each is now absent, in particular the
`java.io.tmpdir` denial at `FileAnalysisStore$BinaryFileStore.set` on `/var/folders/.../T/`, which was the
blocker that closed the gate as open (`docs/agent-runs/phase-01/sandbox-repair/`).

### Gate closure — read this carefully

The P1 open gate in `CLAUDE.md` ("An agent cannot run `sbt check` inside the shell sandbox", plus the stale
marker and `!`-prefix paragraphs) may be rewritten as closed **only if you observed `sbt check` complete
successfully inside your own turn.** If it did not complete, if it needed a restart you could not perform, or
if it completed only partially, **leave the gate open** and rewrite the paragraph to state precisely what the
exclusion did and did not change and what remains pending. Do not close a gate on a prediction. State in your
report which of these two outcomes occurred.

Your probe is control-plane evidence for the exclusion. It is **not** the build's recorded test-gate pass:
that remains the human's `!`-prefixed run or a `build-engineer` measurement. Keep the human `!` route
documented as still valid.

---

## Item 2 — add a `model:` line to every agent file

Add a `model:` frontmatter line to each file in `.claude/agents/**`:

- `opus` for `code-reviewer` only.
- `sonnet` for every other role: `build-engineer`, `domain-engineer`, `dataset-engineer`, `hadoop-engineer`,
  `entity-resolution-engineer`, `spark-engineer`, `storage-engineer`, `rag-engineer`, `service-engineer`,
  `test-engineer`, `infra-engineer`, `deploy-engineer`, `experiment-engineer`, `documentation-engineer`,
  `incident-responder`, `dependency-updater`, and `factory-engineer` itself.

Enumerate the directory rather than trusting this list; report any file present that the list omits, and any
listed role with no file. Change only the frontmatter. Do not alter any role's tool list, description or body
while doing this. Note in your report that a role file's own `model:` line does not bind the orchestrator's
`model` override at dispatch, so this sets the default, not a guarantee.

---

## Item 3 — add workflow rules to `CLAUDE.md`

Add, in the project-rules area, four rules. Phrase them in the file's existing voice and keep them short.

1. **Gate-to-test mapping before coding.** Before writing implementation code, the owning specialist lists
   which specific test proves each item of its primary gate. The list goes in the owner's report.
2. **Parallel independent tests.** `test-engineer` works from the specification in parallel with the owner
   rather than waiting for the implementation, and must **execute** its tests and report exact pass and fail
   counts. A written but unexecuted test is not evidence.
3. **Batched review rounds.** A review batches all of its findings into one repair round. A re-review covers
   only the items that changed, plus anything the repair newly touched.
4. **Short chat reports.** Orchestrator chat reports stay under about ten lines. Detail, evidence and verbatim
   output live in the `docs/agent-runs/phase-NN/` evidence files, not in chat.

Rule 3 must not weaken the existing bar that a review never fixes its own findings and never approves its own
work. Rule 4 must not become a licence to omit a blocker, a failure or an open gate from chat.

---

## Item 4 — reconcile role files with Items 1 and 3

Update the role files whose text instructs a specialist how to run sbt, so they match the new reality: which
commands an agent may now run itself, and what it must still route to the human. Search `.claude/agents/**`
for every mention of `sbt`, the shell sandbox and the `!` prefix, and fix each one. Where Item 1's probe did
not succeed, the role files must keep saying the human runs the check — consistency with what was measured,
not with what was intended. Also reflect Item 3's rules 1 and 2 in the role files for the owners and
`test-engineer`. Keep each role's declared tool list unchanged.

If a role file and `docs/agents.md` disagree after your edits, fix the role file; changing the ownership
matrix is outside this order unless an item above strictly requires it, and if you believe it does, report
`BLOCKED-ON` human instead of editing the matrix.

---

## Policy probes

Add or extend probes that measure this change rather than assert it. At minimum:

- A direct probe that the guard still refuses `sbt check && <anything>`, a redirected form, and a
  command-substitution form, so the exclusion did not open a chaining hole.
- A direct probe that the guard still refuses writes to constitutional paths and still refuses a
  non-permitted bare command.
- A probe that `sbt install`-style near-misses (`sbt checkAll`, `sbt check --foo`) are **not** matched by the
  exclusion, i.e. still sandboxed.
- Record the new probes in the restart-probe style already used in
  `docs/agent-runs/phase-01/orchestrator-update/restart-probes.md`: direct script probes and observed live
  interception stay **separate** evidence, and automatic enforcement is never reported from file existence.

State clearly which probes you ran directly in this session and which need a fresh-session observation.

## Required report fields

1. Every file created or modified, with exact paths, and a per-file summary of the change.
2. The exact `sandbox` object before and after, and the exact guard change if any.
3. Every command you ran, verbatim, with exit codes and the decisive output lines.
4. Item 1 outcome: gate closed with evidence, or gate left open with what is pending. Be explicit.
5. Probe results, separating direct probes from live interception, and naming what needs a fresh session.
6. Open gates after this change, including any you could not measure.
7. Assumptions and risks, each labelled as observed or inferred.
8. `BLOCKED-ON <owner>` with evidence for anything outside your authority. In particular, if the runtime
   denies your write to `.claude/settings.json`, report `BLOCKED-ON` human with the verbatim denial and supply
   the exact JSON the human should apply; do not work around the denial through the shell.
9. Next gate.

## Known risks to address, not to ignore

- An exclusion removes the filesystem **and network** sandbox for the matched command. `sbt` resolves
  dependencies over the network. Say plainly in your report that this widens network reach for those two
  commands, and that it is the intended cost of the change.
- The two entries are exact-match, so any future variant (`sbt -batch check`, `sbt "check"`) will not match.
  Note that in `CLAUDE.md` so a later phase does not read silence as coverage.
