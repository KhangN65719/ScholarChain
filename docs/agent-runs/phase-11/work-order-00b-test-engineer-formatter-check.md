# Work order 00b: test-engineer, live formatter-hook check (tests/ file)

Owner: test-engineer (tests/**). Date: 2026-11-08. Step 0, third file. domain-engineer already checked a module main file and a module test file (report-00a).

## Task

1. Pick one small, already-formatted Scala file under tests/ (for example tests/adversarial/Phase9Steer.scala or similar).
2. Run `git diff --stat` and record that it is clean.
3. Use Edit to introduce one deliberate formatting-only error (extra spaces around an operator, or bad indentation). No semantic change.
4. Read the file back and report whether the PostToolUse hook reformatted it to the original.
5. Run `git diff --stat` and report which files differ. Expected if the hook works: none.
6. Restore the exact original content if the hook did not, and confirm `git diff --stat` shows no diff.

## Constraints

Local only. One file, one deliberate error. No sbt. Read-only git only (bare `git diff --stat`). No commit. Do not touch production code, CLAUDE.md, docs/agents.md or .claude/**.

## Report

Standard contract plus: file path, exact edit, whether the hook reformatted it, `git diff --stat` after the edit and after the revert, final state.
