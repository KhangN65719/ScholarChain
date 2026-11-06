# Phase 1 review and repair history

All constitutional repairs were routed to the factory-engineer. The independent reviewer made no edits.

| Finding | Repair |
|---|---|
| Documentation and dependency roles both claimed `docs/dependencies/`. | Explicitly exclude the subtree from documentation ownership in the matrix and role definition. |
| Deployment and incident procedures listed execution before all prerequisite checks. | Verify approvals, environment, bounds, and evidence before execution. |
| Shell command chains, globs, interpreters, and Git directory comparisons could bypass direct checks. | Restrict ordinary shell input and reject those paths. Add synthetic rejection probes. |
| A known deploy role and script directory alone could unlock execution. | Fail closed for deployment and rollback until the verified human-approved launcher is supplied. |
| AWS inspection arguments could override the identity or endpoint. | Limit direct AWS inspection to exact three-token commands, with no override flags. Add full and abbreviated endpoint probes. |
| Ownership audit accepted required substrings and could miss an extra territory. | Compare exact ownership assignments and role scopes, with an explicit exception for the original seed format. |
| The AWS MCP local-file wording overstated the server setting. | Document direct path-argument rejection and require separate process isolation and effective IAM verification. |

See [validation.md](validation.md) for the rerun and [review.md](review.md) for the independent verdict. Native runtime gates are tracked separately.
