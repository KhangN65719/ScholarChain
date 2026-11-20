# WO-A6 report (factory-engineer), as received 2026-11-19 (condensed by the orchestrator; diff kept)

STATUS: DRAFT for review and ratification.
FILES MODIFIED: `.claude/hooks/guard-dangerous.sh`, `.mcp.json`, `CLAUDE.md` (the GitHub row only, line 37). `validate.py` is unchanged; it checks neither the header nor the variable name (the whole file was read).

## Diff
- **`guard-dangerous.sh`:**
  - Below the existing header, a four-line comment note: the array decides nothing, and `policy.py` stays the enforcer.
  - After `set -euo pipefail`, `CATEGORY_EXAMPLES=( ... )` with 18 `$'<id>\t<example>'` entries:
    - aws-delete-terminate: `aws ec2 terminate-instances i-0abc`, `aws ecr delete-repository example-placeholder`
    - terraform: `terraform apply`, `terraform -chdir=x destroy`, `tofu destroy`
    - git: `git push --force`, `git push origin --delete x`, `git reset --hard`
    - database: `DROP TABLE x`, `DELETE FROM x`
    - s3: `aws s3 rm s3://example-placeholder`, `aws s3 rb s3://example-placeholder`
    - neptune: `aws neptune delete-db-cluster x`, `aws neptunedata cancel-query x`
    - dynamodb: `aws dynamodb delete-table x`, `aws dynamodb delete-item x`
    - vector-index: `aws s3vectors delete-index x`, `aws s3vectors delete-vector-bucket x`
  - Then `readonly CATEGORY_EXAMPLES`, and `if [ "$#" -eq 1 ] && [ "$1" = "--list-categories" ]; then printf '%s\n' "${CATEGORY_EXAMPLES[@]}"; exit 0; fi`.
  - The python3 check and `exec python3 -B "$(dirname "$0")/policy.py" guard` are unchanged.
- **`.mcp.json:7`:** `"Authorization": "Bearer ${SCHOLARCHAIN_GITHUB_READ_TOKEN}"` becomes `"Authorization": "${SCHOLARCHAIN_GITHUB_AUTH_HEADER}"`.
- **`CLAUDE.md:37`:** the row now names `SCHOLARCHAIN_GITHUB_AUTH_HEADER`, adding: "It holds the full header value `Bearer <fine-grained read-only token>`, and `.mcp.json` passes it as the `Authorization` header unchanged." The rest of the row is unchanged.

## Not run (refused by the guard for this role)
`bash -n`, `--list-categories`, and grep for the old variable name.

## Assumptions and risks
- Every example is refused by `policy.py` for test-engineer. Not executed; check `git push --force`, `DROP TABLE x` and `DELETE FROM x` as bare commands, and `aws neptunedata cancel-query x`.
- `$'...'` and a readonly array work on macOS bash 3.2.
- The GitHub MCP server now needs `SCHOLARCHAIN_GITHUB_AUTH_HEADER=Bearer <token>`; the server stays disabled until approved.

## Manual action
1. `bash -n`
2. `validate-agent-files.sh`
3. `--list-categories` (18 lines, exit 0)
4. grep for `SCHOLARCHAIN_GITHUB_READ_TOKEN`, routing the hits
5. `git diff --stat`

## Restart probes
- P1: refused through the hook.
- P1b: a JSON-on-stdin refusal still exits 2.
