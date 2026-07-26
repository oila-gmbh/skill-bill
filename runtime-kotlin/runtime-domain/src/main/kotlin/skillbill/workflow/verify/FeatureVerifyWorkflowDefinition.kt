package skillbill.workflow.verify

import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowInputProjectionDeclaration

object FeatureVerifyWorkflowDefinition {
  private val criteriaFields = setOf(
    "acceptance_criteria",
    "non_goals",
    "rollout_expectation",
    "technical_constraints",
  )
  private val diffFields = setOf("checkpoint", "comparison_scope", "changed_files")
  private val evaluatorPolicyFields = setOf("contract_version", "rules")
  private val evaluatorReceiptFields = setOf("contract_version", "verdict", "findings")

  private val privateArtifactKeys = setOf(
    "telemetry_payload",
    "progress",
    "prompt",
    "logs",
    "source_body",
    "complete_diff",
    "raw_review_output",
    "progress_write_failures",
  )

  private fun projection(vararg keys: String, typedFields: Map<String, Set<String>>) =
    WorkflowInputProjectionDeclaration(
      requiredArtifactKeys = keys.toList(),
      projectedFieldsByArtifactKey = typedFields,
      forbiddenArtifactKeys = privateArtifactKeys,
      maxUtf8Bytes = 64 * 1024,
      maxCollectionItems = 512,
      repositoryCheckpointArtifactKey = "diff_projection",
    )

  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "bill-feature-verify",
    workflowName = "bill-feature-verify",
    workflowIdPrefix = "wfv",
    defaultSessionPrefix = "fvr",
    contractVersion = "0.1",
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned"),
    stepStatuses = setOf("pending", "running", "completed", "failed", "blocked", "skipped"),
    terminalStatuses = setOf("completed", "failed", "abandoned"),
    defaultInitialStepId = "gather_diff",
    stepIds =
    listOf(
      "collect_inputs",
      "extract_criteria",
      "gather_diff",
      "feature_flag_audit",
      "code_review",
      "unit_test_value_check",
      "completeness_audit",
      "verdict",
      "finish",
    ),
    stepLabels =
    mapOf(
      "collect_inputs" to "Step 1: Collect Inputs",
      "extract_criteria" to "Step 2: Extract Acceptance Criteria",
      "gather_diff" to "Step 3: Gather PR Diff",
      "feature_flag_audit" to "Step 4: Feature Flag Audit",
      "code_review" to "Step 5: Code Review",
      "unit_test_value_check" to "Step 6: Unit Test Value Check",
      "completeness_audit" to "Step 7: Completeness Audit",
      "verdict" to "Step 8: Consolidated Verdict",
      "finish" to "Finish",
    ),
    requiredArtifactsByStep =
    mapOf(
      "collect_inputs" to emptyList(),
      "extract_criteria" to listOf("input_context"),
      "gather_diff" to listOf("input_context", "criteria_summary"),
      "feature_flag_audit" to listOf("criteria_summary", "feature_flag_policy", "diff_projection"),
      "code_review" to listOf("criteria_summary", "review_rubric", "diff_projection"),
      "unit_test_value_check" to listOf("criteria_summary", "unit_test_value_rubric", "diff_projection"),
      "completeness_audit" to listOf("criteria_summary", "completeness_rubric", "diff_projection"),
      "verdict" to listOf(
        "feature_flag_audit_receipt",
        "code_review_receipt",
        "unit_test_value_receipt",
        "completeness_audit_receipt",
        "diff_projection",
      ),
      "finish" to listOf("verdict_result"),
    ),
    resumeActions =
    mapOf(
      "collect_inputs" to "Reconfirm the task spec and PR inputs, then reopen the workflow from extract_criteria.",
      "extract_criteria" to
        "Re-extract and confirm the criteria, then persist criteria_summary before moving to gather_diff.",
      "gather_diff" to
        "Reuse input_context and criteria_summary, then persist the bounded checkpointed diff_projection and " +
        "evaluator policies.",
      "feature_flag_audit" to
        "Reuse criteria_summary, feature_flag_policy, and diff_projection; persist feature_flag_audit_receipt.",
      "code_review" to
        "Run bill-code-review independently against criteria, its rubric, and diff_projection, then persist " +
        "code_review_receipt.",
      "unit_test_value_check" to
        "Run bill-unit-test-value-check independently against criteria, its rubric, and diff_projection.",
      "completeness_audit" to
        "Run completeness independently against criteria, its rubric, and diff_projection.",
      "verdict" to
        "Reuse only compact typed evaluator receipts to produce the final verdict without rerunning earlier phases.",
      "finish" to "Close the workflow by marking the verdict complete and emitting the terminal summary.",
    ),
    continuationReferenceSections =
    mapOf(
      "collect_inputs" to listOf("content.md :: Workflow State", "content.md :: Step 1: Collect Inputs"),
      "extract_criteria" to listOf("content.md :: Workflow State", "content.md :: Step 2: Extract Acceptance Criteria"),
      "gather_diff" to listOf("content.md :: Continuation Mode", "content.md :: Step 3: Gather PR Diff"),
      "feature_flag_audit" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 4: Feature Flag Audit (conditional)",
        "content.md :: Feature Flag Audit",
      ),
      "code_review" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 5: Code Review",
        "content.md :: Nested child tools",
      ),
      "unit_test_value_check" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 6: Unit Test Value Check",
        "skills/bill-unit-test-value-check/content.md :: Workflow",
        "skills/bill-unit-test-value-check/content.md :: Output",
      ),
      "completeness_audit" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 7: Completeness Audit",
        "content.md :: Completeness Audit",
      ),
      "verdict" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 8: Consolidated Verdict",
        "content.md :: Consolidated Verdict",
      ),
      "finish" to listOf("content.md :: Telemetry", "content.md :: Workflow State"),
    ),
    continuationDirectives =
    mapOf(
      "collect_inputs" to
        "Reconfirm the spec and PR inputs before continuing, then reopen the workflow from Step 2 with the " +
        "recovered context.",
      "extract_criteria" to
        "Re-extract the criteria from the saved spec context, confirm them with the user, and persist " +
        "criteria_summary before advancing.",
      "gather_diff" to
        "Skip Steps 1 and 2. Reuse the saved input_context and criteria_summary artifacts, then gather the diff " +
        "target and persist the bounded checkpointed diff_projection plus feature_flag_policy, review_rubric, " +
        "unit_test_value_rubric, and completeness_rubric.",
      "feature_flag_audit" to
        "Reuse criteria_summary, feature_flag_policy, and diff_projection. Run the audit only when applicable, " +
        "then persist feature_flag_audit_receipt.",
      "code_review" to
        "Reuse criteria_summary, review_rubric, and diff_projection, pass orchestrated=true to bill-code-review, " +
        "persist code_review_receipt, and keep telemetry in its dedicated store.",
      "unit_test_value_check" to
        "Run independently from sibling evaluators using criteria_summary, unit_test_value_rubric, and the " +
        "checkpoint-scoped diff_projection.",
      "completeness_audit" to
        "Run independently from sibling evaluators using criteria_summary, completeness_rubric, and the " +
        "checkpoint-scoped diff_projection. Refresh the projection if the target changed materially.",
      "verdict" to
        "Reuse only compact typed evaluator receipts to produce the final verdict without rerunning earlier phases.",
      "finish" to
        "Do not re-run analysis. Close the workflow using the saved verdict_result and return the terminal summary " +
        "only.",
    ),
    continuationArtifactOrder =
    listOf(
      "input_context",
      "criteria_summary",
      "diff_projection",
      "feature_flag_audit_receipt",
      "code_review_receipt",
      "unit_test_value_receipt",
      "completeness_audit_receipt",
      "verdict_result",
      "session_notes",
      "review_diff_pointer",
    ),
    openPriorStepsCompleted = true,
    completedTerminalSummaryArtifact = "verdict_result",
    inputProjectionsByStep = mapOf(
      "feature_flag_audit" to projection(
        "criteria_summary",
        "feature_flag_policy",
        "diff_projection",
        typedFields = mapOf(
          "criteria_summary" to criteriaFields,
          "feature_flag_policy" to evaluatorPolicyFields,
          "diff_projection" to diffFields,
        ),
      ),
      "code_review" to projection(
        "criteria_summary",
        "review_rubric",
        "diff_projection",
        typedFields = mapOf(
          "criteria_summary" to criteriaFields,
          "review_rubric" to evaluatorPolicyFields,
          "diff_projection" to diffFields,
        ),
      ),
      "unit_test_value_check" to projection(
        "criteria_summary",
        "unit_test_value_rubric",
        "diff_projection",
        typedFields = mapOf(
          "criteria_summary" to criteriaFields,
          "unit_test_value_rubric" to evaluatorPolicyFields,
          "diff_projection" to diffFields,
        ),
      ),
      "completeness_audit" to projection(
        "criteria_summary",
        "completeness_rubric",
        "diff_projection",
        typedFields = mapOf(
          "criteria_summary" to criteriaFields,
          "completeness_rubric" to evaluatorPolicyFields,
          "diff_projection" to diffFields,
        ),
      ),
      "verdict" to projection(
        "feature_flag_audit_receipt",
        "code_review_receipt",
        "unit_test_value_receipt",
        "completeness_audit_receipt",
        "diff_projection",
        typedFields = mapOf(
          "feature_flag_audit_receipt" to evaluatorReceiptFields,
          "code_review_receipt" to evaluatorReceiptFields,
          "unit_test_value_receipt" to evaluatorReceiptFields,
          "completeness_audit_receipt" to evaluatorReceiptFields,
          "diff_projection" to diffFields,
        ),
      ),
    ),
  )
}
