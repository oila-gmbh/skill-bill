# SKILL-159 — Remove external-process delegated review; restructure review modes

**Status:** Prepared
**Issue key:** SKILL-159
**Feature name:** review-mode-restructure

## Intended Outcome

The external-process delegated review subsystem (provider CLI review workers,
lifecycle evidence, capacity/wave planning, canary/promotion apparatus) is
removed entirely. The review mode vocabulary is restructured so names match
mechanics:

- `delegated` (new meaning, default): the existing specialist subagent fan-out
  review that runs inside the invoking agent's harness — today's "inline".
- `inline` (new meaning): a new simplified single-prompt review in the current
  context with no fan-out and no specialists.
- `auto`: resolves to `delegated` for a subtask's first review pass and to
  `inline` for follow-up/remediation review passes.

Rationale is recorded in `docs/delegated-review/decision.md`: the external
subsystem reconstructs, unreliably (47-item failure ledger, 0/6 promotion
gates on every provider), a completion guarantee the agent harness provides by
construction.

## Acceptance Criteria

1. No runtime code path can launch an external provider CLI process as a code-review worker; the wholly-delegated Kotlin sources and their tests are deleted and shared files are pruned of delegated-lane logic.
2. `CodeReviewExecutionMode` exposes wire values `auto`, `delegated` (specialist fan-out, the default), and `inline` (single-prompt); every parsing, persistence, telemetry, and reported-mode surface uses the new semantics.
3. A goal child's first review pass runs the fan-out (`delegated`) review and any follow-up/remediation review pass runs the single-prompt (`inline`) review; `auto` encodes exactly this policy.
4. The new single-prompt inline review exists as governed content: one review prompt over the child-owned delta, no specialist fan-out, returning findings in the existing severity/report contract consumed by the findings ledger.
5. Contract-versioned schemas whose review-mode semantics changed are bumped; legacy records loud-fail with typed errors and quarantine/regenerate per the standard recipe; no surface silently reinterprets an old `inline`/`delegated` value.
6. `orchestration/contracts/review-lifecycle-schema.yaml`, its validator, version constant, parity test, and Gradle resource copy are removed; the review-lifecycle-evidence contract is removed or re-scoped to surviving surfaces only, consistently with its parity test.
7. All governed content — skills, skill-class YAMLs, platform packs, orchestration playbooks, docs, README — describes only the three restructured modes; no installable content references external-process delegated review, provider capability matrices, canaries, or promotion gates.
8. `docs/delegated-review/` is retained as historical rationale with a preface noting the subsystem was removed by SKILL-159; the nine `spec_followup_*.md` files under `.feature-specs/SKILL-145-delegated-code-review-reliability/` are deleted.
9. `parallel-review:<agent>` continues to work as a second full review lane under the new mode names, in both runtime and prose paths.
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Constraints

- `orchestration/review-orchestrator/specialist-contract.md` remains the single authored source of fan-out worker rules; it now backs the `delegated` mode.
- Applied database migration bodies are append-only history: do not rewrite or delete existing migration entries. Stop reading/writing delegated lifecycle tables; a new migration may drop them, but existing DBs must not break on startup.
- Unsupported-provider refusal paths for review workers disappear with the subsystem; agent-run refusal logic unrelated to review (e.g. runtime-mode refusal for opencode/zcode) is untouched.
- No `claude -p` or other headless agent invocations may be introduced.

## Non-Goals

- Quality-check routing changes.
- Feature-task phase-order changes (audit before review stays).
- Goal findings-ledger contract changes.
- Review depth/tier policy changes beyond the mode rename and the pass-number policy in AC-3.

## Subtasks

1. `spec_subtask_1_remove_external_delegated_subsystem.md` — delete the external-process delegated review subsystem and its contracts/tests.
2. `spec_subtask_2_mode_rename_and_single_prompt_inline.md` — restructure the mode enum, auto policy, pass sequencing, persistence, telemetry, and contract versions; add the single-prompt inline review path.
3. `spec_subtask_3_governed_content_docs_and_packs.md` — rewrite governed skill/pack/orchestration/docs content for the new mode set and delete the SKILL-145 follow-up specs.
