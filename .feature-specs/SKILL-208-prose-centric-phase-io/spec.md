# SKILL-208 — Prose-centric phase I/O for every feature-task phase

## Context

SKILL-207 moved the review boundary off a typed API: the worker's returned text is
the authoritative `output`, claim verification is another phase call over that
string, and register shape is best-effort guidance. `AgentPhaseInput(input,
requestedAction)` and `AgentPhaseOutput(output)` exist in `runtime-domain` for
exactly that pattern, and SKILL-207's Next Path names the rest of the pipeline.

The other eleven feature-task phases still treat agent text as a machine API.
Every phase prompt ends with "Required final output (validated schema gate)" and
demands one JSON object with `contract_version`, `phase_id`, `status`, `summary`,
and a `produced_outputs` map, closed at the root by
`additionalProperties: false`. Layers of machinery exist only to force agent
prose into that shape: per-phase constraints in
`feature-task-runtime-phase-output-schema.yaml`, the repair-receipt coverage and
entry rules, `FeatureTaskRuntimePhaseOutputStructuralRepair`,
`FeatureTaskRuntimePhaseOutputDuplicateKeyMerge`,
`FeatureTaskRuntimeSchemaFailureCorrections`, the retry skeleton in
`FeatureTaskRuntimePhasePromptComposer`, `PHASE_PROJECTION_MATRIX`'s
declared-field filtering, and the planning projection contracts with
`producerProjectionGateReason` and its `RECORD_REJECTED` regeneration edges.

The durable record says how much this costs and where. Across 517 recorded
runtime sessions, 164 blocked; shape and format gates are the largest single
addressable bucket at 51 of those blocks, ahead of agent process failure (37),
agent self-reported terminal output (20), semantic non-convergence (17), and
path-ownership or cross-issue refusals (15). The 358 rows in
`rejected_output_diagnostics` are far more concentrated: 278 of them — 78%, and
87% of the last month — are `implement_fix`, split between 172 repair-receipt
coverage rejections and 106 phase-output-schema rejections. The specific causes
are not disagreements about meaning. They are `text` exceeding `maxLength: 256`,
`constructs[].symbol` failing its regex, `finding_ref` rejected as an unknown
property, and the coverage rule "must include one entry for every finding
carried into this round". `audit` contributes 16 rows and `verify_findings` 6.

Two conclusions follow, and they set the order of this work. Most of the churn is
one phase policing the *inside* of a payload whose meaning nothing disputed, and
that relief needs neither verdict derivation nor a new fact owner nor a record
migration. Everything the flip itself requires — settlement, routing, and the
facts the runtime already holds — is a smaller and more delicate problem that
should not be carrying the relief on its back.

## Intended Outcome

Every feature-task phase is a governed agent call with string I/O. The runtime
hands a phase `input` (upstream phase output text plus the context it is entitled
to) and `requestedAction` (what to do with that input), and the phase settles
with `output`: the agent's returned text, persisted and forwarded verbatim under
budget.

Control flow stops depending on the shape of what the agent produced. Settlement
and every routing verdict come from facts the runtime holds plus bounded reading
over a closed vocabulary the runtime already names. Governed artifacts stay
typed and travel in a runtime-owned sidecar on the durable phase record rather
than being recovered from prose. Kotlin governs the phases; the agent owns the
meaning.

## Design Rules

These four rules decide every case below. They are stated once here because the
per-subtask criteria are applications of them, not independent judgements.

- **R1 — Mint over read.** Where the runtime can observe a fact, it mints it and
  ignores any agent claim. This is not new: `gateRepairNoOutputSchemaDirective`
  already runs validate and build repair turns in prose with no phase-output
  schema, and `FeatureTaskRuntimeValidationGateCoordinator` and
  `FeatureTaskRuntimeBuildGateCoordinator` mint the typed receipt from
  runtime-measured evidence. That shipped pattern — runtime executes, runtime
  mints, agent writes prose — is the model this feature generalizes. Reading
  prose is the fallback for facts the runtime cannot observe, never the first
  choice.
- **R2 — Closed vocabulary only.** Prose derivation is admissible only where the
  runtime already holds the candidate set: a verdict enum, the delivered plan
  task ids, the carried finding ids. An id either appears in the prose or it does
  not, which is a membership test rather than natural-language parsing. Deriving
  a value from an open vocabulary — a commit subject, a file path, a severity for
  a finding the runtime never saw — is out of scope for derivation and handled by
  R1 or R3.
- **R3 — Governed artifacts stay typed in a runtime-owned sidecar.** The durable
  phase record carries the prose `output` plus a structured sidecar the runtime
  owns. The runtime writes the sidecar from its own evidence. The only
  agent-authored sidecar entries are explicitly delimited scalars or blocks for
  governed artifacts the runtime cannot observe, and each keeps its existing
  schema and loud-fail. There are exactly two: the `commit_push` commit subject
  and a decompose outcome's decomposition package.
- **R4 — Indecision never advances.** A derivation that cannot decide blocks or
  re-asks. It never defaults to the advancing verdict and never defaults to
  `completed`. Every re-ask, degradation, and block emits a record per
  `docs/observability-policy.md`.

## Acceptance Criteria

1. Payload shape policing stops blocking a phase: repair-receipt entry rules and
   coverage, per-entry length and pattern caps, key-alias and key-placement
   rules, and duplicate-key handling become diagnostics that record instead of
   deciding a phase's fate. Absent, blank, or unparseable output stays a loud
   failure.
2. In-flight workflows blocked under the retired payload constraints resume
   without a hard reset, and no durable record already written becomes invalid.
3. Facts the runtime can observe are minted by the runtime and never requested
   from the agent (R1): review run id and the review finding set from the
   runtime's own review import, commit-focused accounting from runtime-owned
   review execution state, repository checkpoints and `changed_paths` from the
   checkpoint resolver and `repositoryOwnedPaths`, `gate_run_count` and
   `gate_runs` from the gate coordinators. An agent-supplied value for any of
   them is ignored rather than validated.
4. Mutating-phase idempotency is decided by comparing the repository against the
   runtime-resolved checkpoint, and the `reconciled_state` requirement leaves the
   schema and the prompt.
5. The SKILL-150 truthful-completion gate survives the flip: obligation closure
   is decided by membership of the delivered plan task ids and carried repair
   item ids in the phase's returned text (R2), unmatched ids stay open, and a
   phase claiming completion with open obligations still fails.
6. Settlement (`completed` / `blocked` / `failed`) and its retry disposition are
   derived by one runtime-owned reader, separately contracted and separately
   tested from the routing verdicts, with an indecisive result blocking rather
   than advancing (R4).
7. Every routing verdict — `audit` satisfied vs. gaps_found, `verify_findings`
   findings_verified vs. no_findings_verified, `review` approved vs.
   changes_requested — is derived by that reader over the phase prose combined
   with runtime-held facts, and the existing entry gates and `audit_gap` /
   `review_fix` backward edges route from it with no topology change.
8. Derivation never rewrites, truncates, or substitutes `output`. When it cannot
   decide, the runtime re-asks once with a narrowed `requestedAction` over the
   same `input`; a still-indecisive answer blocks durably naming the phase with
   both outputs preserved and identifies which one is authoritative on resume.
9. Every phase in `FeatureTaskRuntimePhaseWorkflowDefinition` launches through
   the shared `AgentPhaseInput`, and no phase prompt requires a final JSON
   envelope, `produced_outputs`, or `contract_version`. Phase completion records
   the agent's returned text verbatim as `AgentPhaseOutput.output`.
10. The declared-field projection matrix stops filtering the agent handoff: a
    consumer receives its producers' `output` text under the existing handoff
    budgets, and no consumer launch is rejected for a missing declared field.
    Budget truncation emits a record, is visible in the delivered prompt, and
    never removes the region a derivation reads.
11. `commit_push` and a decompose outcome keep their governed typed artifacts in
    the runtime-owned sidecar (R3): a blank commit subject still blocks rather
    than publishing a provisional one, the runtime-captured post-amend sha still
    reaches the decomposition manifest and the goal-continuation outcome as one
    value, and a decompose outcome still lands as a schema-valid decomposition
    package with goal planning still loud-failing on a malformed one.
12. `producerProjectionGateReason` and the planning projection contracts stop
    gating the feature-task phase path while remaining intact for their
    goal-planning callers, and the `RECORD_REJECTED` regeneration edges no longer
    gate a run.
13. Contract versions bump where durable records change, parity tests pin the new
    versions, legacy records are rejected loudly, and the hard-reset path is
    documented in `runtime-kotlin/ARCHITECTURE.md`.
14. Governance surfaces are unchanged: workflow-state contract, decomposition
    manifest, platform-pack manifests, handoff budgets, checkpoint policy,
    evidence-broker binding, telemetry contracts and the records that feed them,
    and runtime-owned commit finalisation.
15. Tests cover: a payload whose entries fail today's shape rules advances with
    its text intact and no hard reset; a verdict stated only in prose routes the
    `audit_gap` and `review_fix` edges correctly; an indecisive verdict re-asks
    once and then blocks with both prose outputs preserved; a completion claim
    with an unmatched plan task id still fails; a blank commit subject still
    blocks.

## Constraints

- No new gate may become the path that deletes a phase result. Diagnostics and
  parsing may stay for observability; they never own the handoff.
- Derivation is runtime-owned reading over a closed vocabulary (R2). Do not add a
  second LLM format-repair pass, and do not re-ask more than the one bounded
  turn.
- Prose forwarding stays budgeted. Truncation under a handoff budget emits a
  record, is visible in the prompt, and is bounded so it cannot consume the
  region a derivation reads.
- Loud-fail stays for infrastructure and governance failures: missing native
  worker, pack or contract errors, launch capability, branch identity,
  dirty-tree and checkpoint mismatches, absent output, blank commit subject.
- Mutating-phase idempotency stays enforced, from repository evidence rather than
  a producer claim; plan-obligation closure stays enforced by R2 membership.
- Goal planning and decomposition keep their governed artifacts and their
  existing gate functions.
- SKILL-207's review boundary stays as shipped; extend it to the remaining phases
  rather than re-deciding it.

## Non-Goals

- Prose for governed artifacts: workflow state, decomposition manifests,
  platform-pack manifests, and telemetry payloads stay typed.
- Changing the phase DAG, loop caps, ceremony scaling, quality-gate routing, or
  pack selection.
- Extending the envelope to the goal-planning sweep or the standalone
  `bill-feature-verify` and PR paths.
- Open-vocabulary extraction from prose. Anything R2 excludes is solved by
  minting (R1) or by the typed sidecar (R3), not by a better parser.
- Perfect natural-language parsing. The reader is bounded and has an explicit
  indecisive path.
- In-place migration of in-flight workflows past subtask 1; a hard reset is the
  documented path for the flip itself.
- Reworking telemetry schemas, review importing, or provider accounting beyond
  reading the finding set the import already owns.

## Decomposition Rationale

Five subtasks. The ordering is set by two things the durable record makes
concrete: relief should not wait on the flip, and the flip should not carry
anything it does not have to.

1. **Demote payload shape policing to diagnostics** — the rules that police the
   inside of a payload stop deciding a phase's fate. This is 278 of the 358
   recorded rejections and it changes no record shape, no routing, and no
   contract, so blocked runs recover without a reset. It ships alone and first,
   and it is revertible.
2. **Runtime-held facts and runtime-minted evidence** — R1 applied: review run
   id and finding set, commit-focused accounting, checkpoints, `changed_paths`,
   and gate runs come from runtime state, and mutating-phase idempotency is
   decided from the checkpoint. Independent of 1.
3. **Derive settlement and verdicts** — R2 and R4 applied: one reader, with
   settlement contracted separately from routing, and the SKILL-150 completion
   gate re-based on id membership.
4. **Prose phase result and string handoff** — the envelope flip, including the
   typed sidecar for the commit subject and the decompose package (R3).
5. **Retire shape policing and align contracts** — delete what nothing reads and
   align the documentation.

The dependency chain is what forces the order, not layering. Subtask 3 depends on
2 because deriving a review verdict without a `findings` array requires the
finding set to already come from the import. Subtask 4 depends on 3 because
routing and settlement must work from prose before the envelope can go, or the
remediation edges become unroutable. Subtask 5 follows 4 because deletion is only
safe once nothing consults the retired shapes. The flip stays one subtask because
its producer and consumer sides cannot ship apart.

Subtask 1 exists as its own commit for the reason the sizing rules give: it
stands alone. Every later subtask changes what the runtime reads or how records
are shaped; this one only stops a diagnostic from blocking, which is why it can
ship before the migration and be reverted without stranding anything.

Phase-by-phase waves were rejected for the flip itself. Migrating `preplan` while
`audit` still owes a typed envelope needs a temporary per-phase prose-mode switch
and two record paths, and that scaffolding costs more than the smaller commits
are worth.

## Next Path

Extend the same envelope to the goal-planning sweep and the standalone
`bill-feature-verify` and PR paths, then add telemetry on derivation re-asks so
the indecisive rate is measured rather than assumed.
