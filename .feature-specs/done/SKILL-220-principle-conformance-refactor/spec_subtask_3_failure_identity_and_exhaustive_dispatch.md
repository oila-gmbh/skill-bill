# SKILL-220 Subtask 3: Failure Identity, Typed Boundaries, Exhaustive Dispatch

## Intended Outcome

Resolve P-04, P-05, P-06, and P-07. Closed sets still fall through `else`;
failure hierarchies pair a sealed result with an enum code with nothing
proving totality; untrusted input throws or is swallowed; capability
vocabularies mix closed enums and open strings without a recorded boundary.

## Scope

### Exhaustive dispatch (P-04)

- Replace residual `else` on sealed types and enums in production code with
  explicit cases. Priority site: sealed `LaunchPreparation` in
  `FeatureTaskRuntimeRunLoop` (`else -> error("Unexpected launch
  preparation/measurement/closed-criterion result.")`).
- Audit every remaining production `else ->`. For each, either make it
  exhaustive or confirm the scrutinee is genuinely open (untrusted JSON,
  external stdout) and record why in one line at the site.
- Remove `else -> error("unreachable")` where a narrowed type makes the
  branch unrepresentable (`ScaffoldCliCommands` and similar).

### Failure identity (P-05)

- Define one shared contract that binds a failure case to its stable code.
  Implement it on existing hierarchies, at least:
  `FeatureTaskRuntimePhaseOutputFailureCode` /
  `FeatureTaskRuntimePhaseOutputValidationResult`,
  `DecompositionManifestValidationFailureCode`,
  `FeatureTaskRuntimeHandoffProjectionFailureKind`,
  and the `ShellContentContractErrors` failure-kind mapping.
- Stop `fromWire` collapsing unknown tokens to `SCHEMA_INVALID`. Unknown
  wire values are a typed violation naming the rejected token.
- Add a conformance test proving, for every hierarchy, that each case
  resolves to exactly one code and each code is produced by exactly one
  case.

### Typed parse boundaries (P-06)

- Convert `GoalRunnerControlStore` durable JSON decode from `error()` /
  `require()` / `IllegalArgumentException` to the typed control-state schema
  error family already used elsewhere.
- Convert handoff and phase-output artifact `fromWire` from
  `IllegalArgumentException` to typed contract failures.
- Replace `runCatching` used as a decode classifier (observability
  `fromWire` → null, YAML caps/exclusions, agent stdout, validation-gate XML
  → `emptyList()`) with an explicit typed parse. Malformed gate XML is a
  typed finding or typed failure, not a silent empty set.
- Rethrow `CancellationException` before any broad `catch`.

### Capability vocabulary (P-07)

- Decide whether `fallbackCapabilities`, native-agent `"entrypoint"` slots,
  and `AgentRunIdlePolicy` stay open strings or become closed enums.
- Both outcomes are acceptable. Record the decision in the relevant area
  `../../../agent/decisions.md`. If they stay open, name the single conversion site
  from any closed vocabulary. If they close, unknown operator input produces
  a typed failure, covered by one test.

## Applicable Principles

- Prefer exhaustive `when` over `else`. The compiler is the checklist.
- Expected failures cross a boundary as typed results; exceptions are for
  broken invariants.
- Convert free-form strings to enums at the earliest boundary that can
  reject bad input.
- Collapse an unmapped throwable into an explicit `Unknown`-style case; never
  a success-shaped fallback and never a bare `false`.
- Map once per boundary in one helper, not with `try`/`catch` at every call
  site.

## Acceptance Criteria

1. No `when` over a sealed hierarchy or enum in production code uses `else`,
   except where the scrutinee is genuinely open and the site says so in one
   line.
2. Adding a variant to `LaunchPreparation`, to any `*FailureCode` hierarchy
   in scope, or to any sealed command/result this subtask touches produces a
   compile error rather than a runtime `else` branch.
3. Every in-scope `*Failure` hierarchy implements the shared case-to-code
   contract; no hierarchy resolves its code through a free-standing `when` a
   caller must maintain.
4. A conformance test proves the mapping is total and injective for every
   in-scope hierarchy, and fails when a case is added without a code or a
   code is orphaned.
5. Every existing error code string is unchanged.
6. Untrusted durable JSON, handoff wire, phase-output wire, YAML capability
   / exclusion files, and validation-gate XML produce typed failures (or
   typed findings) at the parse boundary; no `error()`, `require()`, or
   `runCatching` → empty/null success remains on those paths.
7. A written decision names the authoritative vocabulary for fallback
   capabilities, entrypoint slots, and idle policy, and states why each is
   open or closed.
8. Two tests beyond the conformance test: unrecognized wire failure-code
   token returns a typed violation rather than `SCHEMA_INVALID`; malformed
   control-state JSON returns a typed schema error rather than
   `IllegalArgumentException`. Both catch a regression the current suite
   cannot.
9. `../../../scripts/validate` passes.

## Failure And Recovery Behavior

- Unrecognized wire token: typed contract violation naming the value.
- Malformed durable JSON / YAML: typed schema error with recovery pointing
  at the contract version.
- Defect inside a factory after a successful typed parse: propagates as a
  defect, not as bad agent output.
- `CapabilityUnsupported` / equivalent still names every missing key.

## Non-Goals

- Merging distinct failure hierarchies into one.
- Converting genuinely open maps (undocumented JSON objects at an
  `@OpenBoundaryMap` site) into enums.
- File moves (subtask 1) or FQN rewrites (subtask 2).
- Oversized-file splits (subtasks 4–6), except incidental `when` edits
  inside those files.

## Dependency Notes

Runs after subtasks 1 and 2. Lands before oversized splits so extracted
collaborators inherit exhaustive dispatch. Subtask 7 reuses the conformance
test this subtask adds.

## Validation Strategy

`../../../scripts/validate`. For criterion 2, add a variant locally, confirm the
build fails at each intended site, then revert. Prove the conformance test
by removing a code mapping locally and confirming the failure, then revert.

## Next Path

Subtask 4 decomposes the feature-task runtime oversized units.
