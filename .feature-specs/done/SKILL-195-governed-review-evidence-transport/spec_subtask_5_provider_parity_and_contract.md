# SKILL-195 Subtask 5 — Provider parity, contract bump, and stopgap removal

## Scope

Bring the remaining MCP-speaking providers onto the governed transport, version the accounting
contract that now carries meaningful evidence counters, and retire the interim toolset grant.

`McpRegistrationOperations` already models per-provider MCP config shape — JSON for Claude, Copilot,
Junie, and Cursor; TOML for Codex
(`runtime-infra-fs/.../launcher/mcp/McpRegistrationOperations.kt:47-54`). That per-provider
knowledge is the template for per-launch governed config.

Deliver:

- Per-launch governed MCP config and tool restriction for Codex, Cursor, and Junie on the same terms
  subtask 4 established for Claude, using each provider's own config format and isolation flag.
- A provider that cannot express governed-only tooling or MCP isolation fails loudly at launch with
  a typed error naming the provider and the missing capability. It must not fall back to an
  ungoverned launch.
- Removal of `REVIEW_INLINE_TOOLS` and the `--agent` restoration's raw-filesystem grant from
  `6f47e771a`, now superseded on every governed path.
- `orchestration/contracts/review-context-schema.yaml` version bump for accounting whose
  `evidence_bytes` / `expansions` / `tool_calls` are now boundary-fed rather than structurally zero,
  followed by the Kotlin `*_CONTRACT_VERSION` constant and its parity test, per `AGENTS.md`.
- Legacy accounting records at the prior version quarantine and regenerate in-band rather than
  failing a read.

## Acceptance Criteria

1. Codex, Cursor, and Junie governed review launches carry per-launch governed MCP config and a tool
   list naming only governed operations.
2. A provider lacking governed-only tooling or MCP isolation fails at launch with a typed error
   naming the provider and the capability; no ungoverned fallback exists on any path.
3. `REVIEW_INLINE_TOOLS` is gone; no governed review launch grants `Read`, `Grep`, `Glob`, or `Bash`.
4. The review-context schema version is bumped in `orchestration/contracts/` first, then in the
   Kotlin constant, with a parity test in the `PlatformPackSchemaContractVersionTest` pattern.
5. A pre-bump accounting record quarantines and regenerates in-band; it does not fail the run.
6. Cursor's governed launch keeps its existing `--workspace` scoping and its native worker routing.
7. `(cd runtime-kotlin && ./gradlew check)`, `skill-bill validate`, and
   `scripts/validate_agent_configs` pass.

## Non-Goals

- Supporting agents that do not speak MCP. Those fail loudly rather than degrade.
- Changing agent support tiers or advertising untested agents as verified. Claude and Codex remain
  the e2e-verified tiers; Cursor and Junie parity here is code parity, not a support-tier claim.
- Revisiting the review-context schema beyond the accounting fields this program changes.

## Dependency Notes

Depends on subtask 4. The Claude transport is the reference implementation every other provider
mirrors; parity before it exists would be speculative.

## Validation Strategy

- A Codex governed launch command carries governed-only tooling and its TOML MCP config; a Cursor
  launch carries its JSON equivalent and retains `--workspace`.
- A provider stripped of governed-tooling capability fails with a typed error rather than launching
  ungoverned. This is the test that keeps the boundary from quietly regressing to today's state.
- A pre-bump accounting record reads successfully after regeneration.
- No governed launch command on any provider contains a raw filesystem tool.

One test per rule; do not mirror the same assertion once per provider where a single
parameterized boundary assertion covers the contract. Then the three validation commands above.

## Next Path

```bash
skill-bill goal SKILL-195
```
