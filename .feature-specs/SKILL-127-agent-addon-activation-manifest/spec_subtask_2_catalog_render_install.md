---
status: Draft
issue_key: SKILL-127
subtask_id: 2
source: parent spec
---

# SKILL-127.2: Catalogue Rendering and Codex Startup Delivery

## Scope

Render a deterministic metadata-only catalogue for agent add-ons and install a
managed Codex startup or bootstrap surface that instructs new Codex sessions to
read the catalogue at session start.

## Acceptance Criteria

1. Install/render writes `~/.skill-bill/agent-addons/catalog.md` with slug,
   description, activation type, compatible agents, compatible consumers,
   `use_when`, optional context tags, source identity label, and content digest.
2. The catalogue never contains full `content.md` bodies or absolute user paths.
3. Catalogue rendering includes local and configured external agent add-ons in
   deterministic order and participates in install staging identity.
4. Codex receives a managed startup instruction through a documented supported
   surface, or a Skill Bill-managed bootstrap is injected into every
   Skill Bill-launched Codex child when no stable global startup surface exists.
5. Unsupported provider startup surfaces are reported with clear diagnostics
   rather than silently claiming coverage.
6. Re-running install is idempotent and preserves user-owned files outside
   Skill Bill-managed regions.
7. The rendered catalogue includes `codex-agent-policy` as a Codex initializer,
   `peak-hours-warning` as a ZCode initializer, and `execution-budget` as
   contextual.

## Non-Goals

- Implementing runtime prompt loading beyond proving startup/bootstrap delivery.
- Adding contextual task matching.
- Implementing startup delivery for every supported agent if no stable surface
  exists.

## Dependency Notes

Depends on subtask 1 for activation metadata and catalogue-safe loader models.

## Validation Strategy

Run install-staging tests, Codex startup/bootstrap tests, and file-boundary tests
for idempotency and generated-file exclusion.

## Next Path

Continue with subtask 3 to apply initializer and contextual activation during
feature-task and goal runtime launches.
