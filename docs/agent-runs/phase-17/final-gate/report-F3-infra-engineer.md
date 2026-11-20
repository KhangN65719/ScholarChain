# WO-F3 report (infra-engineer), verbatim as received 2026-11-20

Orchestrator note: there is one Terraform root module, `infra/terraform/environments/course`; the rest are child modules under `infra/terraform/modules/` (`git ls-files infra/terraform`, run by the orchestrator). The checks are moved to the human; the commands are in `human-commands.md`, section "Phase 17 final gate, F3".

---

STATUS: BLOCKED-ON. No check ran.

OWNER: infra-engineer
WORK ORDER: WO-F3 (Phase 17 final gate)

FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN
These are the commands I tried. The guard refused every one before it executed:
1. `terraform fmt -check -recursive /Users/winnguyen/ScholarChain/infra/terraform`
2. `terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform fmt -check -recursive`
3. `terraform -chdir=/Users/winnguyen/ScholarChain/infra/terraform/environments/course init -backend=false`
4. `bash tests/scripts/hooks-guard.sh`
5. `bash tests/scripts/run.sh`
6. `/Users/winnguyen/ScholarChain/tests/scripts/hooks-guard.sh`

I also tried two `grep` calls on the work-order and decisions files. Those were refused as well, so I read `work-orders.md` with the Read tool. I never read `decisions.md` P17-38 and P17-39. I only know P17-39 through the WO-F3 text (no sbt alongside this run).

After the refusals I stopped trying commands. Because `init` was refused, I never ran `validate`. I did not list the Terraform root modules, so only `infra/terraform/environments/course` is named, and that name comes from the order.

RESULTS
- fmt output and exit code: none. The command never executed.
- init and validate output for each module: none. Neither command executed.
- Terraform version and provider versions: not obtained, because `init` never ran.
- `.gitignore` coverage of `.terraform/`: not checked.
- `hooks-guard.sh`: no `RESULT` line, no exit code, no `SKIPPED_CASES`, no FAIL case names.
- `run.sh`: no `RESULT` line, no exit code, no `SKIPPED_CASES`, no FAIL case names.
- No validation evidence exists from this order. Do not record any of these checks as passed.

BLOCKED-ON
Each refusal below is the guard's message, quoted verbatim:

- Commands 1, 2 and 3 (all `terraform` forms), and command 6 (the direct script path), each returned:
  `PreToolUse:Bash hook error: [bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/guard-dangerous.sh]: ScholarChain policy: This shell command is outside the role's permitted commands. Use structured file tools or route an approved check or script to its owner.`
- Commands 4 and 5 (`bash <script>`) each returned:
  `PreToolUse:Bash hook error: [bash "$CLAUDE_PROJECT_DIR"/.claude/hooks/guard-dangerous.sh]: ScholarChain policy: Run reviewed repository scripts directly. Shell and interpreter wrappers are blocked.`
- Two `grep` calls were refused with `...Use explicit commands and paths. Shell expansion, pipelines, chaining and redirection require a reviewed script.` and `...outside the role's permitted commands...`.

Owner: the human, or factory-engineer if the human ratifies a guard entry. The runtime guard in `.claude/hooks/` is factory-owned. It does not permit `terraform` (any subcommand), `bash <script>`, or direct invocation of `tests/scripts/*.sh` for this role. The sandbox was not the cause, and no provider download was attempted.

The guard message for `bash` says to run reviewed repository scripts directly. My direct call to `tests/scripts/hooks-guard.sh` was still refused, so the guard does not yet list that script as reviewed. I did not look for another way past the refusals.

MANUAL ACTION REQUIRED
- The human can run steps 1 to 4 with the `!` prefix, outside this role's guard: `terraform fmt -check -recursive` over `infra/terraform`, init and validate per root, `bash tests/scripts/hooks-guard.sh`, and `bash tests/scripts/run.sh`. Do not run any of it alongside sbt. The `run.sh` run takes about 40 minutes.
- Alternatively, factory-engineer can draft a guard allowance for these exact commands. That is a constitutional change and needs review, ratification and a fresh session.

NEXT GATE
The orchestrator should record F3 as BLOCKED-ON and not as pass or fail. The final-gate close (F4 to F6) should not cite a Terraform or harness result from this order.
