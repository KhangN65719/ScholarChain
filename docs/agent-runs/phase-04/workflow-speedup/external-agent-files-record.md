# Record: `.codex/`, `.agents/` and `AGENTS.md` were never committed

- Date: 2026-11-06 · Phase 4 · Author: orchestrator (main session) · Branch `main` at `6d89e3d`
- Reason for the record: the human moved these paths out of the repository working tree and asked that their
  commit status be recorded as evidence.

## Verification performed

Two read-only Git commands, run by the orchestrator in this session:

| Command | Output |
|---|---|
| `git log --all --oneline -- .codex .agents AGENTS.md` | no output |
| `git status --porcelain` | `?? docs/agent-runs/.DS_Store` only |

## Findings

1. **Never committed.** `git log --all` over all refs, with those three pathspecs, returns no commit. No commit
   on any branch or tag reachable in this clone ever added, modified or deleted `.codex/`, `.agents/` or
   `AGENTS.md`. There is therefore nothing to remove from history and no `git rm` is required.
2. **Absent from the working tree and not staged.** `git status --porcelain` lists only an untracked
   `docs/agent-runs/.DS_Store`. None of the three paths appears as tracked, staged, modified or untracked,
   which is consistent with the human's statement that they were moved out of the repository.
3. **No ScholarChain control plane depended on them.** These are other tools' agent-configuration conventions.
   ScholarChain's authority surface is `CLAUDE.md`, `docs/agents.md`, `.claude/**` and `.mcp.json`, all owned
   by `factory-engineer`. Their removal moves no ownership boundary and is not a constitutional change.

## Scope limits

- Verified in this clone only. If the paths were ever pushed from a different clone and that history is not
  fetched here, this check would not see it. No remote-side verification was performed.
- `.DS_Store` noise under `docs/agent-runs/` is unrelated and is left untouched; it is not a constitutional
  path and no gitignore change is proposed here.
