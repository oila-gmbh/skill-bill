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
`ux-accessibility` defer concerns owned by the other lane instead of duplicating
them. Specialists do not invoke sibling specialists. A severity closer says
`Blocker or Major` and requires a concrete consequence. The only severity
ratings are `Blocker`, `Major`, and `Minor`.

## Baseline Skeleton

Every baseline names classification rules, supplies a diff-signal routing
table, defines Mixed Diffs behavior, excludes generated, vendored, and
non-stack files, and keeps findings disciplined. It merges specialist findings
by preserving attribution before deduplicating overlapping findings.

## Manifest Conventions

`platform.yaml` declares every baseline and specialist `content.md`, the
approved review areas, routing signals, and generated pointers. It does not
declare generated source files or use pack prose to override routing contracts.

## Native-Agent Description Pattern

Provider-neutral native-agent sources describe when to use a specialist in one
specific, stack-and-area sentence. Generated provider outputs are never
authored or committed.

## Quality-Check Skeleton

Every declared quality-check source has `Purpose`, `Execution Steps`, and
`Fix Strategy` H2 sections. It identifies the scoped files, runs the pack
entrypoint, fixes scoped failures, and reruns the check.

## Authored-Sidecar Contract

A specialist may own one co-located authored Markdown rubric sidecar only when
its `content.md` explicitly names the sidecar and why a normal H2 section is
insufficient. The sidecar contains specialist rubric content only. It cannot
replace `content.md`, masquerade as a generated pointer, contain wrapper or
provider output, or become an arbitrary organization file.
