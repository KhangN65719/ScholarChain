# Work order 00a: domain-engineer, live formatter-hook check (main and test file)

Owner: domain-engineer (modules/core/**). Date: 2026-11-08. Part of Step 0: verify the formatter hook live in this fresh session (hook fix is commit 6842c2e).

## Task

Perform this for two files, strictly one after the other, with no other edits in between:

1. One main file under modules/core/src/main/scala (pick a small, already-formatted file).
2. One test file under modules/core/src/test/scala (pick a small, already-formatted file).

For each file:
1. Before: run `git diff --stat` and confirm the tree state for that file is clean (record the output).
2. Use the Edit tool to introduce exactly one deliberate formatting error (for example, extra spaces around an operator or a badly indented line). Do not change semantics.
3. Read the file and report whether the hook reformatted it back to the scalafmt style (the error is gone) or the error remains.
4. Run `git diff --stat` and report which files differ. Expected if the hook works: no other file changed (and this file shows no diff because the original was formatted).
5. Restore the exact original content (if the hook already restored it, confirm with `git diff --stat` that the file shows no diff; otherwise revert with Edit).
6. Final `git diff --stat` for that file must show no diff versus HEAD.

## Constraints

Local only. Edit only the two files named above, and only for the deliberate error and its revert. Do not run sbt. Read-only git only. No commit. Do not edit production behavior, CLAUDE.md, docs/agents.md or .claude/**.

## Report

Standard contract, plus per file: path, the exact edit made, whether the hook reformatted it, the `git diff --stat` output after the edit and after the revert, and the final state.
