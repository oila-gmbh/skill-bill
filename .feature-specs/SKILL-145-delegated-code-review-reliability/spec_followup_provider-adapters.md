# Follow-up specification: provider adapter isolation

**Order:** 5 of 9  
**Depends on:** `spec_followup_persistence.md`  
**Purpose:** expose independent provider capabilities while keeping the generic
process runner strategy-driven.

## Scope and targets

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunAdapters.kt`
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/FileSystemAgentRunLauncher.kt`
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/process/AgentRunProcessRunner.kt`
- `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/launcher`

Each provider must expose independently testable isolation, launch, output
decoding, declared progress, cancellation, timeout, token reporting, and
terminal-result capabilities. Codex retains `fork_turns: none`; Claude retains
fresh-process and stream-decoder behavior; Cursor retains its own process and
stream strategy. Unsupported providers terminate explicitly.

## Acceptance and rejection cases

Accept a provider only when its own adapter satisfies the capability contract
and binds output to the current assignment. Reject missing native capability,
stale installed identity, non-zero or interrupted process, timeout, invalid
terminal result, and provider/assignment mismatch. An explicit delegated
request never silently substitutes inline execution.

Provider-isolation tests must prove a Codex change cannot alter Claude, Cursor,
or unchanged adapters. The generic process runner receives injected strategy
objects and contains no provider-identity branch.

## Exclusions

Do not broaden installed provider support or change the inline default as part
of this follow-up.
