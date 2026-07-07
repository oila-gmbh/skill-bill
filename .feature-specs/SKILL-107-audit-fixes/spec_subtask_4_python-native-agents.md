# SKILL-107 Subtask 4: Python Code-Review Native Agents

**Parent:** [spec.md](spec.md)
**Depends on:** none.
**Covers:** audit finding 8 (DECIDED).

## Context

The go and php packs ship provider-neutral native-agent sources at `platform-packs/<slug>/code-review/bill-<slug>-code-review/native-agents/agents.yaml`, so their review specialists run as native subagents. The python pack has no `native-agents/` source, so python specialists cannot run natively.

## Scope

- Create `platform-packs/python/code-review/bill-python-code-review/native-agents/agents.yaml` mirroring `platform-packs/go/code-review/bill-go-code-review/native-agents/agents.yaml` and the php equivalent: same 10 approved areas (`architecture`, `performance`, `platform-correctness`, `security`, `testing`, `api-contracts`, `persistence`, `reliability`, `ui`, `ux-accessibility`), python-appropriate descriptions, `contract_version` present and matching the current native-agent-composition contract.
- Content must be python-flavored (frameworks, idioms, review signals), not a find-and-replace of go/php prose — reuse the structure, adapt the domain language to the python pack's declared areas and content files.

## Acceptance criteria

1. `platform-packs/python/code-review/bill-python-code-review/native-agents/agents.yaml` exists, declares `contract_version`, and defines native agents for exactly the 10 approved areas the python pack declares, following the go/php structural pattern.
2. The file validates against the native-agent-composition schema and `scripts/validate_agent_configs` passes.
3. No provider-specific generated outputs (`claude-agents/`, `codex-agents/`, `opencode-agents/`, `junie-agents/`) are committed.
4. `skill-bill validate` and `npx --yes agnix --strict .` pass; `(cd runtime-kotlin && ./gradlew check)` stays green (no runtime code changes expected).
5. After `./install.sh`, the python review specialists render/install as native subagents the same way go/php specialists do (spot-check the generated provider agent list locally; generated outputs stay untracked).

## Non-goals

- No new review areas beyond the approved 10 and no changes to `platform-packs/python/platform.yaml` beyond what native-agent registration requires (if the manifest needs no change, make none).
- No changes to go/php agents.yaml.
- No company identifiers; neutral placeholders only.

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
./install.sh
```

Tests: none beyond validators — this is governed config; existing schema validators are the acceptance/rejection gate.

## Risk notes

- If subtask 2 has already landed, the python `platform.yaml` will be at contract 1.2; agents.yaml `contract_version` is a DIFFERENT contract (native-agent composition) — do not confuse the two.
- Check whether area-specialist native agents must match `declared-area parity` coherence checks; declare only areas the python manifest declares.

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_4_python-native-agents.md`.
