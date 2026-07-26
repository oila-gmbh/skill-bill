# Feature: review-acceptance-metrics
Created: 2026-04-02
Status: Complete
Sources: chat discussion about measuring review acceptance for code-review findings; follow-up discussion about number-based triage UX and learnings management

## Acceptance Criteria
1. Add a number-based review triage flow so users can respond with issue numbers instead of raw finding IDs.
2. Allow per-number notes such as custom fix instructions or dismissal reasons, and record them in telemetry.
3. Add a separate local learnings layer in SQLite so reusable review preferences can be stored independently from raw feedback events.
4. Add user-facing learnings management commands so users can list, inspect, edit, disable, and delete learnings entries.
5. Keep learnings reviewable and user-controlled; do not force irreversible or hidden learning behavior.
6. Add documentation and tests for the numbered triage UX and learnings management workflow.

## Non-Goals
- Datadog or Firebase integration
- Automatic cloud sync
- Implicit NLP-based acceptance detection
- Full dashboards
- Repo-file promotion into `AGENTS.md` or `.agents/skill-overrides.md` in this pass

---

## Consolidated Spec

Phase 1 already introduced the local-first measurement baseline for `bill-code-review` and the stack-specific review skills it routes to. That baseline added explicit `review_run_id` and `finding_id` contracts plus a repo-native helper for importing reviews and recording raw feedback events.

This phase extends that baseline into a user-friendly feedback loop. Users should be able to triage a review by visible issue numbers rather than raw finding ids, optionally attach notes that explain why an issue should be fixed or dismissed, and have the helper map those numbers back to the stable telemetry identifiers internally.

Add a separate learnings layer in the same local SQLite database. Learnings are reusable preferences or review heuristics derived from user-controlled feedback, but they must stay distinct from raw feedback history. The user must be able to inspect, edit, disable, and delete learnings entries explicitly.

The system must remain local-first and portable across agent runtimes. The helper CLI can become more ergonomic, but it should not depend on agent-specific hooks, cloud analytics, or hidden automatic inference.

Document the new numbered triage flow, the learnings commands, and the safety model clearly, including that learnings remain user-reviewable and removable.
