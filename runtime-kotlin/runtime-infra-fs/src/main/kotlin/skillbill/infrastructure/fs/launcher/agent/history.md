## [2026-07-27] Cursor full agent support (subtask 4: runtime-headless)
Areas: agentrun (launcher), featuretask (runtime loop), cli (routing)
- Implemented CursorAgentRunCommandBuilder as provider strategy with documented command format (--print --force --trust --approve-mcps --workspace --output-format stream-json --stream-partial-output)
- Added bounded JSONL decoder for Cursor output with sanitized fixtures and safe failure modes for malformed/oversized/error/empty cases
- Integrated Cursor model capability with effort merging into bracket parameters, conflict detection, and parameter preservation
- Added continuation support preserving workflow, branch, and review context for Skill Bill direct launches
- Excluded Cursor from prose-only refusal; added coverage in help/refusal tests
- Provider strategy pattern for agent-specific command builders (reusable CursorAgentRunCommandBuilder)
- Bounded JSONL decoder pattern with versioned fixtures for agent output parsing (reusable decoder infrastructure)
- Model capability parameter merging with conflict detection (reusable)
- No breaking changes; no known limitations beyond documented non-goals (chat IDs, --resume, cloud agents, editor automation)
Feature flag: N/A
Acceptance criteria: 7/7 implemented