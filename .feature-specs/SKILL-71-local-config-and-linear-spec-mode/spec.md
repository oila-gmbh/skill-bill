# SKILL-71 - skill-bill local config and Linear spec mode

Created: 2026-06-07
Status: Draft
Issue key: SKILL-71
Parent: introduces a repo-local skill-bill config and a Linear-backed spec mode
on top of the SKILL-67 canonical feature-task surface and the existing
decomposition-manifest contract (`orchestration/contracts/decomposition-manifest-schema.yaml`).

## Decomposition

This feature is decomposed because it spans four independent seams plus a closing
gate, in dependency order:

1. a new **repo-local config foundation** — a gitignored `.skill-bill/config.yaml`
   scaffolded at install, read behind a hexagonal port with explicit precedence
   and loud-fail validation (config + install + infra);
2. a **persisted spec-source contract** — an additive, back-compatible
   decomposition-manifest field plus a single_spec `spec.md` convention that
   records how a feature was prepared, so downstream steps never consult mutable
   config (contracts + schema + parity);
3. **`bill-feature-spec` Linear mode** — `service:default/linear`, Linear MCP
   parent ticket + one sub-issue per subtask, parent key as the issue key, and
   stamping the resolved mode + Linear ids into the artifact (skill + Linear MCP);
4. **feature-task / goal consumption** — read the stamp (never config), exclude
   `.feature-specs/{KEY}/` from commit staging, delete the local spec dir on
   terminal success, and rehydrate from Linear on resume/verify (skills + runtime
   handoff);
5. **`bill-code-review` config fallback** — fall back to
   `code_review_parallel_agent` from config when `parallel:` is absent, arg still
   overriding (skill, independent of 2-4);
6. the **closing validation gate** — full maintainer command set plus a
   back-compatibility sweep proving existing `0.2` manifests still load under the
   bumped contract (gate).

Implement on one branch with a commit per subtask:

1. [Repo-Local Config Foundation and Install Scaffolding](./spec_subtask_1_repo-local-config-foundation.md)
2. [Persisted Spec-Source Contract](./spec_subtask_2_persisted-spec-source-contract.md)
3. [bill-feature-spec Linear Mode](./spec_subtask_3_feature-spec-linear-mode.md)
4. [feature-task and goal Stamp Consumption](./spec_subtask_4_feature-task-goal-stamp-consumption.md)
5. [bill-code-review Config Fallback](./spec_subtask_5_code-review-config-fallback.md)
6. [Validation Gate and Back-Compatibility Sweep](./spec_subtask_6_validation-gate-and-back-compat-sweep.md)

## Sources

- Scoping exploration on 2026-06-07 confirmed the current state:
  - The headless runtime reads spec text from disk through a single port,
    `FeatureTaskRuntimeRunInvariantsSource`
    (`FileSystemFeatureTaskRuntimeRunInvariantsSource.kt:22`, `Files.readString`),
    and freezes the extracted run-invariants via
    `FeatureTaskRuntimeRunInvariantsStore` (read-once, not re-read per step).
  - There is **no MCP client inside the runtime**; the only MCP code there is for
    registering skill-bill's own server (`McpRegistrationService`). External MCPs
    (Linear) exist only in the agent session.
  - The decomposition manifest is consumed by the runtime via `spec_path`
    resolved to a real file (`DecompositionManifestRuntimeState.kt`,
    `DecompositionManifestRuntimeStateSupport.kt`); nothing reads spec/manifest
    from git — every reader hits the working tree.
  - The manifest schema is the canonical contract
    (`orchestration/contracts/decomposition-manifest-schema.yaml`):
    `additionalProperties: false`, `spec_path` required, `contract_version`
    pinned `const: "0.2"` to `DECOMPOSITION_MANIFEST_CONTRACT_VERSION` in
    `DecompositionManifestSchemaPaths.kt`, enforced by
    `DecompositionManifestSchemaContractVersionTest`. Cross-field rules live in
    `DecompositionManifestCoherenceValidator` and are named under
    `x-coherence-checks` (e.g. `execution-model-default` applies a runtime
    default before validation — the precedent for defaulting a new field).
  - `~/.skill-bill/` is the existing **machine-global** home (runtime install,
    the install-selection record behind `InstallSelectionPersistencePort`,
    `desktop.properties`). It is in `$HOME`, not gitignored, and is **not** the
    right scope for per-project config.
  - The commit step is instruction-driven and already stages by **explicit
    enumerated path** with an explicit ban on `git add -A`
    (`bill-feature-task-prose/content.md:269`).
  - `bill-code-review` already accepts an **optional** `parallel:<agent>` arg
    (`skills/bill-code-review/content.md:8`); supported agent ids come from
    `InstallAgent.supportedIds` (also surfaced by
    `skill-bill code-review-merge --help` / `--lane2-agent`). Lane execution is
    `ParallelCodeReviewRunner`.
- Maintainer decisions recorded at intake on 2026-06-07 (authoritative):
  1. Config scope is **repo-local and gitignored**, not machine-global; it is a
     project default source, never the runtime source of truth.
  2. Disk stays the runtime's source of truth; Linear is the durable archive. The
     on-disk spec is **uncommitted local scratch** in linear mode, deleted on
     terminal success, and rehydrated from Linear when later steps need it.
  3. Decomposed features map to a **parent Linear ticket plus one sub-issue per
     subtask** (both tagged `task`); a per-subtask Linear id is stored so
     rehydrate is a deterministic per-subtask fetch, not header parsing.
  4. Mode is decided at prep and **stamped into the artifact**; downstream reads
     the stamp, never the config. `parallel:` and `service:` remain optional
     overrides with precedence `arg > config > built-in default`.

## Problem

Every feature is specced through on-disk `.feature-specs/` artifacts regardless
of where the team actually tracks work. For a Linear-backed project this means
the spec lives in two places (Linear issue + committed disk spec) or only on
disk, and there is no project-level switch to say "this repo is Linear-backed,
spec everything through it." Separately, recurring per-project preferences (which
parallel code-review agent to use) can only be passed per-invocation, so they
must be retyped every time.

There is no repo-local place to record project defaults, and no mechanism for a
prepared feature to carry "how was I prepared" so that implementation, commit,
cleanup, resume, and verification stay consistent without re-supplying flags.

## Goals

1. Add a gitignored, repo-local `.skill-bill/config.yaml`, scaffolded at install,
   read behind a hexagonal port with explicit precedence and loud-fail
   validation, holding `spec_type` and `code_review_parallel_agent` with room to
   grow.
2. Add a `service:default/linear` mode to `bill-feature-spec` that creates a
   Linear parent ticket plus one sub-issue per subtask (tag `task`) with the spec
   as description, adopts the parent key as the issue key, and stamps the
   resolved mode + Linear ids into the artifact.
3. Keep disk the runtime source of truth: in linear mode the on-disk spec is
   uncommitted scratch, excluded from commit staging, deleted on terminal
   success, and rehydrated from Linear for resume/verify.
4. Persist the spec-source decision in the artifact as an additive,
   back-compatible contract change so downstream steps read the stamp, never the
   config.
5. Make `bill-code-review` fall back to `code_review_parallel_agent` from config
   when `parallel:` is absent, with the arg still overriding.

## Non-Goals

- Do **not** teach the headless runtime to call Linear (no GraphQL client, no
  token on disk). All Linear access is agent-side via the Linear MCP.
- Do **not** make Linear the runtime's read source. Disk remains the read source;
  rehydrate writes disk before the runtime reads.
- Do **not** store secrets in `.skill-bill/config.yaml`; auth stays in the MCP
  layer. The config holds preferences only.
- Do **not** change the runtime phase loop, handoff payload shapes, schema gate
  semantics, or the `spec_path` resolution mechanism.
- Do **not** make `spec_source` required or break existing `0.2` manifests; the
  new field is optional and defaults to `local`.
- Do **not** remove the `parallel:` or `service:` override args; they remain
  optional overrides over config.

## Target User Experience

- In a Linear-backed repo whose `.skill-bill/config.yaml` sets
  `spec_type: linear`, a maintainer runs `bill-feature-spec` with just an issue
  description; it creates the Linear parent ticket (+ sub-issues for a decomposed
  feature), uses the parent key as the issue key, writes the local scratch spec,
  and stamps `spec_source: linear` into the artifact.
- `bill-feature-task` / `skill-bill goal` run exactly as today off the disk spec,
  but the commit step omits `.feature-specs/{KEY}/`, and on terminal success the
  local spec dir is deleted (the manifest last) — Linear is the archive.
- A maintainer who resumes a completed/aborted-then-cleaned linear feature, or
  runs `bill-feature-verify` after deletion, transparently rehydrates the spec
  from Linear before any read.
- A maintainer who passes `service:local` or `service:linear` overrides config
  for that one prep; a maintainer who passes `parallel:codex` overrides the
  configured `code_review_parallel_agent` for that one review.
- In a non-Linear repo (no config, or `spec_type: local`), behavior is unchanged
  from today.

## Acceptance Criteria

1. A gitignored, repo-local `.skill-bill/config.yaml` is scaffolded at install
   (and added to `.gitignore`), read behind a hexagonal port with precedence
   `explicit arg > config > built-in default`; unknown keys/values loud-fail with
   a named error; a missing file resolves to built-in defaults without error.
2. The decomposition-manifest schema gains an optional top-level
   `spec_source: local | linear` (default `local`) and an optional per-subtask
   `linear_issue_id` (nullable string); `contract_version` is bumped in lockstep
   in the schema and `DecompositionManifestSchemaPaths.kt`, the parity test
   passes, and a runtime default applies `local` when the field is absent so
   existing `0.2`-era manifests still load and validate.
3. single_spec preparation records the same decision as a parsed `spec.md` line
   (`spec_source: linear`) that the spec readers honor; absence means `local`.
4. `bill-feature-spec` accepts `service:default/linear`, resolves mode via
   `arg > config spec_type > local`, and in linear mode uses the Linear MCP to
   create a parent ticket (tag `task`) plus one sub-issue per subtask (tag
   `task`) with the spec content as description, adopts the parent key as the
   issue key / `.feature-specs/{KEY}-{name}/` directory, and stamps the resolved
   `spec_source` and per-subtask `linear_issue_id` into the artifact. If linear
   mode is selected but the Linear MCP is unavailable, it loud-fails before
   writing any artifact.
5. When the stamp reads `linear`, `bill-feature-task` / `skill-bill goal` exclude
   `.feature-specs/{KEY}/` from commit staging (preserving the existing
   no-`git add -A`, enumerate-paths contract) so neither spec, subtask specs, nor
   manifest enter git history.
6. On terminal success of a linear-mode feature, the local spec dir is deleted —
   incrementally for decomposed (each subtask spec after its subtask completes,
   parent spec + manifest after the final subtask) — and only on success; an
   aborted/incomplete run leaves the local scratch intact for resume.
7. When a linear-mode feature's local spec is missing, resume and
   `bill-feature-verify` rehydrate it from Linear (parent key + per-subtask
   `linear_issue_id`) via MCP before any read; downstream reads the artifact
   stamp and never the config.
8. `bill-code-review` falls back to `code_review_parallel_agent` from config when
   `parallel:` is absent (`none` -> single-lane; an agent id -> that lane), the
   `parallel:` arg still overrides, and a config value is validated against
   `InstallAgent.supportedIds + none` with a loud-fail on an unknown value.
9. Maintainer validation passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`

## Design Notes

- **Config scope and shape.** Repo-local `.skill-bill/config.yaml` resolved
  relative to repo root, distinct from machine-global `~/.skill-bill/`. YAML for
  hand-editability. Flat key map; each key consumed by the owning skill/command;
  precedence `explicit arg > config > built-in default`. Reader is a
  domain-owned port with a `runtime-infra-fs` adapter, mirroring
  `InstallSelectionPersistencePort`. No secrets — auth stays in the MCP layer.
- **Stamp vs config.** Config is a mutable project default; a prepared feature is
  immutable. The stamp (`spec_source` + per-subtask `linear_issue_id` in the
  manifest; a `spec.md` line for single_spec) is the per-feature source of truth
  that all downstream steps read. This prevents the drift bug where flipping
  config mid-flight corrupts an in-flight linear feature.
- **Back-compatible contract bump.** `spec_source` and `linear_issue_id` are
  optional with documented defaults; the contract version still bumps (the parity
  test forbids silent schema drift) and a coherence default applies `local` when
  absent, mirroring the `execution-model-default` precedent. Existing manifests
  remain valid.
- **Disk stays source of truth.** The runtime is unchanged: it reads
  `spec_path` from the working tree once and freezes invariants. Linear is never
  read by the runtime. Rehydrate is an agent-side MCP fetch that writes the disk
  scratch before the runtime/verifier reads it.
- **Commit exclusion, not gitignore.** The commit step already enumerates paths
  and bans `git add -A`, so linear mode simply omits `.feature-specs/{KEY}/` from
  the staged set. No global ignore rule, no `git reset` dance; nothing
  spec-related ever enters history.
- **Linear layout.** Parent ticket + sub-issue per subtask, each tagged `task`.
  The parent key is the issue key; each subtask carries its sub-issue id for a
  deterministic per-subtask rehydrate fetch (no header parsing).
- **Boundary contracts** from `runtime-kotlin/ARCHITECTURE.md` hold: application
  must not depend on Clikt/MCP/JDBC; schema validators are reached only through
  domain-owned ports; `runtime-core` is the only composition root. The new config
  reader follows the same port/adapter split.

## Validation Strategy

Per-subtask validation is defined in each subtask spec. The closing subtask runs
the full maintainer command set and a back-compatibility sweep that loads a
representative existing `0.2`-era manifest under the bumped contract (default
applied, validation passes) and exercises a local-mode prep end-to-end to confirm
unchanged behavior when no config is present.

## Open Questions

- Exact config filename/dir (`.skill-bill/config.yaml` vs a flatter
  `.skill-bill.yaml`) given the visual collision with machine-global
  `~/.skill-bill/` — resolved in subtask 1; default to `.skill-bill/config.yaml`
  scoped to repo root with the gitignore entry anchored as `/.skill-bill/`.
- Whether `linear_issue_id` should also be recorded for the parent in the
  manifest top-level or derived from `issue_key` — resolved in subtask 2; default
  to `issue_key` carrying the parent and per-subtask `linear_issue_id` carrying
  the children.
