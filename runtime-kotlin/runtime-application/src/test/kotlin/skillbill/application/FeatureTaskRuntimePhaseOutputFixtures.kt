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

// The commit_push record as it exists AFTER runtime finalisation: the agent contributed message and
// changed_paths, and the runtime wrote the post-amend commit_sha back into the same container. This is
// what the `pr` consumer projection reads, so any suite that assembles a pr briefing from static
// records must use this rather than the pre-finalisation agent payload.
internal val FINALISED_COMMIT_PUSH_OUTPUT: String = validJsonOutput("commit_push")
  .replace("\"message\":", "\"commit_sha\":\"commit-runtime-1\",\n      \"message\":")

internal fun validJsonOutput(phaseId: String): String = """
  {
    "contract_version": "0.2",
    "phase_id": "$phaseId",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${validProducedOutputs(phaseId)}
  }
""".trimIndent()

@Suppress("LongMethod")
internal fun validProducedOutputs(phaseId: String): String = when (phaseId) {
  "validate" ->
    """{"validation_result":{
      "validation_status":"passed",
      "checks":["FooTest"],
      "repository_checkpoint":{"fingerprint":"fixture-checkpoint-1"},
      "gate_run_count":1,
      "gate_runs":[{"duration_ms":1,"outcome":"passed","cache_mode":"forced_full","executed_work_units":1}]
    }}
    """.trimIndent()
  "write_history" ->
    """{"history_result":{"changed_paths":["agent/history.md"],"decisions_recorded":[]}}"""
  // The runtime, not the agent, supplies commit_sha: the agent contributes the outcome message and
  // the enumerated path set, and finalisation writes the post-amend sha back into this container.
  "commit_push" ->
    """{"commit_push_result":{
      "message":"SKILL-65: runtime feature-task parity",
      "changed_paths":["src/Runtime.kt"],
      "branch":"feat/SKILL-65-runtime-feature-task-parity",
      "base_branch":"main",
      "pushed":true
    }}
    """.trimIndent()
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
  "plan_fix" ->
    """{
      "repair_plan":{
        "contract_version":"0.1",
        "round_number":1,
        "entries":[{
          "finding_ref":"F-001",
          "root_cause":"Fixture root cause for the carried finding.",
          "minimal_change":"Fixture minimal change closing the carried finding.",
          "classification":"local_patch_site"
        }]
      }
    }
    """
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
      "deferred_repair_item_ids":[],
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
  else -> """{"tasks":["task-1"]}"""
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

/**
 * SKILL-187 subtask 3: synthetic audit envelopes for repair-context conformance. Sentinels only —
 * never real rejected payloads, secrets, prompts, or database paths.
 */
internal object Skill187SyntheticAuditResponses {
  const val NESTED_VERDICT_SENTINEL: String = "SKILL187-NESTED-VERDICT"
  const val OBSERVATION_SENTINEL: String = "SKILL187-BAD-OBSERVATION"
  const val ARTIFACT_SENTINEL: String = "SKILL187-OVERSIZE-ARTIFACT"
  const val YAML_NESTED_SENTINEL: String = "SKILL187-YAML-NESTED"
  const val UNSUPPORTED_YAML_SENTINEL: String = "SKILL187-UNSUPPORTED-YAML"

  /** Missing closing brace; verdict nested under produced_outputs (SKILL-16 shape). */
  fun nestedVerdictMissingDelimiter(): String =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"$NESTED_VERDICT_SENTINEL",""" +
      """"produced_outputs":{"gaps":[],"verdict":"satisfied"}"""

  /** Same nested-verdict defect with balanced braces (schema-only rejection). */
  fun nestedVerdictComplete(): String =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"$NESTED_VERDICT_SENTINEL",""" +
      """"produced_outputs":{"gaps":[],"verdict":"satisfied"}}"""

  /** Conservative flow-YAML twin of [nestedVerdictComplete]. */
  fun nestedVerdictConservativeYaml(): String =
    "{contract_version: \"0.3\", phase_id: \"audit\", status: \"completed\", " +
      "summary: \"$YAML_NESTED_SENTINEL\", produced_outputs: {gaps: [], verdict: \"satisfied\"}}"

  /** Unauthorized observation enum on carried_gap_dispositions (audit wire vocabulary). */
  fun unauthorizedObservation(): String =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"$OBSERVATION_SENTINEL",""" +
      """"verdict":"satisfied","produced_outputs":{"gaps":[],"carried_gap_dispositions":[{""" +
      """"gap_id":"ac-001-gap-1","status":"resolved","evidence":{""" +
      """"observation":"blast_radius_inspected","artifact_ref":"runtime-kotlin","check_ref":"AC-001"}}]}}"""

  /**
   * Compact gap whose expanded audit_repair_plan artifact_ref exceeds the 256-char bound
   * (file 128 + location 256 + separators).
   */
  fun oversizedExpandedArtifactRef(): String {
    val file = "f".repeat(128)
    val location = "L" + "o".repeat(255)
    return """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"$ARTIFACT_SENTINEL",""" +
      """"verdict":"gaps_found","produced_outputs":{"gaps":[{""" +
      """"criterion":"AC-001","severity":"blocker","location":"$location","issue":"Missing behavior.",""" +
      """"fix":"Restore the behavior.","file":"$file"}]}}"""
  }

  fun correctedSatisfied(): String =
    """{"contract_version":"0.3","phase_id":"audit","status":"completed","summary":"criteria met",""" +
      """"verdict":"satisfied","produced_outputs":{"gaps":[],"non_blocking_findings":[]}}"""

  /** Block-style YAML that must not receive guessed structural repair. */
  fun unsupportedBlockYaml(): String = """
      contract_version: "0.3"
      phase_id: "audit"
      status: "completed"
      summary: "$UNSUPPORTED_YAML_SENTINEL"
      verdict: "satisfied"
      produced_outputs:
        gaps: []
  """.trimIndent()
}
