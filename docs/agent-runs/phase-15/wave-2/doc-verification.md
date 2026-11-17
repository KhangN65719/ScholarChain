# Wave 2 documentation checks (orchestrator, aws-documentation MCP, 2026-11-17)

| Claim | Result | Source |
|---|---|---|
| openCypher queries need `neptune-db:ReadDataViaQuery` (reads) on an IAM-auth cluster | VERIFIED: `ExecuteOpenCypherQuery` requires one of `neptune-db:ReadDataViaQuery`, `WriteDataViaQuery`, `DeleteDataViaQuery` depending on the query (same pattern as the Gremlin action quoted on the page); the search index lists `neptune-db:QueryLanguage:OpenCypher` for `ExecuteOpenCypherQuery` | https://docs.aws.amazon.com/neptune/latest/userguide/data-api-dp-queries.html |
| `neptune-db:QueryLanguage` condition key | VERIFIED: the only Neptune service-specific data-access key; String; valid values `Gremlin`, `OpenCypher`, `Sparql`; supported actions `ReadDataViaQuery`, `WriteDataViaQuery`, `DeleteDataViaQuery`, `GetQueryStatus`, `CancelQuery` | https://docs.aws.amazon.com/neptune/latest/userguide/iam-data-condition-keys.html |

Consequence for WO-10: the runtime role's Neptune read statement can add `"StringEquals": {"neptune-db:QueryLanguage": "OpenCypher"}` on `ReadDataViaQuery` and `GetQueryStatus`. `GetEngineStatus` is not in the key's supported-action list, so it must stay in a statement without that condition. Routed to infra-engineer with the WO-10 repair items.
