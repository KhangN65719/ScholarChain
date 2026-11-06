> **Orchestrator note (2026-11-05):** The human architect supplied the diff evidence, because the orchestrator cannot run git in this session. The guard hook refused `git log` and `git diff` with "This shell command is outside the role's permitted commands." The human ran `git diff --stat 9ee8b22..8dbd141` and `git diff 9ee8b22..8dbd141 -- CLAUDE.md docs/agents.md .claude .mcp.json` with the `!` prefix. The orchestrator passed both diffs to `code-reviewer`, with unchanged context lines left out and hunk headers kept. The orchestrator archived the verdict below without editing it. Author: `code-reviewer` subagent, fresh Claude Code session.

---

# Independent review: Phase 1 orchestrator repair, range `9ee8b22..8dbd141`

**Verdict: REQUEST CHANGES**

- **Status:** COMPLETE (review delivered)
- **Owner:** code-reviewer
- **Work order:** Phase 1 orchestrator repair, independent review of `9ee8b22..8dbd141`
- **Files created or modified:** none
- **Commands run:** none. I have no shell. I used only Read, Grep and Glob on the current tree.
- **Evidence used:** the `git diff --stat` and constitutional `git diff` the human supplied, the orchestrator's session observation, and the current tree.

## Answers to the review questions

1. **Ownership rows, routes, tool lists, permission rules, deny rules, hook logic.** None changed, based on the supplied diff. The only hook-directory change is a renamed label at `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py:57`. `policy.py`, `settings.json` and `.mcp.json` do not appear in the diff stat. In `/Users/winnguyen/ScholarChain/.claude/agents/factory-engineer.md:4` the tool list is unchanged (`Read, Grep, Glob, Write, Edit, Bash`). Confirmed.
2. **Codex wording.** A case-insensitive search for `codex` across `CLAUDE.md`, `docs/agents.md`, `.claude/**` and `.mcp.json` returned no matches. Confirmed. One Codex-only tool code path remains in the hooks (Finding 5). That is code, not orchestration wording.
3. **Wording defects.** See Findings 1 to 4.
4. **README accuracy.** The "unchanged" claims match the diff. The change table leaves out four added evidence files (Finding 6).

## Findings

### 1. Medium: enforcement is stated as fact before the evidence exists
- **Location:** `/Users/winnguyen/ScholarChain/CLAUDE.md:47`
- **Evidence:**
  - The line says: "Claude Code runs the hooks natively from `.claude/settings.json` and enforces each subagent's declared tools." It states this as a present fact.
  - The next sentence says: "do not report automatic enforcement from the existence of these files alone."
  - `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/orchestrator-update/README.md:3,28` says native enforcement is pending fresh-session probes.
  - The only observed evidence is PreToolUse/Bash interception in the main session. Nothing has been observed yet for the Stop hook, the Read deny rules, subagent tool enforcement, or whether `agent_type` reaches the hook (restart probes 3, 4, 5 and 7 are still open).
- **Requested change:** describe this as configured behavior that still has to be verified by `restart-probes.md`. Example: "`.claude/settings.json` configures the hooks to run natively, and subagent tool lists are meant to be enforced; record each as verified only after a fresh-session probe observes it."
- **Owner:** factory-engineer

### 2. Medium: the reviewer read-only claim depends on an unverified hook input
- **Location:** `/Users/winnguyen/ScholarChain/docs/agents.md:75`
- **Evidence:**
  - The line says: "Reviewers stay read-only through their tool lists and the guard hook."
  - The guard's reviewer checks only fire when the payload carries `agent_type == "code-reviewer"` (`/Users/winnguyen/ScholarChain/.claude/hooks/policy.py:140` and `:164`).
  - Nobody has observed whether Claude Code includes `agent_type` for subagent tool calls. Probe 7 in `restart-probes.md:13` is "Not run".
  - The text this replaced told the orchestrator to "mark the tool fence pending". The new text drops that condition.
- **Requested change:** keep the claim conditional. The tool-list fence and the guard fence stay pending until probes 2 and 7 pass.
- **Owner:** factory-engineer

### 3. Medium: nothing enforces "never performs a specialist's work inline", and no open gate records that
- **Location:** `/Users/winnguyen/ScholarChain/CLAUDE.md:23`
- **Evidence:**
  - For the main session, events carry no `agent_type`. With no agent type, `policy.py:157-171` only denies edits to constitutional files, the test marker, `.git` and sensitive paths.
  - So a main-session Write to, for example, `modules/core/**` passes the guard (`policy.py:172` returns 0).
  - The new rule therefore depends on the model behaving correctly. `docs/agents.md:75` says "If the runtime does not enforce a particular tool or path boundary, record it as open." The change record does not record this one.
- **Requested change:**
  - Add this gap to the remaining gates or the open-gate list.
  - Optionally add a restart probe: ask the main session to edit a disposable module file directly, and expect it to decline and delegate.
- **Owners:** factory-engineer for the constitutional and probe wording. The orchestrator archives it in `orchestrator-update/`.

### 4. Low: allowing `/clear` per phase can skip the restart that activation needs
- **Location:** `/Users/winnguyen/ScholarChain/CLAUDE.md:23`, compared with `CLAUDE.md:24` and `/Users/winnguyen/ScholarChain/docs/agents.md:67`
- **Evidence:**
  - Line 23 allows "one fresh Claude Code session, or `/clear`, per phase".
  - Line 24 and the approval table require restarting, or a fresh orchestrator session, before constitutional changes take effect.
  - `/clear` resets the conversation but does not restart the process. Settings and hook configuration loaded at startup may not be reloaded. I could not verify this from the repo, so it is pending.
  - A phase that follows a ratified constitutional change could start on stale controls.
- **Requested change:** limit `/clear` to phases where no constitutional change was ratified since the last restart.
- **Owner:** factory-engineer

### 5. Low (informational): a Codex-only tool path remains in the guard and probes
- **Location:** `/Users/winnguyen/ScholarChain/.claude/hooks/policy.py:13` (`"apply_patch"` in `WRITES`), `policy.py:38-40`, and `/Users/winnguyen/ScholarChain/.claude/hooks/probe_policy.py:57`
- **Evidence:**
  - `apply_patch` is a Codex tool. Claude Code exposes no tool by that name.
  - The PreToolUse matcher in `/Users/winnguyen/ScholarChain/.claude/settings.json:122` does not include it, so this path never runs natively.
  - Removing "Codex" from the probe label hides where the path came from.
  - It is harmless as extra protection, but the probe count overstates what native enforcement actually covers.
- **Requested change:** keep the path as an explicitly labeled legacy or defensive probe, or remove it. Do not count it as evidence of native enforcement. This does not block approval.
- **Owner:** factory-engineer

### 6. Low: the README change table is incomplete and one authority entry is unclear
- **Location:** `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/orchestrator-update/README.md:11-16`
- **Evidence:**
  - The diff stat lists 8 files. The table lists 4. Missing:
    - `README.md` itself
    - `orchestrator-update/restart-probes.md`
    - `docs/agent-runs/phase-02/revalidation.md`
    - `docs/agent-runs/phase-03/revalidation.md`
  - Those are evidence files, so the table can arguably cover constitutional files only. But the README does not say so, and it does not name the author of these records. `docs/agents.md` boundary rules say: "Preserve each report as received with its author identified."
  - The `factory-engineer.md` row gives its authority as "Phase 0 seed (human architect)". But `docs/agents.md` assigns `.claude/agents/**` to factory-engineer, and the other rows say "factory-engineer territory" without naming who actually wrote them.
- **Requested change:**
  - Say that the table covers constitutional files only, or list all 8 files.
  - Name the author of each change and evidence file.
  - Have the human confirm the Phase 0 authorship of `factory-engineer.md` in `ratification.md`.
- **Owners:** the orchestrator for the archive record, and the human for confirming attribution.

### 7. Informational: the ratification draft exists before the review
- **Location:** `/Users/winnguyen/ScholarChain/docs/agent-runs/phase-01/orchestrator-update/ratification.md` (untracked)
- **Evidence:** the file is a blank DRAFT template and contains no decision, so the order of gates is not broken. Recorded so the human fills it in only after reading this review and any `repairs.md`.
- **Owner:** human architect

## Confirmed without defects
- The MCP wording at `CLAUDE.md:37` matches `settings.json:2` (`enableAllProjectMcpServers: false`). It does not widen authority.
- The `agent_type` wording at `CLAUDE.md:47` matches `policy.py:158`: a missing agent type is denied a constitutional edit.
- The new `factory-engineer.md:9` text ("tools field is the maximum authority ... may narrow it further") is consistent with `docs/agents.md:75,77`. It does not widen authority.
- All constitutional edits in the range are inside factory-engineer territory, apart from the attribution question in Finding 6.

## Pending checks (not verified in this review)

| # | Check | Owner |
|---|---|---|
| 1 | Re-run `python3 -m json.tool` on `.claude/settings.json` and `.mcp.json` | factory-engineer |
| 2 | Re-run `bash -n` on the five `.sh` hooks and `py_compile` on `policy.py`, `validate.py`, `probe_policy.py` | factory-engineer |
| 3 | Re-run `.claude/hooks/validate-agent-files.sh` (expect 18/18/18/5, exit 0) | factory-engineer |
| 4 | Re-run `.claude/hooks/probe-policy.sh` (expect exit 0) | factory-engineer |
| 5 | Restart probes 1 to 7 in a fresh Claude Code session after ratification, especially 3 (`terraform destroy`), 4 (`.env`), 5 (Stop hook) and 7 (`agent_type` reaches the hook) | human / orchestrator; results archived by the orchestrator |
| 6 | Confirm whether `/clear` reloads settings and hooks (Finding 4) | human / orchestrator, observed during restart probes |
| 7 | Confirm the full `git diff 9ee8b22..8dbd141` contains no other changed lines. I relied on the orchestrator's statement that context lines were elided. | human |

## Next gate
Send Findings 1 to 5 to factory-engineer, record them in `repairs.md`, then run an independent re-review of the repair commits. After that, the human ratifies in `ratification.md`, and the restart probes follow.
