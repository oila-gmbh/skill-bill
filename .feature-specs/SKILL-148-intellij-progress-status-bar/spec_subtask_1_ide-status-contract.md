# SKILL-148 · Subtask 1: IDE status contract

## Scope

Create the schema-first external-consumer contract and a read-only CLI query that
projects the currently relevant Skill Bill work for an explicit repository root. Reuse
existing workflow and goal status authorities, adding a single application-level
selection/projection service where necessary. The command must be suitable for polling
by IDE integrations and must not expose internal persistence records as its wire model.

## Acceptance Criteria

1. A Draft 2020-12 YAML schema under `orchestration/contracts/` defines the IDE status payload, a pinned contract version, strict nested shapes, and every lifecycle, freshness, workflow-family, and error enum required by the first widget.
2. A matching Kotlin contract-version constant, parity test, typed `Invalid<Contract>SchemaError`, classpath copy task, and validator loud-fail when the bundled schema or emitted/consumed payload is missing, malformed, or incompatible.
3. A read-only `skill-bill work status --repo-root <path> --format json` command emits exactly one schema-valid snapshot and provides stable machine-readable exit/error semantics.
4. The payload includes canonical repository identity, optional issue/workflow identity, workflow family, lifecycle state, current step identifier and user-facing label, optional completed/total units, authoritative goal/work `started_at`, optional current-subtask identity and `subtask_started_at`, authoritative update time, freshness classification, summary, and typed problem details.
5. Repository matching and multiple-work precedence are centralized in the runtime application layer, deterministic, documented, and covered for concurrently active, paused, blocked, failed, and recently terminal work.
6. Prose feature tasks, runtime feature tasks, verification workflows, and decomposed goals are projected through their existing authorities into the same IDE model without copying SQLite row shapes or accepting stale child state over an authoritative goal projection.
7. Missing repository identity, an absent Skill Bill database, no matching work, incompatible records, and invalid repository input remain distinct typed outcomes; normal idle/unavailable states do not produce stack traces or human-only output on stdout.
8. The command performs no workflow transition, manifest rewrite, lease acquisition, telemetry mutation, or database write.
9. Goal/work and current-subtask start timestamps come from durable runtime state, remain stable across status polls and process restarts, and are absent only when the corresponding scope does not exist or legacy state cannot provide them; the runtime does not synthesize them from `updated_at`.
10. Golden and application tests lock schema-valid JSON, selection precedence, repository isolation, lifecycle mapping, progress mapping, stable goal/work and subtask start timestamps, legacy timestamp absence, freshness boundaries, and typed failures.

## Non-Goals

- IntelliJ plugin code or UI.
- A streaming or JSONL event command.
- Workflow mutation commands.
- Exposing raw workflow snapshots, phase artifacts, prompts, diffs, or database paths.

## Dependency Notes

- No subtask dependency.
- Subtasks 2 and 3 consume the contract and fixtures produced here.

## Validation Strategy

- Run focused runtime-contracts, runtime-application, runtime-infra-fs, runtime-cli, and
  architecture tests.
- Validate every golden fixture against the bundled schema.
- Run the command against fixture databases containing each supported workflow family,
  multiple repositories, no work, and malformed/incompatible records.
- Run `git diff --check`.

## Next Path

Proceed to subtask 2 to build the IntelliJ consumer around the stable status port.
