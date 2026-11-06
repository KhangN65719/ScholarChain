# Phase 1 validation

The orchestrator reran the following checks on the final pre-ratification candidate. The aggregate command exited 0.

- `bash -n` and executable-bit check for each of the five `.claude/hooks/*.sh` files.
- `python3 -m json.tool` for `.claude/settings.json` and `.mcp.json`.
- `git diff --exit-code 2313f50 -- .claude/agents/factory-engineer.md` for the seed comparison.
- `.claude/hooks/validate-agent-files.sh` and `.claude/hooks/probe-policy.sh`.
- `git diff --check` for tracked paths and `git diff --no-index --check /dev/null <path>` for additions. The addition comparisons returned difference status 1 with no whitespace diagnostics.

## Output

```text
PASS: 18 unique agent names, explicit tools and read-only reviewer fence
PASS: 18 ownership rows and 18 unique lifecycle routes, with shared namespace exceptions
PASS: 5 workflow commands resolve to required owners
PASS: parsed 2 JSON files, checked 5 shell scripts, executable hooks and event wiring
PASS: echo 'terraform destroy' (exit 2)
PASS: terraform -chdir=infra/terraform apply (exit 2)
PASS: tofu destroy (exit 2)
PASS: git push --force-with-lease (exit 2)
PASS: git -C . push origin -f HEAD (exit 2)
PASS: git push origin +HEAD:main (exit 2)
PASS: aws s3 rm s3://dummy --recursive (exit 2)
PASS: aws s3 rb s3://dummy --force (exit 2)
PASS: aws dynamodb delete-table --table-name dummy (exit 2)
PASS: aws neptune delete-db-cluster (exit 2)
PASS: aws s3vectors delete-index (exit 2)
PASS: aws ecr delete-repository (exit 2)
PASS: aws kms schedule-key-deletion (exit 2)
PASS: aws emr terminate-clusters (exit 2)
PASS: aws emr add-instance-fleet (exit 2)
PASS: aws s3 ls && aws s3 rb s3://dummy --force (exit 2)
PASS: aws ec2 describe-regions --endpoint-url https://example.invalid (exit 2)
PASS: aws ec2 describe-regions --endpoint https://example.invalid (exit 2)
PASS: psql -c 'DROP TABLE dummy' (exit 2)
PASS: python3 -c 'open(chr(46)+"env").read()' (exit 2)
PASS: cat .env (exit 2)
PASS: cat .* (exit 2)
PASS: cat credentials.json (exit 2)
PASS: cat service-account.json (exit 2)
PASS: Read .env (exit 2)
PASS: Read project/secrets/key (exit 2)
PASS: Read credentials.json (exit 2)
PASS: Read service-account.json (exit 2)
PASS: Read private.pem (exit 2)
PASS: reviewer source edit (exit 2)
PASS: reviewer shell (exit 2)
PASS: service engineer build edit (exit 2)
PASS: service engineer constitutional edit (exit 2)
PASS: test engineer production edit (exit 2)
PASS: test engineer production shell edit (exit 2)
PASS: test engineer independent test edit (exit 0)
PASS: deploy engineer arbitrary shell execution (exit 2)
PASS: unsafe Git no-index comparison (exit 2)
PASS: Codex apply_patch constitution edit (exit 2)
PASS: factory constitutional edit (exit 0)
PASS: factory production edit (exit 2)
PASS: read-only Git status (exit 0)
PASS: infra metadata read (exit 0)
PASS: no source change at stop (exit 0)
PASS: formatter ignores non-Scala edits (exit 0)
PASS: missing marker blocks Scala stop (exit 2)
PASS: stale marker blocks Scala stop (exit 2)
PASS: fresh marker permits Scala stop (exit 0)
PASS: formatter detects Scala before Phase 2 build (exit 2)
PASS: Scala formatter invokes sbt fmt (exit 0)
PASS: Scala formatter flags collateral changes (exit 2)
PASS: existing deploy script still needs verified launcher (exit 2)
PASS: baseline seed comparison and whitespace checks
```

## Pending

The probe runner tested the scripts with simulated tool events, a temporary repository, and a mock formatter. The prohibited commands appeared only as payloads. The phase did not run an actual Scala build, contact MCP servers, or execute cloud changes.

The human decision and [fresh-session probes](restart-probes.md) are pending. Automatic Codex hook invocation and trust, runtime agent identity and tool fences, and effective secret isolation must be verified in the intended client. MCP authentication and effective IAM scope require separate verification before enabling connections. Actual `sbt fmt`, `sbt check`, and the test marker writer depend on Phase 2. Live deploy and rollback remain closed until the approved execution launcher exists.
