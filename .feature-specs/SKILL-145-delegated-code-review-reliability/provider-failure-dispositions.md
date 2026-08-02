# Provider failure dispositions

| Provider | Disposition | Evidence boundary |
| --- | --- | --- |
| Codex | Keep experimental. Enforce fresh fork_turns none, process timeout, bounded JSONL output, durable worker transitions, and explicit terminal classification. Do not treat completion-only usage as in-flight progress. | CodexAgentRunCommandBuilder, CodexNativeReviewLifecycleCallbacks, lifecycle ledger, command-builder/process fixtures |
| Claude | Keep experimental. Preserve fresh-process isolation, streamed output decoding, process/idle deadlines, and the existing callback strategy. | ClaudeAgentRunCommandBuilder, ClaudeNativeReviewLifecycleCallbacks, regression fixtures |
| Cursor | Keep experimental. Preserve fresh-process isolation, stream decoding, bounded output, and explicit provider errors; do not share Codex command flags or process strategy. | CursorAgentRunCommandBuilder, CursorNativeReviewLifecycleCallbacks, cursor stream fixtures |
| Junie | Explicitly unsupported for delegated native workers because its adapter does not expose the required lifecycle and terminal-result contract. | JunieAgentRunCommandBuilder, unsupported launch outcome |
| Copilot | Explicitly unsupported: no headless delegated worker adapter is registered. | adapter registry |
| Opencode | Explicitly unsupported by runtime policy; no delegated worker is launched. | RUNTIME_REFUSED_AGENTS |
| Zcode | Explicitly unsupported by runtime policy; no delegated worker is launched. | RUNTIME_REFUSED_AGENTS |

The remaining parent-spec failure items are dispositioned as follows:

- A live process, MCP startup, stdout chatter, file activity, or completion
  token count is observational evidence only. Durable declared specialist
  progress and a normal zero-exit result remain authoritative.
- Missing, duplicate, stale-attempt, provider-mismatched, or invalid worker
  results block aggregation with a bounded diagnostic.
- An unsupported provider is a terminal unsupported outcome. Inline review is
  not a fallback for an explicit delegated request.
- Provider-specific command flags and lifecycle callbacks remain in the
  provider adapter/builders. The generic process runner receives strategies and
  does not branch on provider identity.
