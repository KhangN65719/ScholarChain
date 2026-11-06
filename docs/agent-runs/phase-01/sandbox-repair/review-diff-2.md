# Re-review diff: the new changes only (orchestrator-produced)

Produced by the orchestrator on 2026-11-05 for the **re-review**, which is scoped to the changes made *after*
the first review verdict. `code-reviewer` has no Bash tool and cannot run git itself. Generated with the
read-only forms the P3 change permits, against the uncommitted working tree at base `751d926` on `main`.
Nothing is committed.

## How to isolate what is new

[`review-diff.md`](review-diff.md) is the **baseline**: it captures the change set exactly as the first review
saw it. This file captures the tree **now**. Anything present here and absent there is new and in scope;
anything identical in both was already reviewed and is **out of scope** for the re-review.

Two change sets landed in parallel, on disjoint files:

| Author | Files | Report |
|---|---|---|
| `factory-engineer` | `CLAUDE.md`, `.claude/hooks/policy.py`, `.claude/hooks/probe_policy.py` | [`review-repair-report.md`](review-repair-report.md) |
| `build-engineer` | `.jvmopts` (new), `target/sbt-tmp/.keep` (new) | [`tmpdir-experiment-report.md`](tmpdir-experiment-report.md) |

`.claude/settings.json` and `docs/agents.md` are **unchanged** since the first review — byte-identical to
`review-diff.md`. No re-review of those two is needed.

## Diffstat now

```
 .claude/hooks/policy.py       |  71 +++++++++++++--
 .claude/hooks/probe_policy.py | 203 +++++++++++++++++++++++++++++++++++++++++-
 .claude/settings.json         |   6 ++
 CLAUDE.md                     |   6 ++
 docs/agents.md                |   2 +-
 5 files changed, 280 insertions(+), 8 deletions(-)
```

Baseline was 238 insertions / 7 deletions across the same five files. The delta is +42 insertions / +1 deletion:
`policy.py` 68 → 71, `probe_policy.py` 163 → 203, `CLAUDE.md` 6 → 6 (two lines rewritten in place, no line
count change).

## Untracked, and therefore absent from `git diff`

`git status --short` reports:

```
 M .claude/hooks/policy.py
 M .claude/hooks/probe_policy.py
 M .claude/settings.json
 M CLAUDE.md
 M docs/agents.md
?? .jvmopts
?? docs/agent-runs/phase-01/sandbox-repair/
```

**`.jvmopts` is new and untracked, so no diff hunk exists for it.** Its complete contents, read directly, are
one line plus a trailing newline:

```
-Djava.io.tmpdir=/Users/winnguyen/ScholarChain/target/sbt-tmp
```

`target/sbt-tmp/.keep` is also new. It does not appear even as untracked because `target/` is gitignored at
`.gitignore:1`. It is an empty marker file whose purpose was to create the parent directory, since `mkdir` is
refused to the role.

`docs/agent-runs/phase-01/sandbox-repair/` is orchestrator-owned evidence, not part of the change set.

## `.claude/hooks/policy.py` — current full diff

The F2 comment rewrite (lines 54-56 of the new file) and the F5 `is True` tightening are the only changes
since the baseline. `REVIEW_GIT`'s contents and `review_git`'s accept condition are byte-identical to the
baseline — verify that rather than taking it on trust, since `factory-engineer` reports having temporarily
stubbed `review_git` during its probe-discrimination check and then reverted it.

```diff
diff --git a/.claude/hooks/policy.py b/.claude/hooks/policy.py
index aa67883..8a61a2a 100644
--- a/.claude/hooks/policy.py
+++ b/.claude/hooks/policy.py
@@ -53,6 +53,10 @@ def checkout_path(value):
 
 
 READ_GIT = {"status", "diff", "log", "show", "ls-files", "rev-parse", "branch"}
+# Exactly the read-only review forms that may name a constitutional path. review_git() tries every
+# form and accepts if any one matches, so this table's order carries no meaning: do not introduce a
+# break-on-first-prefix-match without also making the order explicit and tested.
+REVIEW_GIT = (("diff", "--stat"), ("log", "--oneline"), ("show", "--stat"), ("diff",))
 CHECK_SBT = {"check", "Test/compile"}
 SCRIPT_ROOTS = {
     "dataset-engineer": ("datasets/scripts/",),
@@ -60,6 +64,23 @@ SCRIPT_ROOTS = {
 }
 
 
+def review_git(tokens):
+    """Match exactly `git diff`, `git diff --stat`, `git log --oneline` and `git show --stat`,
+    with revision ranges and pathspecs. Every remaining argument must be a revision, a pathspec or
+    the `--` separator, so `--output`, `--ext-diff`, `--textconv`, `--exec-path` and `--no-index`
+    stay refused and no other option can ride along. Callers apply this only after the sensitive-path
+    and destructive-operation scans, which therefore keep their effect on these forms."""
+    if not tokens or tokens[0] != "git":
+        return False
+    arguments = tokens[1:]
+    for form in REVIEW_GIT:
+        if tuple(arguments[: len(form)]) != form:
+            continue
+        if all(item == "--" or not item.startswith("-") for item in arguments[len(form):]):
+            return True
+    return False
+
+
 def guard_shell(command, cwd, agent):
     # Normalize quoted words for direct policy probes without executing input.
     if re.search(r"[$`*?\[\]{}\n\r<>;|&]", command):
@@ -87,6 +108,11 @@ def guard_shell(command, cwd, agent):
             or not re.fullmatch(r"(?:describe|list)-[a-z-]+|get-caller-identity|ls", tokens[2])
         ):
             return deny("Direct AWS shell calls are limited to metadata inspection. Route mutations through the approved human workflow.")
+    # Read-only review of constitutional files. Reviews need to read the diff of the very files the
+    # token scan below protects, so this allowance precedes it. It grants read only: no write, no
+    # checkout, no branch change. The scans above still apply, so a diff cannot name a secret path.
+    if review_git(tokens):
+        return 0
     if any(
         CONSTITUTION.search(value) or CONSTITUTION.search(checkout_path(resolved_path(value, cwd)) or "")
         for value in tokens
@@ -228,22 +254,55 @@ def changed_at(path):
     return target.stat().st_mtime_ns
 
 
-def verify_tests():
+def verify_tests(payload=None):
     source = [path for path in changed_paths() if source_path(path)]
     if not source:
         return 0
     if TEST_MARKER.is_symlink() or not TEST_MARKER.is_file():
-        return deny("Changed sources require a fresh passing sbt check. The test marker is missing.")
-    if TEST_MARKER.stat().st_mtime_ns <= max(changed_at(path) for path in source):
-        return deny("Changed sources are newer than the test marker. Run sbt check after the final edit.")
-    return 0
+        message = "Changed sources require a fresh passing sbt check. The test marker is missing."
+    elif TEST_MARKER.stat().st_mtime_ns <= max(changed_at(path) for path in source):
+        message = "Changed sources are newer than the test marker. Run sbt check after the final edit."
+    else:
+        return 0
+    # Trade-off, loop termination against gate strength. Blocking again after Claude Code has already
+    # re-run this hook for the same stop produces an unsatisfiable loop, which Claude Code eventually
+    # overrides anyway, so the stop is allowed on the second pass. The gate is NOT satisfied: report it
+    # as a non-blocking error (exit 1, stderr shown to the operator) instead of returning a silent 0,
+    # so a stale marker is visible and attributable rather than inferred to be success. No state is
+    # written: the test marker stays build-generated and guard() keeps refusing agent writes to it.
+    # Identity, not truthiness: the contract is a boolean, so "false", 1 and other truthy non-booleans
+    # keep the gate fully enforced rather than opening the one path that deliberately weakens it.
+    if (payload or {}).get("stop_hook_active") is True:
+        print(
+            "ScholarChain policy: UNVERIFIED STOP. The test gate was not satisfied, and this hook "
+            f"already ran for this stop, so it is not blocking again. {message} Operator action: treat "
+            ".claude/.last-test-run as stale, run sbt check over the changed sources listed below, and "
+            "record the result before this work is reviewed, committed or ratified. Changed sources: "
+            f"{', '.join(sorted(path.as_posix() for path in source))}.",
+            file=sys.stderr,
+        )
+        return 1
+    return deny(message)
+
+
+def stop_payload():
+    """Stop and SubagentStop deliver a JSON payload carrying stop_hook_active. An absent payload keeps
+    the gate fully enforced, which is the closed direction; non-empty malformed input raises so that
+    main() fails closed. isatty keeps a direct terminal invocation from blocking on stdin."""
+    raw = "" if sys.stdin.isatty() else sys.stdin.read()
+    if not raw.strip():
+        return {}
+    payload = json.loads(raw)
+    if not isinstance(payload, dict):
+        raise ValueError("A stop payload must be a JSON object.")
+    return payload
 
 
 def main():
     try:
         action = sys.argv[1]
         if action == "tests":
-            return verify_tests()
+            return verify_tests(stop_payload())
         payload = json.load(sys.stdin)
         return {"guard": guard, "format": format_scala}[action](payload)
     except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.CalledProcessError):
```

## `.claude/hooks/probe_policy.py` and `CLAUDE.md`

These changed substantially (+40 diff lines on the probe file, two paragraphs rewritten in place in
`CLAUDE.md`). **Read the current files directly** — they are in the reviewer's read scope, and the baseline in
`review-diff.md` gives the before-state for comparison. `factory-engineer`'s report
([`review-repair-report.md`](review-repair-report.md)) states the exact line ranges it changed and the
before/after text of each documentation edit, which is the fastest route to the delta; verify it against the
files rather than taking it on trust.
