# Feature: review-learnings-application
Created: 2026-04-03
Status: Complete
Sources: prior SKILL-4 review telemetry work; chat discussion about making active learnings influence future code-review runs in an explicit, auditable way

## Acceptance Criteria
1. Add a helper workflow for resolving active learnings that apply to a review using explicit `global`, `repo`, and `skill` scopes.
2. Keep learnings application explicit and safe: only active learnings apply, precedence is deterministic, and disabled learnings stay out of review context.
3. Update shared code-review contracts so relevant learnings can be loaded before review, passed through routing/delegation, and treated as visible context instead of hidden behavior.
4. Update review output contracts so a review must explicitly say which learnings were applied, or say that none were applied.
5. Add documentation and automated coverage for learnings resolution, precedence, and auditable review output wiring.

## Non-Goals
- Automatic cloud sync
- Hidden auto-learning or irreversible preference application
- Replacing evidence-based review judgment with learned preferences
- Runtime-specific special cases that break portability

---

## Consolidated Spec

Phase 1 and phase 2 introduced local-first review telemetry, number-based triage, and a user-controlled learnings layer. Users can now record which findings they accepted, dismissed, or asked to fix, and they can promote durable preferences into explicit learnings entries.

The next step is to close the loop: make active learnings available to future review flows. This must stay explicit, scope-aware, and auditable. Review behavior should not silently mutate based on hidden preferences.

Add a helper path that resolves active learnings for the current review context. The resolution model should support:

- `global` learnings that apply everywhere
- `repo` learnings that apply to one repository or repo identity
- `skill` learnings that apply to one routed review skill

Resolution must exclude disabled learnings and use a deterministic precedence order so the resulting context is understandable and testable.

Then wire that resolved context into the shared code-review contracts. The shared router and stack-specific review skills should load applicable learnings when available, pass them through routing/delegation, and surface them in the review summary. A review must say either that no learnings were applied or which learnings were used, so later feedback remains auditable.

Keep the design local-first and portable across runtimes. Do not introduce hidden suppression rules, cloud analytics dependencies, or runtime-specific shortcuts that the validator and docs cannot explain.
