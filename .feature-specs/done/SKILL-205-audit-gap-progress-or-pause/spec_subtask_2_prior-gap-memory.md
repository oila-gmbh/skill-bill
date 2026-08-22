# Subtask 2 — Prior-gap memory for continuing `audit_gap` rounds

## Scope

When `audit_gap` remediation continues (after a progress-making round or an
operator grant from subtask 1), brief implement and the following audit with a
bounded durable memory of prior unmet criteria, last implement claims against
them, and sticky ids. Qualify audit prompts so repeated criteria require
re-justification; qualify implement prompts so sticky unmet items are
prioritized. Do not treat implement claims as proof of satisfaction — audit
still re-reads the tree.

## Acceptance Criteria

1. A bounded prior-gap memory projection (new contract or an extension of an
   existing durable audit/repair surface) records, per `audit_gap` round:
   unmet criterion refs and notes from the audit that fired the edge, and
   which of those the subsequent implement receipt claimed to address.
2. Implement briefings under `audit_gap` include that memory and instruct
   prioritizing sticky unmet criteria while still closing every currently
   listed gap in one invocation.
3. Audit briefings after a remediation implement include the memory and
   require explicit re-justification when repeating a sticky criterion id;
   blank-slate "ignore earlier audits" wording is removed or subordinated.
4. In-flight workflows without the projection degrade to empty memory without
   failing the phase; once two comparable rounds exist, subtask 1's
   no-progress comparison remains authoritative.
5. Tests cover projection schema/parity, briefing inclusion on remediation
   re-entry, and prompt directives for sticky re-justification / priority.

## Non-Goals

- Replacing the audit agent with deterministic AC checkers.
- Changing the no-progress / warn-threshold pause rules from subtask 1.
- Expanding repair-item receipt identifiers beyond what memory needs.

## Dependency Notes

Depends on subtask 1 so pause policy and consecutive unmet-set comparison
already exist; memory feeds better retries after grants without reopening
thrash.

## Validation Strategy

Contract parity tests for the projection; briefing assembler / prompt composer
tests; one loop integration asserting memory appears on the second
`audit_gap` implement and the audit that follows. Full
`./gradlew check --continue` before commit.

## Next Path

None — feature complete after this subtask.
