# Repair 06: review A9 F1 and F2 (documentation-engineer), as received 2026-11-19 (condensed by the orchestrator)

STATUS: COMPLETE.
- **F1:** `README.md`: the newline is restored, so the F07 bullet ends line 9 and the K02 bullet starts line 10. Nothing else changed. The agent confirms its own A8b edit caused the join.
- **F2:** `docs/threat-model.md:61`. The claim column now reads: `.mcp.json` sets `Authorization` to the reference `"${SCHOLARCHAIN_GITHUB_AUTH_HEADER}"`, not a value; the variable holds `Bearer <token>` and is set outside the repository; MCP servers stay disabled until approved. The evidence column adds `CLAUDE.md:37`. The limits column is unchanged.
VALIDATION: both files were read before and after; `GITHUB_READ_TOKEN` no longer appears in either.
