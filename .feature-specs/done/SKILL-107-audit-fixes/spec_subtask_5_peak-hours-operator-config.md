# SKILL-107 Subtask 5: peak-hours-warner Operator Config

**Parent:** [spec.md](spec.md)
**Depends on:** none.
**Covers:** audit finding 10 (DECIDED).

## Context

`orchestration/shell-content-contract/peak-hours-warner.md` hard-codes a specific vendor (Z.AI/GLM) and peak-hour values as product content. Vendor and peak-hours must come from repo-local operator configuration; the current values become the operator's local config, not tracked product content.

First, investigate how the sidecar is rendered and installed — `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/ScaffoldSupport.kt:103,115` and `orchestration/skill-classes/feature-launch-warning.yaml` — to pick the right config seam. Candidate: a repo-local `.skill-bill/config.yaml` (or the existing `SKILL_BILL_CONFIG_PATH`-relocatable config if that is the established operator-config seam; prefer whichever the runtime already reads).

## Scope

- Genericize `orchestration/shell-content-contract/peak-hours-warner.md`: no vendor names, no concrete hour values; instead it instructs the agent to read the operator's peak-hours config from the chosen seam, describes the expected config shape (vendor label, peak windows, timezone), and defines the no-config behavior: skip the warning silently.
- Establish the config seam. If the sidecar remains pure prose (agent reads a config file in-session), document the file path and shape in the sidecar itself. If a runtime seam is needed (rendered values), add it with typed loud-fail errors for malformed config — but prefer the prose-only approach; do not add runtime machinery unless rendering requires it.
- Ensure the rendering/install path (`ScaffoldSupport.kt`, `feature-launch-warning.yaml` matcher/ceremony wiring) is unchanged in shape: the pointer still generates and installs exactly as before.
- Remove the current Z.AI/GLM values from all tracked files; they live on only in the operator's local, untracked config (provide the operator a ready-to-paste example block in the PR description or spec notes, not in tracked product content — a neutral `acme`-style example inside the sidecar is fine).

## Acceptance criteria

1. `orchestration/shell-content-contract/peak-hours-warner.md` contains no vendor names and no concrete peak-hour values; `grep -rni "z\.ai\|glm" --include='*.md' --include='*.yaml' --include='*.kt' .` (excluding `.git/` and `.feature-specs/`) is empty.
2. The sidecar documents: config location, config shape (with a neutral placeholder example), and explicit no-config behavior (skip the warning without noise).
3. The generated pointer for feature-task shells still renders and installs byte-stable except for the genericized content (verify via `skill-bill render --skill-name bill-feature-task` and `./install.sh`).
4. If (and only if) a runtime config-parsing seam is introduced: malformed config fails loudly with a typed error (rejection test) and well-formed config parses (acceptance test). If the seam is prose-only, tests: none (nothing runtime-testable) — state which path was taken.
5. All four validators pass: `skill-bill validate`, `scripts/validate_agent_configs`, `npx --yes agnix --strict .`, `(cd runtime-kotlin && ./gradlew check)`.

## Non-goals

- No changes to other ceremony pointers (`shell-ceremony`, `telemetry-contract`).
- No new config framework; reuse the existing operator-config seam if one exists.
- Documenting the ceremony-pointer mechanism itself is subtask 7 (finding 9a).

## Validation strategy

```bash
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
(cd runtime-kotlin && ./gradlew check)
skill-bill render --skill-name bill-feature-task
./install.sh
```

## Risk notes

- The investigation may reveal the sidecar is copied verbatim (no templating); in that case the prose-only seam is correct and cheap. Do not invent runtime rendering to inject config values.
- The operator (repo owner) currently relies on the Z.AI/GLM warning; the handoff must include the exact local config block to restore behavior after upgrade, delivered outside tracked files.

## Handoff

Run bill-feature-task on `.feature-specs/SKILL-107-audit-fixes/spec_subtask_5_peak-hours-operator-config.md`.
