# SKILL-138 · Subtask 4 — Cursor headless runtime, decoding, models, and continuation

Parent: [spec.md](./spec.md)

## Scope

- Implement `CursorAgentRunCommandBuilder` as a provider strategy and register
  it in headless adapters and review-isolation resolution.
- Normal launches use the documented equivalent of:

  ```text
  agent --print --force --trust --approve-mcps --workspace <repo>
    --output-format stream-json --stream-partial-output
    [--model <model-with-parameters>] <prompt>
  ```

- Resolve `agent` through `PATH`, use no PTY, preserve requested workdir/timeout,
  and use existing continuation environment fields.
- Add a bounded JSONL decoder that assembles assistant output once, extracts
  documented usage, and uses sanitized versioned fixtures.
- Make malformed JSONL, provider errors, missing terminal output, and empty
  usable output visible as failure.
- Add Cursor model capability; merge effort into bracket parameters, preserve
  other parameters, accept identical effort, and reject conflicts.
- Test invoked/override/phase/parallel agent routing, feature-task run/resume,
  goal children, and direct continuation.
- Keep Cursor out of prose-only refusal and cover this in help/refusal tests.

## Acceptance Criteria

1. The builder emits documented command, prompt, workspace, timeout,
   environment, non-PTY, and normal runtime approvals.
2. Cursor is launchable and only deliberate prose-only providers remain refused.
3. JSONL fixtures decode result/usage without duplication; malformed, oversized,
   error, and empty fixtures fail safely.
4. Streaming remains observable without bypassing durable progress, activity,
   timeout, cancellation, or shutdown policies.
5. Model-only and model-plus-effort directives produce valid Cursor arguments,
   including parameterized/conflicting cases.
6. Continuation launches Skill Bill directly with preserved workflow, branch,
   and review context.
7. Builder, decoder, adapter, CLI, routing, directive, timeout, interruption,
   and continuation tests pass.

## Non-Goals

- Cursor chat IDs, `agent --resume`, cloud agents, or editor automation.
- Specialist permission isolation, owned by subtask 5.

## Dependencies

- depends_on: `[1]`
- dependency_reason: Runtime registration requires `InstallAgent.CURSOR`.

## Validation Strategy

```bash
cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test \
  :runtime-infra-fs:test :runtime-cli:test
```

## Next Path

Run `bill-feature-task` on this spec after subtask 1.
