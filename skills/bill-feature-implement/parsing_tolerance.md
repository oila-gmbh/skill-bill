# RESULT: Block Parsing Tolerance

This artifact records the parsing posture for the JSON `RESULT:` block returned by every `bill-feature-implement` subagent. The orchestrator parses these blocks inline; no machine parser exists in the Kotlin runtime modules. The choice below governs what subagents may emit and how the orchestrator behaves when a subagent's final message deviates from the strict contract.

## Resolutions Considered

- **Resolution A — Best-effort plus surface failure with retry.** The orchestrator attempts a strict parse first. If the strict parse fails, it tries a best-effort recovery: locate the last `RESULT:` literal in the message, isolate the trailing JSON object, and parse that. If recovery still fails, the orchestrator surfaces the malformed payload back to the subagent with one corrective re-spawn before escalating to the user. Subagents are expected to return strict JSON, but minor deviations (leading prose above the marker, trailing whitespace, single trailing comma) do not abort the workflow.
- **Resolution B — Defensive parsing.** The orchestrator only accepts strictly-formatted output; any deviation is a hard failure with no retry. Subagents would need a richer parser contract (schema validation, error codes) and the orchestrator would need to embed a real parser rather than inline extraction.

## Chosen Resolution

**Resolution A.** The orchestrator currently parses inline; investing in defensive parsing would require a separate parser layer that does not exist today. Best-effort with one corrective retry preserves the strict contract while tolerating the minor formatting deviations that show up in practice (different runtimes wrap output differently, and some emit a short narrative before the `RESULT:` marker even when told not to).

## Runtime Posture

- **All supported runtimes** (Claude, Codex, OpenCode, Copilot) are treated as best-effort emitters. No runtime is granted strict-only treatment. Subagents target strict JSON; the orchestrator absorbs minor noise.
- Subagents MUST still emit exactly one `RESULT:` block as their final message. Multiple `RESULT:` blocks, missing blocks, or non-JSON payloads remain failure conditions.

## Orchestrator Behavior on Malformed RESULT

1. **Strict parse.** Attempt to locate the single `RESULT:` marker and parse the JSON body that follows.
2. **Best-effort recovery.** If strict parse fails, locate the last `RESULT:` marker in the message and parse the trailing JSON object. Strip a single trailing comma before the final closing brace if present. Strip surrounding code-fence markers (```json … ```) if the subagent wrapped the JSON.
3. **Corrective re-spawn (one attempt).** If recovery still fails, re-spawn the same subagent with the same briefing plus a corrective addendum that quotes the malformed payload back and reminds the subagent to emit exactly one `RESULT:` block as the final message with valid JSON matching the declared contract.
4. **Escalation.** If the corrective re-spawn still produces a malformed payload, stop the workflow at the failing step, persist the malformed payload as the failed phase artifact, and report the failure to the user with the payload attached. Do not silently fall back to placeholder values or skip the phase.

## Retry Posture

- Best-effort recovery is automatic and silent (no user prompt).
- Corrective re-spawn is automatic and counts toward the orchestrator's per-step retry budget; it is logged in the workflow state.
- Beyond one corrective re-spawn, the orchestrator escalates to the user. Loops are not permitted at the parsing layer.

## Escalation Path

When parsing escalates to the user:

- The workflow stays in `running` until the user decides; the failing step keeps `status: "running"` with `attempt_count` incremented.
- The user may choose to: re-run the failing step, abandon the workflow (`workflow_status: "abandoned"`), or hand-edit the artifact and resume.
- If the user abandons, call `feature_implement_finished` with an appropriate `completion_status` (typically `error`) and update workflow state to `failed` with the malformed payload preserved in the artifact patch.

## Non-Goals

- Replacing the JSON `RESULT:` contract with a different return shape.
- Adding schema validation in the Kotlin runtime modules (`runtime-core` / `runtime-cli`).
- Changing the per-phase return contracts in [reference.md](reference.md).
