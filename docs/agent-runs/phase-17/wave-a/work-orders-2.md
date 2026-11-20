# Phase 17 Wave A2 work orders (F07 and R03), issued 2026-11-19 under P17-16

Common rules are as in `work-orders.md`: read `CLAUDE.md`, `docs/agents.md`, your role file, `../plan.md` and `../decisions.md` (P17-16). No AWS, no Python execution, no commit. Write only your write set; anything else is `BLOCKED-ON`. Never invent results. No text addressed to graders or agents. Make your FINAL message the full course-field report (STATUS, OWNER, WORK ORDER, FILES CREATED, FILES MODIFIED with exact before/after, COMMANDS RUN, TEST RESULTS, VALIDATION RESULTS, ASSUMPTIONS, RISKS, MANUAL ACTION REQUIRED, BLOCKED-ON OWNER, BLOCKED-ON EVIDENCE, NEXT GATE). No `.scala` or `.sbt` file changes in Wave A2, so no sbt is required.

## Interface fixed by the orchestrator (all three orders build to it)

`bash .claude/hooks/guard-dangerous.sh --list-categories` prints one line per example to stdout, as `<category-id><TAB><example command>`, and exits 0 without reading stdin. Category ids, in this order: `aws-delete-terminate`, `terraform`, `git`, `database`, `s3`, `neptune`, `dynamodb`, `vector-index`. There are at least two examples per category, taken from the commands the current header already lists as refused for every role. Examples use placeholder names only (`example-placeholder`, `x`, `i-0abc`), with no real account id, bucket or ARN. Any other invocation, including the hook's own (no arguments, JSON on stdin), behaves exactly as today: `exec python3 -B "$(dirname "$0")/policy.py" guard`.

## WO-A6 factory-engineer: F07 and R03 (constitutional)

Write set: `.mcp.json`, `.claude/hooks/guard-dangerous.sh`, `CLAUDE.md` (the MCP trust table row that names the GitHub token variable, only), and `.claude/hooks/validate.py` (only if it checks the `.mcp.json` header or the variable name).
- **F07:** `.mcp.json` `"Authorization": "${SCHOLARCHAIN_GITHUB_AUTH_HEADER}"`. Update the `CLAUDE.md` GitHub row to name `SCHOLARCHAIN_GITHUB_AUTH_HEADER`, holding the full header value `Bearer <fine-grained read-only token>`, injected outside the repository. Report every other file that names `SCHOLARCHAIN_GITHUB_READ_TOKEN` (for example `README.md`, `docs/**`, `tests/**`) as a finding for its owner; do not edit them.
- **R03:** add `readonly` bash arrays (or one array of `id<TAB>example` entries) holding the eight categories and their examples, and a `--list-categories` branch that prints them per the interface and exits 0. Keep the comment header, but shorten it if it now duplicates the array. Keep `set -euo pipefail`, the python3 check and the `exec` path unchanged for every other invocation. Blocking logic stays in `policy.py`; the array decides nothing.
- Report the full diff, the human checks (`bash -n`, `validate-agent-files.sh`, and `bash .claude/hooks/guard-dangerous.sh --list-categories`), and the restart probes (P1 refusal through the hook; P1b hook payload still refused after the change).

## WO-A7 test-engineer: harness against the interface

Write set: `tests/scripts/hooks-guard.sh`.
- Replace the header-heading cases and the "no non-comment line above the last three" case, which no longer hold, with cases on the interface: `--list-categories` exits 0; it prints exactly the eight ids in order, each with at least two examples; each example piped to `policy.py guard` as test-engineer exits 2 with a `ScholarChain policy:` message; and the script's last line is still `exec python3 -B "$(dirname "$0")/policy.py" guard`.
- A hook-payload case: JSON on stdin, no arguments, a refused command gives exit 2 through `guard-dangerous.sh` itself.
- Keep the existing 33 explicit R03 refusal cases and the role cases; weaken nothing. Placeholders only.
- You cannot run the harness: list each added, removed and changed case with its expected outcome, and the new expected total.

## WO-A8 documentation-engineer: README F07 bullet

Write set: `README.md`, the F07 bullet in "Autograder notes" only. State that `.mcp.json` sets `Authorization` to `${SCHOLARCHAIN_GITHUB_AUTH_HEADER}`, that the variable holds the full header value `Bearer <token>` and is set outside the repository, and that the variable name is recorded in the `CLAUDE.md` MCP trust table. Remove the old explanation of the literal `Bearer ` prefix. Cite `.mcp.json` and `CLAUDE.md` by line once WO-A6 lands; until then, write the planned state and say so.
