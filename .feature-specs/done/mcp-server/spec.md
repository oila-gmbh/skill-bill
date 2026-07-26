# Feature: mcp-server
Created: 2026-04-05
Status: In Progress
Sources: conversation context

## Acceptance Criteria
1. skill_bill/mcp_server.py exposes 5 MCP tools: import_review, triage_findings, resolve_learnings, review_stats, doctor
2. Tools reuse existing skill_bill.* module functions — no logic duplication
3. Each tool returns structured dict responses (FastMCP serializes to JSON)
4. Tools that mutate state (import_review, triage_findings) call auto_sync_telemetry
5. .mcp.json registers the server for Claude Code auto-discovery (stdio transport)
6. pyproject.toml adds mcp dependency and skill-bill-mcp entry point
7. install.sh registers MCP server for Claude agent after CLI install
8. SKILL.md Auto-Import/Auto-Triage prefer MCP tools, fall back to CLI
9. Existing CLI commands and tests continue to work unchanged
10. MCP server starts without error via python3 -m skill_bill.mcp_server
