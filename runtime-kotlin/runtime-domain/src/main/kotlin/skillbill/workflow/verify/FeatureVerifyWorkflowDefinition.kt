package skillbill.workflow.verify

import skillbill.workflow.model.WorkflowDefinition

object FeatureVerifyWorkflowDefinition {
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
      "completeness_audit" to "Step 6: Completeness Audit",
      "verdict" to "Step 7: Consolidated Verdict",
      "finish" to "Finish",
    ),
    requiredArtifactsByStep =
    mapOf(
      "collect_inputs" to emptyList(),
      "extract_criteria" to listOf("input_context"),
      "gather_diff" to listOf("input_context", "criteria_summary"),
      "feature_flag_audit" to listOf("criteria_summary", "diff_summary"),
      "code_review" to listOf("criteria_summary", "diff_summary"),
      "completeness_audit" to listOf("criteria_summary", "diff_summary", "review_result"),
      "verdict" to listOf("criteria_summary", "review_result", "completeness_audit_result"),
      "finish" to listOf("verdict_result"),
    ),
    resumeActions =
    mapOf(
      "collect_inputs" to "Reconfirm the task spec and PR inputs, then reopen the workflow from extract_criteria.",
      "extract_criteria" to
        "Re-extract and confirm the criteria, then persist criteria_summary before moving to gather_diff.",
      "gather_diff" to
        "Reuse input_context and criteria_summary, then gather the diff target and persist diff_summary.",
      "feature_flag_audit" to
        "Reuse criteria_summary and diff_summary, run the audit if still required, and persist the result.",
      "code_review" to
        "Reuse criteria_summary and diff_summary, then invoke bill-code-review and persist review_result.",
      "completeness_audit" to
        "Reuse criteria_summary, diff_summary, and review_result, then persist completeness_audit_result.",
      "verdict" to
        "Reuse saved review and audit artifacts, then write the final verdict without rerunning earlier phases.",
      "finish" to "Close the workflow by marking the verdict complete and emitting the terminal summary.",
    ),
    continuationReferenceSections =
    mapOf(
      "collect_inputs" to listOf("SKILL.md :: Workflow State", "SKILL.md :: Step 1: Collect Inputs"),
      "extract_criteria" to listOf("SKILL.md :: Workflow State", "SKILL.md :: Step 2: Extract Acceptance Criteria"),
      "gather_diff" to listOf("SKILL.md :: Continuation Mode", "SKILL.md :: Step 3: Gather PR Diff"),
      "feature_flag_audit" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 4: Feature Flag Audit (conditional)",
        "audit-rubrics.md :: Feature Flag Audit",
      ),
      "code_review" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 5: Code Review",
        "SKILL.md :: Nested child tools",
      ),
      "completeness_audit" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 6: Completeness Audit",
        "audit-rubrics.md :: Completeness Audit",
      ),
      "verdict" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 7: Consolidated Verdict",
        "audit-rubrics.md :: Consolidated Verdict",
      ),
      "finish" to listOf("SKILL.md :: Telemetry", "SKILL.md :: Workflow State"),
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
        "target and persist diff_summary.",
      "feature_flag_audit" to
        "Reuse the saved criteria_summary and diff_summary artifacts. Run the audit only when the spec or diff " +
        "still requires it, then persist feature_flag_audit_result.",
      "code_review" to
        "Reuse the saved criteria_summary and diff_summary artifacts, pass orchestrated=true to bill-code-review, " +
        "and store the returned telemetry payload with the review result.",
      "completeness_audit" to
        "Reuse criteria_summary, diff_summary, and review_result. If the verify target changed materially, refresh " +
        "the diff before re-running the audit.",
      "verdict" to
        "Reuse the saved review and audit artifacts to produce the final verdict without rerunning earlier steps " +
        "unless recovery made them stale.",
      "finish" to
        "Do not re-run analysis. Close the workflow using the saved verdict_result and return the terminal summary " +
        "only.",
    ),
    continuationArtifactOrder =
    listOf(
      "input_context",
      "criteria_summary",
      "diff_summary",
      "feature_flag_audit_result",
      "review_result",
      "completeness_audit_result",
      "verdict_result",
      "session_notes",
      "review_diff_pointer",
    ),
    openPriorStepsCompleted = true,
    completedTerminalSummaryArtifact = "verdict_result",
  )
}
