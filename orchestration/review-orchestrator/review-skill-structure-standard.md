---
name: review-skill-structure-standard
description: Testable authored-source requirements for platform-pack review skills.
---

# Review Skill Structure Standard

This standard governs authored `content.md` sources for platform-pack review
skills. Generated wrappers and pointers remain outside this source contract.

## Specialist Skeleton

Every specialist uses these H2 sections in order: `Focus`, `Ignore`,
`Applicability`, and `Project-Specific Rules`. `Repo-Local Knowledge` may be
the only trailing optional H2. Rules are grouped by H3. Each specialist states
concrete API-boundary or failure-mode checks appropriate to its area; `ui` and
`ux-accessibility` explicitly defer concerns owned by the other lane and the
`security` lane instead of duplicating them. Specialists do not invoke sibling
specialists. A severity closer says
`Blocker or Major` and requires a concrete consequence. The only severity
ratings are `Blocker`, `Major`, and `Minor`.

## Baseline Skeleton

Every baseline uses these H2 sections in order: `Classification Rules`,
`Diff-Signal Routing Table`, `Mixed Diffs`, and `Finding Discipline`.
Classification names explicit `if` decisions and an `otherwise` outcome. The
routing table maps file-level diff signals to specialists. Mixed diffs keep the
baseline specialists for the whole review while using a lightweight file-level
classification pass for specialist selection. Per-specialist scope excludes
generated, vendored, and non-stack-owned files. Finding discipline calibrates
severity, verifies each finding's preconditions, preserves lane attribution,
and only then deduplicates overlapping findings.

## Manifest Conventions

`platform.yaml` declares every baseline and specialist `content.md`, the
approved review areas, routing signals, and generated pointers. It does not
declare generated source files or use pack prose to override routing contracts.
Every file-extension routing signal appears in both bare (`.kt`) and glob
(`*.kt`) forms. When routing signals overlap, tie-breakers state a positive
dominance rule, a negative disambiguation rule against adjacent packs, and an
exclusion of generated or vendored files from dominance scoring.
`area_metadata.focus` is bespoke to the stack and area, not copied boilerplate.

## Native-Agent Description Pattern

Each provider-neutral specialist description follows this sentence pattern:
`<Stack> <area> specialist code reviewer. Runs against <lanes>. Returns a Risk
Register in the F-XXX bullet format.` Generated provider outputs are never
authored or committed.

## Quality-Check Skeleton

Every declared quality-check source has `Purpose`, `Execution Steps`, and
`Fix Strategy` H2 sections. It discovers commands from repository build files,
wrappers, and CI configuration before falling back to defaults; identifies the
scoped files; runs the pack entrypoint; uses a priority-ordered fix ladder;
never suppresses failures; reruns targeted checks; and escalates to the full
suite when the targeted result cannot establish safety.

## Authored-Sidecar Contract

A specialist may own one co-located authored Markdown rubric sidecar only when
its `content.md` explicitly names the sidecar and why a normal H2 section is
insufficient. The sidecar contains specialist rubric content only. It cannot
replace `content.md`, masquerade as a generated pointer, contain wrapper or
provider output, or become an arbitrary organization file.
