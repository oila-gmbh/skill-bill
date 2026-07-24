package skillbill.application

// SKILL-140 Subtask 3 (task-6): the canned phase-output fixture corpus, extracted verbatim from
// FeatureTaskRuntimeRunnerTest so a single parity test (PhaseOutputFixtureParityTest) can enumerate
// every fixture with a stable identifier. Runner, goal-runner, and projection suites resolve these
// top-level functions by package, so the move needs no import changes.

// An implement output that completes WITHOUT the reconciliation report, so the runtime's
// mutating-phase reconciliation gate must reject it (silent skip fails the gate loudly). This is a
// NEGATIVE fixture: it is deliberately invalid and is excluded from the parity corpus by design.
internal val IMPLEMENT_NO_RECONCILE_OUTPUT: String = """
  {
    "contract_version": "0.2",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {"changed_files": ["src/Foo.kt"]}
  }
""".trimIndent()

// A schema-valid plan output carrying a top-level `verdict` wire string the transition function reads.
// produced_outputs carries the declared executable_plan projection: a completed plan owes the shape
// its consumer parses, and the producer gate rejects it otherwise (SKILL-140 Subtask 1).
internal fun verdictPlanOutput(verdict: String): String = """
  {
    "contract_version": "0.2",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": ${validProducedOutputs("plan")}
  }
""".trimIndent()

internal fun validJsonOutput(phaseId: String): String = """
  {
    "contract_version": "0.2",
    "phase_id": "$phaseId",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${validProducedOutputs(phaseId)}
  }
""".trimIndent()

internal fun validProducedOutputs(phaseId: String): String = when (phaseId) {
  "commit_push" -> """{"commit_push_result": {"commit_sha": "commit-runtime-1"}}"""
  // preplan and plan feed the bounded planning projections on the preplan->plan and plan->implement
  // (and plan->audit commitment) edges, so their fixture payloads carry the declared projection shape.
  "preplan" ->
    """{
      "projection_kind":"preplanning_digest",
      "contract_version":"0.1",
      "affected_boundaries":["runtime-application"],
      "risks":["Fixture risk."],
      "rollout":{"flag_required":false,"flag_pattern":"none","notes":"No flag needed."},
      "validation_strategy":["Focused runtime tests."]
    }
    """.trimIndent()
  "plan" ->
    """{
      "projection_kind":"executable_plan",
      "contract_version":"0.1",
      "mode":"direct",
      "tasks":[{
        "task_id":"task-1",
        "description":"Fixture task.",
        "criterion_refs":["AC-001"],
        "target_paths_or_symbols":["src/Foo.kt"],
        "test_obligations":["Focused test."]
      }],
      "validation_strategy":["Focused runtime tests."]
    }
    """.trimIndent()
  // Mutating phases must carry the reconciliation report or the runtime's reconciliation gate
  // rejects the output (SKILL-85 Subtask 3). implement_fix is mutating too (SKILL-85 Subtask 4).
  // implement additionally feeds audit's bounded implementation-receipt projection.
  //
  // SKILL-140 Subtask 3 (task-8) decision: the implementation_receipt variant is
  // additionalProperties:false and declares `changed_paths` (normalized repo-relative paths) plus the
  // governed co-residents `reconciled_state` and `repair_item_results`. It does NOT declare
  // `changed_files`. The prior fixture carried a redundant `changed_files` list that duplicated
  // `changed_paths` and is rejected by the real Draft 2020-12 validator. We remove the undeclared key
  // rather than widen implementation_receipt to admit it: widening the schema to keep a duplicate wire
  // field is the rejected alternative (schema-shape changes are a stated non-goal, and `changed_paths`
  // already carries the path list this variant delivers).
  "implement", "implement_fix" ->
    """{
      "projection_kind":"implementation_receipt",
      "contract_version":"0.1",
      "completed_task_ids":["task-1"],
      "changed_paths":["src/Foo.kt"],
      "tests_executed":[{"name":"FooTest","outcome":"passed"}],
      "reconciliation_evidence":{"reconciled":true,"evidence":"Fixture tree at target state."},
      "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
      "reconciled_state":{"reconciled":true},
      "repair_item_results":[{
        "repair_item_id":"ac-002-gap-1-item-1",
        "outcome":"fixed",
        "changed_paths_or_symbols":["src/Foo.kt"],
        "executed_verification":["Focused test passed."],
        "result_evidence":{"observation":"fix_verified","artifact_ref":"runtime-kotlin","check_ref":"AC-002"}
      }]
    }
    """.trimIndent()
  // A clean review must emit a verification signal or the review gate blocks (SKILL-85 Subtask 4):
  // an explicit empty findings array affirms "no blocking findings" and advances.
  "review" -> """{"findings": []}"""
  // A clean audit must emit a verification signal or the audit gate blocks (SKILL-85 Subtask 5):
  // an explicit empty unmet_criteria array affirms "every acceptance criterion is met" and advances.
  "audit" -> """{"unmet_criteria": []}"""
  else -> """{"tasks": ["task-1"]}"""
}

// SKILL-140 Subtask 3 (task-6/task-7): the enumerable parity corpus. Each entry carries a stable id
// used verbatim in parity-failure messages.
internal data class PhaseOutputFixture(
  val id: String,
  val phaseId: String,
  val producedOutputs: String,
)

// The producing phases own a bounded planning projection, so their produced_outputs must validate
// against the canonical planning-projections schema. `implement_fix` re-enters the implement phase and
// reuses the same implementation_receipt projection, so it is validated too.
internal val PLANNING_PROJECTION_FIXTURES: List<PhaseOutputFixture> =
  listOf("preplan", "plan", "implement", "implement_fix").map { phaseId ->
    PhaseOutputFixture(
      id = "validProducedOutputs:$phaseId",
      phaseId = phaseId,
      producedOutputs = validProducedOutputs(phaseId),
    )
  }

// Explicit, named exemptions (AC-002): these phases carry no planning-projection obligation, so their
// produced_outputs are not validated against the planning-projections schema.
// - review / audit: emit verification signals (findings / unmet_criteria), not a bounded projection.
// - commit_push: emits a commit_push_result, owned by the phase-output contract, not this schema.
internal val PLANNING_PROJECTION_EXEMPT_PHASES: Set<String> = setOf("review", "audit", "commit_push")
