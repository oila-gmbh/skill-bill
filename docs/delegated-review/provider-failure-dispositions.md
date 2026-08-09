# Provider failure dispositions

| Provider | Disposition | Evidence boundary |
| --- | --- | --- |
| Codex | Keep experimental. Enforce fresh fork_turns none, process timeout, bounded JSONL output, durable worker transitions, and explicit terminal classification. Do not treat completion-only usage as in-flight progress. | CodexAgentRunCommandBuilder, CodexNativeReviewLifecycleCallbacks, lifecycle ledger, command-builder/process fixtures |
| Claude | Keep experimental. Preserve fresh-process isolation, streamed output decoding, process/idle deadlines, and the existing callback strategy. | ClaudeAgentRunCommandBuilder, ClaudeNativeReviewLifecycleCallbacks, regression fixtures |
| Cursor | Keep experimental. Preserve fresh-process isolation, stream decoding, bounded output, and explicit provider errors; do not share Codex command flags or process strategy. | CursorAgentRunCommandBuilder, CursorNativeReviewLifecycleCallbacks, cursor stream fixtures |
| Junie | Explicitly unsupported for delegated native workers because its adapter does not expose the required lifecycle and terminal-result contract. | JunieAgentRunCommandBuilder, unsupported launch outcome |
| Copilot | Explicitly unsupported: no headless delegated worker adapter is registered. | adapter registry |

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

## Provider-keyed remaining failure matrix

Each row is a disposition for every remaining reliability item relevant to the
provider. `mitigated` records bounded runtime behavior; `deferred` keeps the
provider experimental or unsupported until independent evidence changes that
boundary.

| Provider | Capacity and waves | Bootstrap measurement | Deadlines | Diagnostics and repair | Wave telemetry | Promotion threshold |
| --- | --- | --- | --- | --- | --- | --- |
| Codex | mitigated by the coordinator-owned six-worker plan and coordinator slot | mitigated by explicit process-start and observable MCP-start records; absent MCP observation remains zero | mitigated by startup, progress-idle, per-worker, aggregation, and whole-review scopes; completion-only usage is not progress | mitigated by bounded lifecycle references and strict current-attempt aggregation; raw JSONL is not persisted | mitigated by durable predicted and actual wave membership | deferred until independent authenticated canaries show stable liveness, deadlines, aggregation, and interruption evidence |
| Claude | mitigated by the same coordinator plan without changing Claude command construction | mitigated by launcher-bound process facts; MCP startup counts only when explicitly observed | mitigated by injected scope policy and Claude's existing process/idle strategy | mitigated by bounded diagnostics and current-attempt aggregation checks | mitigated by shared durable wave projection | deferred until regression fixtures and authenticated canaries establish promotion evidence |
| Cursor | mitigated by the same coordinator plan without importing Codex flags or process strategies | mitigated by launcher-bound process facts; heartbeat does not imply MCP startup | mitigated by injected scope policy and Cursor's existing stream strategy | mitigated by bounded diagnostics, explicit provider errors, and strict repair boundary | mitigated by shared durable wave projection | deferred until stream, deadline, and aggregation canaries establish promotion evidence |
| Junie | deferred as unsupported because no delegated lifecycle adapter exists | unsupported; no launch is counted | unsupported; terminates as explicit unavailable rather than falling back | mitigated only at the boundary by bounded unsupported diagnostics | unsupported; no worker wave is launched | deferred until an independent lifecycle-capable adapter exists |
| Copilot | deferred as unsupported because no headless delegated adapter is registered | unsupported; no launch is counted | unsupported; terminates as explicit unavailable | mitigated only by the explicit unsupported terminal outcome | unsupported; no worker wave is launched | deferred until a governed adapter is registered and independently measured |

The provider-keyed rows above explicitly disposition every remaining historical
failure item relevant to delegated reliability: items 13-18 (capacity,
coordinator capacity, waves, bootstrap, and bounded admission), 24-25 (whole-
review deadline and provider-output measurement), 28 (per-worker deadline),
33 and 40 (bounded diagnostics and result-envelope repair), 42 (wave
telemetry), and 47 (provider promotion thresholds). For Codex, Claude, and
Cursor these items are mitigated by the shared durable lifecycle contract with
the provider strategy named in the row; for Junie and Copilot they remain
explicit unsupported or policy-deferred outcomes.

The matrix is provider-keyed so a mitigation for Codex cannot be read as a
change to Claude, Cursor, or unchanged-provider command builders and process
strategies. These dispositions do not alter inline defaults, routing, declared
areas, or automatic resolution.

## Item-keyed disposition check

The following compact check prevents the category rows above from hiding an
undispositioned historical item. `mitigated` means the shared lifecycle
behavior is enforced and measured; `unsupported/deferred` is an explicit
terminal boundary, not inline fallback.

Item mapping: 13 capacity, 14 coordinator slot, 15 predicted waves, 16 actual
waves, 17 selected-area conservation, 18 bounded admission, 24 whole-review
deadline, 25 provider-output measurement, 28 per-worker deadline, 33 bounded
diagnostics, 40 result-envelope repair, 42 wave telemetry, 47 promotion
threshold.

| Provider | 13 | 14 | 15 | 16 | 17 | 18 | 24 | 25 | 28 | 33 | 40 | 42 | 47 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Codex | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | deferred |
| Claude | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | deferred |
| Cursor | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | mitigated | deferred |
| Junie | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred |
| Copilot | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred | unsupported/deferred |
