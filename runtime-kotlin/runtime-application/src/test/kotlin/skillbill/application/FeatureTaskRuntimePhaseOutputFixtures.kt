package skillbill.application

// SKILL-140 Subtask 3 (task-6): the canned phase-output fixture corpus, extracted verbatim from
// FeatureTaskRuntimeRunnerTest so a single parity test (PhaseOutputFixtureParityTest) can enumerate
// every fixture with a stable identifier. Runner, goal-runner, and projection suites resolve these
// top-level functions by package, so the move needs no import changes.

// An implement output that completes WITHOUT a reconciliation report. Implement no longer owes
// mutating-reconciliation; value is required, legacy sibling keys are tolerated (AC-003/AC-013).
internal const val REVIEW_FIX_BLOCKER_FINDING_ID = "F-001"

internal var harnessPendingVerifyFindingIds: List<String> = emptyList()

internal fun harnessReviewDriverSyncingPendingVerifyFindings(
  delegate: skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver,
): skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver =
  skillbill.application.featuretask.FeatureTaskRuntimeReviewDriver { request ->
    val result = delegate.run(request)
    harnessPendingVerifyFindingIds = result.mergeResult.findings.map { it.fNumber }
    result
  }

internal fun verifyFindingsPhaseOutput(
  verifiedFindingIds: List<String> = harnessPendingVerifyFindingIds,
): skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput =
  skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutput(
    "verify_findings",
    1,
    verifyFindingsOutput(verifiedFindingIds),
  )

internal fun verifyFindingsOutputForFindingIds(vararg findingIds: String): String =
  verifyFindingsOutput(findingIds.toList())

internal fun verifyFindingsOutput(verifiedFindingIds: List<String> = harnessPendingVerifyFindingIds): String {
  val dispositions = verifiedFindingIds.joinToString(",") { findingId ->
    """{"finding_id":"$findingId","disposition":"verified","reason":"Matches spec intent AC-002.",""" +
      """"severity":"blocker","location":"Foo.kt:1","message":"Foo.kt leaks a connection in the error path",""" +
      """"boundary_context_unavailable":true}"""
  }
  val verdict = if (verifiedFindingIds.isEmpty()) "no_findings_verified" else "findings_verified"
  val dispositionsJson = if (dispositions.isEmpty()) "[]" else "[$dispositions]"
  return """
  {
    "contract_version": "0.4",
    "phase_id": "verify_findings",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": {"finding_dispositions": $dispositionsJson}
  }
  """.trimIndent()
}

internal val IMPLEMENT_NO_RECONCILE_OUTPUT: String = """
  {
    "contract_version": "0.4",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {
      "value": "Implement prose without a reconciliation report.",
      "changed_files": ["src/Foo.kt"]
    }
  }
""".trimIndent()

// A schema-valid plan output carrying a top-level `verdict` wire string the transition function reads.
// produced_outputs carries the declared executable_plan projection: a completed plan owes the shape
// its consumer parses, and the producer gate rejects it otherwise (SKILL-140 Subtask 1).
internal fun verdictPlanOutput(verdict: String): String = """
  {
    "contract_version": "0.4",
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
internal val FINALISED_COMMIT_PUSH_OUTPUT: String = """
  {
    "contract_version": "0.4",
    "phase_id": "commit_push",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${commitPushProducedOutputs(commitSha = "commit-runtime-1")}
  }
""".trimIndent()

// The runtime, not the agent, supplies commit_sha: the agent contributes the outcome message and the
// enumerated path set, and finalisation writes the post-amend sha back into this container. Both the
// pre- and post-finalisation fixtures are built here so the sha lands inside commit_push_result by
// construction; deriving one from the other by text substitution let a template rename yield a fixture
// that silently no longer carried the finalised shape.
internal fun commitPushProducedOutputs(
  commitSha: String? = null,
  changedPaths: List<String> = listOf("src/Foo.kt"),
): String {
  val sha = commitSha?.let { """"commit_sha":"$it",""" } ?: ""
  val pathsJson = changedPaths.joinToString(",") { "\"$it\"" }
  return """{"commit_push_result":{
    $sha
    "message":"SKILL-65: runtime feature-task parity",
    "changed_paths":[$pathsJson],
    "branch":"feat/SKILL-65-runtime-feature-task-parity",
    "base_branch":"main",
    "pushed":true
  }}
  """.trimIndent()
}

internal fun validJsonOutput(phaseId: String, commitPushChangedPaths: List<String>? = null): String {
  if (phaseId == "verify_findings") {
    return verifyFindingsOutput()
  }
  if (phaseId == "audit") {
    return """
    {
      "contract_version": "0.4",
      "phase_id": "audit",
      "status": "completed",
      "summary": "Phase produced a validated output.",
      "verdict": "satisfied",
      "produced_outputs": ${validProducedOutputs(phaseId, commitPushChangedPaths)}
    }
    """.trimIndent()
  }
  return """
  {
    "contract_version": "0.4",
    "phase_id": "$phaseId",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${validProducedOutputs(phaseId, commitPushChangedPaths)}
  }
  """.trimIndent()
}

internal fun validJsonOutputForGitPhase(phaseId: String, git: RecordingWorkflowGitOperations): String = validJsonOutput(
  phaseId,
  commitPushChangedPaths = if (phaseId == "commit_push" && git.ownedPathsValue.isNotEmpty()) {
    git.ownedPathsValue
  } else {
    null
  },
)

@Suppress("LongMethod")
internal fun validProducedOutputs(phaseId: String, commitPushChangedPaths: List<String>? = null): String =
  when (phaseId) {
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
    "commit_push" -> commitPushProducedOutputs(
      commitSha = null,
      changedPaths = commitPushChangedPaths ?: listOf("src/Foo.kt"),
    )
    "preplan" ->
      """{
      "value":"Fixture preplan prose for downstream plan."
    }
      """.trimIndent()
    "plan" ->
      """{
      "value":"Fixture plan prose for downstream implement and audit."
    }
      """.trimIndent()
    "implement" ->
      """{
      "value":"Fixture implement prose for downstream audit."
    }
      """.trimIndent()
    "implement_fix" ->
      """{
      "repair_receipt": {
        "contract_version": "0.1",
        "entries": [{
          "finding_id": "F-001",
          "severity": "blocker",
          "label": "Foo",
          "text": "Foo.kt leaks a connection in the error path",
          "outcome": "addressed",
          "constructs": [{"symbol": "Foo.member", "file": "Foo.kt"}],
          "intent": "close the finding at Foo.member"
        }]
      },
      "reconciled_state": {"reconciled": true, "evidence": "Fixture tree at target state."}
    }
      """.trimIndent()
    "review" -> """{"findings": []}"""
    "audit" -> """{"gaps": []}"""
    "verify_findings" -> """{"finding_dispositions": []}"""
    else -> """{"tasks":["task-1"]}"""
  }

// SKILL-140 Subtask 3 (task-6/task-7): the enumerable parity corpus. Each entry carries a stable id
// used verbatim in parity-failure messages.
internal data class PhaseOutputFixture(
  val id: String,
  val phaseId: String,
  val producedOutputs: String,
)

internal val PLANNING_PROJECTION_FIXTURES: List<PhaseOutputFixture> = emptyList()

internal val PLANNING_PROJECTION_EXEMPT_PHASES: Set<String> =
  setOf(
    "preplan",
    "plan",
    "implement",
    "review",
    "audit",
    "verify_findings",
    "implement_fix",
    "commit_push",
  )

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
    """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"$NESTED_VERDICT_SENTINEL",""" +
      """"produced_outputs":{"gaps":[],"verdict":"satisfied"}"""

  /** Same nested-verdict defect with balanced braces (schema-only rejection). */
  fun nestedVerdictComplete(): String =
    """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"$NESTED_VERDICT_SENTINEL",""" +
      """"produced_outputs":{"gaps":[],"verdict":"satisfied"}}"""

  /** Conservative flow-YAML twin of [nestedVerdictComplete]. */
  fun nestedVerdictConservativeYaml(): String =
    "{contract_version: \"0.4\", phase_id: \"audit\", status: \"completed\", " +
      "summary: \"$YAML_NESTED_SENTINEL\", produced_outputs: {gaps: [], verdict: \"satisfied\"}}"

  /** An unmet-criterion entry carrying a key the closed schema does not define. */
  fun invalidCriterionShape(): String =
    """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"$OBSERVATION_SENTINEL",""" +
      """"verdict":"gaps_found","produced_outputs":{"gaps":[{"criterion":"AC-001",""" +
      """"note":"the behavior is absent","severity":"blocker"}]}}"""

  fun correctedSatisfied(): String =
    """{"contract_version":"0.4","phase_id":"audit","status":"completed","summary":"criteria met",""" +
      """"verdict":"satisfied","produced_outputs":{"gaps":[],"non_blocking_findings":[]}}"""

  /** Block-style YAML that must not receive guessed structural repair. */
  fun unsupportedBlockYaml(): String = """
      contract_version: "0.4"
      phase_id: "audit"
      status: "completed"
      summary: "$UNSUPPORTED_YAML_SENTINEL"
      verdict: "satisfied"
      produced_outputs:
        gaps: []
  """.trimIndent()
}
