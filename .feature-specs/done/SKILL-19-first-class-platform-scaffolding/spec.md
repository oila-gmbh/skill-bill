# SKILL-19: first-class platform scaffolding

Status: Complete

Sources:
- User briefing in this turn
- AGENTS.md
- agent/history.md

Acceptance Criteria:
1. Adding a new platform like java is a first-class authoring flow, not a manual manifest-and-files exercise.
2. A user can create a new platform pack through a single high-level action, with the governed runtime shape generated automatically.
3. After creating a platform, a user can add code-review specialists like architecture and performance through single high-level actions.
4. The authoring workflow hides contract details such as manifest structure, required sections, sidecar wiring, install wiring, and README platform maintenance.
5. The runtime governance model remains strict and loud-fail; the simplification happens in the authoring layer, not the runtime contract.

Non-goals:
- Replacing the runtime shell/content contract.
- Removing shipped reference packs as part of this feature.
- Solving every future family at once beyond the immediate new-platform and add-review-area flow.
- Requiring manual README platform catalog updates for newly scaffolded platforms.

Consolidated Spec Content:
- The governance model is the product.
- Users should not have to understand platform.yaml, required H2 sections, sidecar symlinks, installer wiring, or README/catalog maintenance in order to add support for a new platform.
- Adding a new platform such as Java should be a first-class authoring flow.
- After creating a platform, adding code-review specialists such as architecture and performance should also be first-class authoring flows.
- New platform creation should scaffold quality-check by default.
- We should stop requiring README platform updates as part of this feature.
- Runtime governance stays strict and loud-fail; simplification belongs in the authoring layer.

Consolidated Notes:
- Preserve the runtime shell/content contract.
- Favor the smallest authoring-layer change that makes new-platform and add-review-area creation feel like one high-level action each.
