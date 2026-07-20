package skillbill.contracts.workflow

import skillbill.error.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.workflow.taskruntime.model.AUDIT_REPAIR_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAuditGap
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeEvidence
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepairItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The durable-text rule is enforced twice: as `compactSummary` in
 * feature-task-runtime-audit-repair-plan-schema.yaml and as `requireDurableText` in
 * FeatureTaskRuntimeAuditRepairModels.kt. They drifted once — the schema kept rejecting the
 * punctuation the domain had been relaxed to allow, so the domain change had no effect at the
 * seam that actually runs first. These cases pin both layers to the same verdict.
 */
class FeatureTaskRuntimeAuditRepairSchemaParityTest {
  private val accepted = listOf(
    "GoalSubtaskReviewSummaryReducer.fromOutput collapses each group via minByOrNull(::severityRank).",
    "labelFor returns List<String> instead of the sanitized stem.",
    "The gate reads results[0].codeReviewMode == inline.",
    "Run ./gradlew :runtime-domain:test --tests=*AuditRepairModelsTest*",
  )

  private val rejected = listOf(
    "{\"tool\":\"result\",\"output\":\"raw\"}",
    "@@ -1,4 +1,6 @@ fun leaked()",
    "diff --git a/src/Foo.kt b/src/Foo.kt",
    "assistant: I inspected the reducer",
    "Diagnosis with a `fenced` quote",
    "Diagnosis with a\nline break",
  )

  @Test
  fun `schema and domain agree on every durable text case`() {
    (accepted.map { it to true } + rejected.map { it to false }).forEach { (value, expectedAccepted) ->
      assertEquals(
        expectedAccepted,
        schemaAccepts(value),
        "JSON Schema verdict for \"$value\"",
      )
      assertEquals(
        expectedAccepted,
        domainAccepts(value),
        "Kotlin domain verdict for \"$value\"",
      )
    }
  }

  // The evidence-reference bound drifted the same way `compactSummary` once did: the schema capped
  // artifact_ref and check_ref at 256 characters while the domain had no length rule at all, so the cap
  // vanished on every seam the schema never sees. Pinning both layers is what stops that recurring.
  @Test
  fun `schema and domain agree on every evidence reference bound`() {
    val longestAcceptedArtifactRef = "a".repeat(256)
    val longestAcceptedCheckRef = "a".repeat(252) + "Test"

    listOf(
      Triple(longestAcceptedArtifactRef, longestAcceptedCheckRef, true),
      Triple("a".repeat(257), longestAcceptedCheckRef, false),
      Triple(longestAcceptedArtifactRef, "a".repeat(253) + "Test", false),
    ).forEach { (artifactRef, checkRef, expectedAccepted) ->
      assertEquals(
        expectedAccepted,
        schemaAcceptsEvidence(artifactRef, checkRef),
        "JSON Schema verdict for artifact_ref=${artifactRef.length} check_ref=${checkRef.length} chars",
      )
      assertEquals(
        expectedAccepted,
        domainAcceptsEvidence(artifactRef, checkRef),
        "Kotlin domain verdict for artifact_ref=${artifactRef.length} check_ref=${checkRef.length} chars",
      )
    }
  }

  private fun schemaAcceptsEvidence(artifactRef: String, checkRef: String): Boolean = runCatching {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validate(
      auditPhaseOutput("A bounded diagnosis.", artifactRef, checkRef),
      "audit",
    )
  }.fold(
    onSuccess = { true },
    onFailure = { error ->
      if (error is InvalidFeatureTaskRuntimePhaseOutputSchemaError) false else throw error
    },
  )

  private fun domainAcceptsEvidence(artifactRef: String, checkRef: String): Boolean = runCatching {
    FeatureTaskRuntimeEvidence(
      FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT,
      artifactRef,
      checkRef,
    )
  }.fold(
    onSuccess = { true },
    onFailure = { error -> if (error is IllegalArgumentException) false else throw error },
  )

  private fun schemaAccepts(diagnosis: String): Boolean = runCatching {
    FeatureTaskRuntimePhaseOutputSchemaValidator.validate(auditPhaseOutput(diagnosis), "audit")
  }.fold(
    onSuccess = { true },
    onFailure = { error ->
      if (error is InvalidFeatureTaskRuntimePhaseOutputSchemaError) false else throw error
    },
  )

  private fun domainAccepts(diagnosis: String): Boolean = runCatching { auditGap(diagnosis) }.fold(
    onSuccess = { true },
    onFailure = { error -> if (error is IllegalArgumentException) false else throw error },
  )

  private fun auditGap(diagnosis: String) = FeatureTaskRuntimeAuditGap(
    gapId = "ac-128-gap-1",
    acceptanceCriterionRef = "AC-128",
    acceptanceCriterionText = "Integration coverage exists.",
    failureEvidence = FeatureTaskRuntimeEvidence(
      FeatureTaskRuntimeEvidence.Observation.REQUIRED_BEHAVIOR_ABSENT,
      "runtime-kotlin/integration",
      "AC-001",
    ),
    diagnosis = diagnosis,
    affectedBoundary = "runtime integration tests",
    repairItems = listOf(
      FeatureTaskRuntimeRepairItem(
        repairItemId = "ac-128-gap-1-item-1",
        intendedOutcome = "The integration scenario verifies the feature.",
        implementationActions = listOf("Add and execute the integration scenario."),
        affectedPathsOrSymbols = listOf("runtime-core/src/test"),
        requiredVerification = listOf("Run the integration test."),
        dependsOn = emptyList(),
      ),
    ),
  )

  private fun auditPhaseOutput(
    diagnosis: String,
    artifactRef: String = "runtime-kotlin/integration",
    checkRef: String = "AC-001",
  ): Map<String, Any?> = mapOf(
    "contract_version" to FEATURE_TASK_RUNTIME_CONTRACT_VERSION,
    "phase_id" to "audit",
    "status" to "completed",
    "summary" to "One criterion remains unmet.",
    "verdict" to "gaps_found",
    "produced_outputs" to mapOf(
      "unmet_criteria" to listOf(
        mapOf(
          "acceptance_criterion_ref" to "AC-128",
          "message" to "Integration coverage is missing.",
        ),
      ),
      "audit_repair_plan" to mapOf(
        "contract_version" to AUDIT_REPAIR_CONTRACT_VERSION,
        "gaps" to listOf(
          mapOf(
            "gap_id" to "ac-128-gap-1",
            "acceptance_criterion_ref" to "AC-128",
            "acceptance_criterion_text" to "Integration coverage exists.",
            "failure_evidence" to mapOf(
              "observation" to "required_behavior_absent",
              "artifact_ref" to artifactRef,
              "check_ref" to checkRef,
            ),
            "diagnosis" to diagnosis,
            "affected_boundary" to "runtime integration tests",
            "repair_items" to listOf(
              mapOf(
                "repair_item_id" to "ac-128-gap-1-item-1",
                "intended_outcome" to "The integration scenario verifies the feature.",
                "implementation_actions" to listOf("Add and execute the integration scenario."),
                "affected_paths_or_symbols" to listOf("runtime-core/src/test"),
                "required_verification" to listOf("Run the integration test."),
                "depends_on" to emptyList<String>(),
                "status" to "pending",
              ),
            ),
          ),
        ),
      ),
    ),
  )
}
