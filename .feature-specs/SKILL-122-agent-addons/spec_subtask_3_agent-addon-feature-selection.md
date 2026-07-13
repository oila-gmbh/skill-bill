---
status: Ready for implementation
issue_key: SKILL-122
subtask_id: 3
---

# SKILL-122 Subtask 3: Explicit selection and workflow propagation

## Scope

Add the feature-facing `agent-addon:<slug>` argument and its single
compatibility-resolution path. Carry the ordered selection and content digests
through `bill-feature`, feature-task prose/runtime paths, the runtime CLI and
requests, prompt building, durable workflow records, retries/resumes, and
decomposed-goal child launches.

## Acceptance Criteria

1. `bill-feature` recognises zero or more ordered `agent-addon:<slug>` tokens
   alongside existing mode, review-mode, and parallel-review tokens. Omission
   leaves all current behaviour unchanged.
2. Before the existing single confirmation gate, the router rejects malformed,
   duplicate, missing, unknown, unsupported-consumer, and
   agent-incompatible add-on selections with an actionable error and no
   workflow/child side effect.
3. Compatibility resolution uses the effective agent, agent override, and all
   explicit phase-agent overrides that would receive the add-on. Incompatible
   phase assignments fail before launch; no phase silently drops a selection.
4. The confirmation includes ordered selected slugs and manifest descriptions.
   Every subsequent router/sidecar/CLI boundary receives the already-resolved
   selection and must not reparse or reorder the user's tokens.
5. Durable state persists ordered slug, source identity, and content digest.
   Runtime and prose retries, review-fix/audit re-entry, continuation, and
   goal-child flows reuse it exactly. Resume fails loudly before execution if a
   selected source is missing or its digest has drifted.
6. Prompt construction reads and injects only the selected content as a
   labelled, ordered add-on section with provenance. It does not load all
   discoverable add-ons, rerun broad discovery in workers, or flatten add-ons
   into unrelated prompts.
7. Runtime and prose execution, including a compatible delegated review lane,
   receive equivalent selected add-on content/provenance. Existing no-add-on,
   review-mode, parallel-review, agent-override, and goal semantics remain
   unchanged.
8. The injection boundary enforces documented precedence: add-on content cannot
   grant delegation authority, add a confirmation gate, override repository or
   governed instructions, alter model controls, suppress required review or
   validation, or weaken any typed failure contract.

## Non-Goals

- A generic add-on argument-value protocol or a persisted `stop-after` field.
- Automatic model/provider-based selection, per-user defaults, or provider UI
  model tuning.

## Validation Strategy

Add router, CLI, application, prose, runtime, resume, and goal tests covering
empty selections, ordered multi-selection, every failure category, prompt
contents, agent overrides, digest drift, and backward compatibility.

## Dependency Notes

Depends on subtasks 1 and 2. It supplies the product execution seam consumed
by the shipped add-on in subtask 4.

## Next Path

Proceed to `spec_subtask_4_execution-budget-addon.md`.
