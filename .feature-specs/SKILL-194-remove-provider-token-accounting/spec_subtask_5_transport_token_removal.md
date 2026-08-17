# SKILL-194 Subtask 5 — Transport-layer provider token removal

## Scope

Stop reading provider token usage off the wire at all. Every consumer is gone by this point, so this
subtask removes the fields that carried the values and the decoding that produced them.

Delete from `runtime-ports/.../agentrun/model/AgentRunLauncherModels.kt`:

- `inputTokens`, `cachedInputTokens`, `outputTokens`, `reasoningTokens`, `totalTokens` (`:247-251`)
- `tokenOwnership` (`:252`) and the `AgentRunTokenOwnership` enum
- `providerUsageEnforceable` (`:253`)
- the non-negativity require covering those five fields (`:276-282`)

Delete the corresponding fields from `DecodedAgentRunOutput`
(`runtime-infra-fs/.../launcher/agentrun/AgentRunAdapters.kt:178`) and their mapping at `:83-89`.

Delete the usage decoding from all four decoders in `AgentRunAdapters.kt`:

- `decodeClaudeJson` (`:224-235`) — `input_tokens`, `cache_read_input_tokens`, `output_tokens`,
  `reasoning_tokens`, `total_tokens`
- `decodeClaudeStreamJson` (`:242-264`) — the same five keys off the terminal `result` event
- `decodeCodexJsonl` (`:266-285`) — `input_tokens`, `cached_input_tokens`, `output_tokens`,
  `reasoning_tokens`, `total_tokens`
- `decodeCursorStreamJson` (`:369`) — the `cursorTokens` reads

Each decoder keeps everything else exactly as it is: `text` harvesting, terminal-event selection,
`rawOutputPreview` degradation, `assistantEventCount`, session identity, and the malformed-stream
handling. The Claude stream decoder must still select the last `type: "result"` event and still degrade
to an empty harvest with a bounded excerpt when no terminal event arrives; removing usage decoding must
not disturb that path.

After this subtask, no type in the runtime carries a provider-reported token value.

## Acceptance Criteria

1. `AgentRunLaunchFacts` carries no `inputTokens`, `cachedInputTokens`, `outputTokens`,
   `reasoningTokens`, `totalTokens`, `tokenOwnership`, or `providerUsageEnforceable`, and
   `AgentRunTokenOwnership` no longer exists.
2. `DecodedAgentRunOutput` carries none of those fields, and the adapter no longer maps them.
3. None of the four decoders reads a provider usage key, and no `usage` object is consulted for token
   values anywhere in the transport layer.
4. Each decoder still harvests text identically to before: the buffered Claude decoder, the Claude
   stream decoder selecting the last `type: "result"` event, the Codex JSONL decoder, and the Cursor
   stream decoder.
5. The Claude stream decoder still degrades to an empty harvest with a bounded `rawOutputPreview` when
   no terminal event is present.
6. `assistantEventCount`, session identity fields, `stdoutTruncated`, `stdoutByteSize`, and
   `stdoutSha256` are unchanged.
7. No type in the runtime carries a provider-reported token value after this subtask.
8. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Changing text harvesting, terminal-event selection, degradation behaviour, or session identity
  capture in any decoder.
- Adding `cache_creation_input_tokens` decoding, or any other usage key.
- Touching the local byte-derived estimates in the feature-task runtime; they do not come from a
  provider report.
- Removing the `usage` node from any provider's transport handling where it is read for a non-token
  purpose, if such a read exists.

## Dependency Notes

Depends on subtasks 2, 3, and 4, which remove every consumer of these fields: the review accounting
model and broker, goal session accounting, and the planning-sweep counters. Landing this subtask before
those would break compilation at each consumer.

## Validation Strategy

The observable behaviour is that decoding is unchanged apart from the absent token values.

- Assert the buffered and streamed Claude decoders still produce identical text for the same terminal
  payload, including the recorded incident shape
  `{"input_tokens":2,"cache_read_input_tokens":17931,"output_tokens":6,"total_tokens":120}`, which must
  now decode to its text with no token fields and no error.
- Assert a Claude stream with no terminal `result` event still yields an empty harvest with a bounded
  excerpt.
- Assert the Codex JSONL and Cursor stream decoders still harvest their text and session identity.
- Assert a malformed or blank stream behaves as before.

Rewrite `AgentRunCommandBuildersTest` to drop its token assertions (`:57-62`, `:80`, `:93-96`, `:638`,
`:658-664`) while keeping the text-parity, terminal-selection, and degradation cases; keep the incident
payload as a decode fixture precisely because it must no longer be special. Update
`CursorAgentRunTransportTest` likewise. Then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-194
```
