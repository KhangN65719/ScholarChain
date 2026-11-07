# Documentation evidence: excluding specific commands from the Claude Code Bash sandbox

> **Archival note (orchestrator).** Gathered 2026-11-06 by the orchestrator before dispatching the Phase 4
> workflow-speedup work order, because `factory-engineer` has no web-fetch tool and the human required the
> setting be verified in the documentation first. The report is preserved as received from the
> `claude-code-guide` subagent, which read the pages listed at the end. Items the author marked as **inferred**
> are not documented facts and are labelled as such below. This is upstream-documentation evidence only; it is
> **not** a measurement of this repository's behavior.

## Sources read

- https://code.claude.com/docs/en/sandboxing.md
- https://code.claude.com/docs/en/permissions.md
- https://code.claude.com/docs/en/settings.md
- https://code.claude.com/docs/en/settings-reference.md
- https://code.claude.com/docs/en/hooks.md

## 1. The setting

The mechanism is `sandbox.excludedCommands`, an array of strings under the `sandbox` key. There is **no**
permissions-based way to exclude a command from the sandbox; permission rules only decide approval.

Quoted from the sandboxing page: "List a command pattern in `sandbox.excludedCommands` to run matching
commands outside the sandbox, which means no filesystem restrictions and no network proxy."

Its documented example:

```json
{
  "sandbox": {
    "enabled": true,
    "excludedCommands": ["docker compose *"]
  }
}
```

The settings-reference index describes the key as "Name commands Claude Code can run outside the sandbox",
scope "Any file". The author could not read the per-key entry, so the declared type and default there are
**unconfirmed**.

Related keys: `sandbox.autoAllowBashIfSandboxed` (default `true`, "Run sandboxed commands without a permission
prompt") and `sandbox.allowUnsandboxedCommands` (controls the retry escape hatch). This session has
`dangerouslyDisableSandbox` disabled, so the retry path is unavailable regardless.

## 2. Pattern matching

- Patterns use the same syntax as a `Bash(...)` permission rule, **not** regex.
- A pattern with no `*` is an **exact match**. `sbt check` matches only the text `sbt check`, not
  `sbt check --foo`.
- `*` matches any text. A trailing ` *` (with a space) also matches the bare command, so `sbt check *` would
  match `sbt check` plus any arguments.
- In a compound command, **every** subcommand must match an entry. The docs' own example: `npm ci && docker
  compose build` stays sandboxed unless another entry also covers `npm ci`. Therefore
  `sbt check && rm -rf x` stays sandboxed.
- A redirect, a `cd`, or `$(...)` keeps the whole call sandboxed.
- Matching is on the call text, so a script or `make` target that invokes `sbt` internally does not match.
- The `:*` suffix is documented for permission rules only. The sandbox page says only "same syntax", so the
  author advises against relying on `:*` here.

Consequence for this work order: `sbt check` and `sbt Test/compile` as exact entries match those two commands
and nothing else. `sbt anything-else` does not match.

The exact snippet for exactly those two commands:

```json
{
  "sandbox": {
    "excludedCommands": ["sbt check", "sbt Test/compile"]
  }
}
```

It must be merged into the existing `sandbox` object without dropping `enabled` or any other existing key.
Each invocation variant (`sbt -batch check`, `sbt "check"`) would need its own entry.

## 3. The `/sandbox` command

`/sandbox` exists and opens a panel with **Mode**, **Overrides** and **Config** tabs. The docs say that saving
a Mode choice writes to `.claude/settings.local.json` for the current project. They do **not** say whether
`excludedCommands` can be written from the panel, so the author recommends editing the file by hand. `/sandbox`
fails if a higher-priority level (managed settings, or `--settings`) sets the same keys.

## 4. Hooks and permissions still apply

Quoted from the permissions page: "An excluded command goes through the regular permission flow: Read-only
commands and commands your allow rules cover run without a prompt. In auto mode, the classifier reviews other
excluded commands. In `bypassPermissions` mode, an excluded command runs without a prompt unless an ask rule
matches it." Excluded commands "respect the bare `Bash` ask rule as usual."

The hooks page says PreToolUse hooks "run before the permission prompt, for every tool except
`EndConversation`." No page states that a sandbox exclusion skips hooks.

- **Inferred, not documented:** that PreToolUse therefore still runs for an excluded `sbt check`.
- **Inferred, not documented:** that because `autoAllowBashIfSandboxed` applies to *sandboxed* commands, an
  excluded `sbt check` may prompt in default mode unless `Bash(sbt check)` is in `permissions.allow`.
- Documented: a hook's `"allow"` does not override a matching `deny` or `ask` rule.

## 5. Does it need a restart

**Not confirmed for `excludedCommands`.** The settings page says Claude Code reloads settings files on change,
"including edits to `permissions`, `hooks`, and credential helpers", and that some keys are read only at
session start. The sandboxing page says filesystem list changes apply to the running session, and that a change
to `autoAllowBashIfSandboxed` takes effect at the next shell command. No page says either for
`excludedCommands`. Treat a restart as possibly required and record what was observed.

## 6. Settings scopes

The settings-reference index lists all four sandbox keys with scope "Any file", so they are valid in user,
project, local and managed settings. Two qualifications:

- Under an **admin-required** sandbox, Claude Code ignores `excludedCommands` entries in
  `.claude/settings.json` and `.claude/settings.local.json`.
- Lists from non-managed scopes merge, so entries from several files combine. **Applying that merge behavior
  to this specific key is the author's inference.**

## 7. ScholarChain-specific notes from the author

- This session's sandbox configuration write-denies `/Users/winnguyen/ScholarChain/.claude/settings.json` and
  `.claude/settings.local.json`, so the author expects an agent cannot make this edit and the human must.
  **Orchestrator note:** this is the author's inference from the session's sandbox configuration, and it is in
  tension with Phase 1, where a sandbox write allowlist was in fact authored in that file. The work order
  therefore instructs `factory-engineer` to attempt the edit and report `BLOCKED-ON` human with the verbatim
  denial if the runtime refuses, rather than working around it through the shell.
- `.claude/settings.json` is `factory-engineer`-owned and changes to it need human ratification.
- The existing documented route stays valid: the sandboxing page says commands typed at the `!` shell-mode
  prompt "keep running outside the sandbox", so the human's `!`-prefixed `sbt check` needs no config change.
