# WO-A8 report (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE with one open item. WO-A6 had not landed when this was written.
FILES MODIFIED: `README.md:9`, the F07 bullet only. It now reads: `.mcp.json:7` sets `Authorization` to `${SCHOLARCHAIN_GITHUB_AUTH_HEADER}`; the variable holds the full header value `Bearer <token>`, set outside the repository; the name is recorded in the `CLAUDE.md` MCP trust table GitHub row (line 37). The bullet ends with a planned-state sentence saying the line numbers are to be checked once the changes land. The old explanation of the `Bearer ` prefix was removed.
VALIDATION: `.mcp.json:7` and `CLAUDE.md:37` were still unchanged when read. Other mentions of the old variable name were not searched (WO-A6 reports them).
OPEN: after WO-A6 lands, check the two citations and delete the planned-state sentence (follow-up WO-A8b).
