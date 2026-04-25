package skillbill.workflow.implement

import skillbill.workflow.model.WorkflowDefinition

object FeatureImplementWorkflowDefinition {
  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "bill-feature-implement",
    workflowName = "bill-feature-implement",
    workflowIdPrefix = "wfl",
    defaultSessionPrefix = "fis",
    contractVersion = "0.1",
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned", "blocked"),
    stepStatuses = setOf("pending", "running", "completed", "failed", "blocked", "skipped"),
    terminalStatuses = setOf("completed", "failed", "abandoned"),
    defaultInitialStepId = "assess",
    stepIds =
    listOf(
      "assess",
      "create_branch",
      "preplan",
      "plan",
      "implement",
      "review",
      "audit",
      "validate",
      "write_history",
      "commit_push",
      "pr_description",
      "finish",
    ),
    stepLabels =
    mapOf(
      "assess" to "Step 1: Collect Design Doc + Assess Size",
      "create_branch" to "Step 1b: Create Feature Branch",
      "preplan" to "Step 2: Pre-Planning",
      "plan" to "Step 3: Create Implementation Plan",
      "implement" to "Step 4: Execute Plan",
      "review" to "Step 5: Code Review",
      "audit" to "Step 6: Completeness Audit",
      "validate" to "Step 6b: Quality Check",
      "write_history" to "Step 7: Boundary History",
      "commit_push" to "Step 8: Commit and Push",
      "pr_description" to "Step 9: PR Description",
      "finish" to "Finish",
    ),
    requiredArtifactsByStep =
    mapOf(
      "assess" to emptyList(),
      "create_branch" to listOf("assessment"),
      "preplan" to listOf("assessment", "branch"),
      "plan" to listOf("assessment", "preplan_digest"),
      "implement" to listOf("plan", "preplan_digest"),
      "review" to listOf("implementation_summary"),
      "audit" to listOf("implementation_summary", "review_result"),
      "validate" to listOf("audit_report"),
      "write_history" to listOf("implementation_summary", "validation_result"),
      "commit_push" to listOf("implementation_summary", "validation_result", "history_result"),
      "pr_description" to listOf("implementation_summary", "branch"),
      "finish" to listOf("pr_result"),
    ),
    resumeActions =
    mapOf(
      "assess" to "Reconstruct or confirm the Step 1 assessment, then reopen the workflow from create_branch.",
      "create_branch" to "Create or verify the feature branch, persist the branch artifact, then continue to preplan.",
      "preplan" to
        "Re-run the pre-planning phase using the assessment and branch artifacts, then persist preplan_digest.",
      "plan" to "Re-run the planning phase using assessment and preplan_digest, then persist the plan artifact.",
      "implement" to
        "Resume implementation from the persisted plan and preplan_digest, then refresh implementation_summary.",
      "review" to
        "Resume code review from the latest implementation_summary and persist review_result after each pass.",
      "audit" to
        "Resume the completeness audit from implementation_summary and review_result, then persist audit_report.",
      "validate" to "Resume final validation from the latest audit_report, then persist validation_result.",
      "write_history" to
        "Resume boundary history from implementation_summary and validation_result, then persist history_result.",
      "commit_push" to
        "Resume commit/push after verifying implementation_summary, validation_result, and history_result are current.",
      "pr_description" to "Resume PR creation using the branch and implementation_summary, then persist pr_result.",
      "finish" to "Close the workflow by marking finish completed and setting the final workflow_status.",
    ),
    continuationReferenceSections =
    mapOf(
      "assess" to listOf(
        "SKILL.md :: Workflow State",
        "SKILL.md :: Step 1: Collect Design Doc + Assess Size (orchestrator)",
      ),
      "create_branch" to listOf(
        "SKILL.md :: Workflow State",
        "SKILL.md :: Step 1b: Create Feature Branch (orchestrator)",
      ),
      "preplan" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 2: Pre-Planning (subagent)",
        "reference.md :: Pre-planning subagent briefing",
      ),
      "plan" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 3: Create Implementation Plan (subagent)",
        "reference.md :: Planning subagent briefing",
      ),
      "implement" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 4: Execute Plan (subagent)",
        "reference.md :: Implementation subagent briefing",
      ),
      "review" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 5: Code Review (orchestrator)",
        "reference.md :: Fix-loop briefing (used by Step 5 review loop)",
      ),
      "audit" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 6: Completeness Audit (subagent)",
        "reference.md :: Completeness audit subagent briefing",
      ),
      "validate" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 6b: Final Validation Gate (subagent)",
        "reference.md :: Quality-check subagent briefing",
      ),
      "write_history" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 7: Write Boundary History (orchestrator)",
      ),
      "commit_push" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 8: Commit and Push (orchestrator)",
      ),
      "pr_description" to listOf(
        "SKILL.md :: Continuation Mode",
        "SKILL.md :: Step 9: Generate PR Description (subagent)",
        "reference.md :: PR-description subagent briefing",
      ),
      "finish" to listOf(
        "SKILL.md :: Telemetry: Record Finished",
        "reference.md :: Workflow State Contract",
      ),
    ),
    continuationDirectives =
    mapOf(
      "assess" to
        "Reconstruct the Step 1 assessment from the saved assessment artifact, confirm it with the user if needed, " +
        "then reopen the normal flow from create_branch.",
      "create_branch" to
        "Do not rerun Step 1 discovery. Reuse the saved assessment artifact, create or verify the feature branch, " +
        "persist the branch artifact, then continue into preplan.",
      "preplan" to
        "Skip Steps 1 and 1b. Reuse the saved assessment and branch artifacts as the contract and branch context, " +
        "then spawn the pre-planning subagent with those recovered inputs.",
      "plan" to
        "Skip the discovery steps. Reuse the saved assessment and preplan_digest artifacts, then spawn the planning " +
        "subagent from that recovered context.",
      "implement" to
        "Do not re-plan unless the recovered plan proves invalid. Reuse the saved plan and preplan_digest artifacts, " +
        "then resume the implementation subagent from Step 4.",
      "review" to
        "Do not re-run implementation first unless the review loop sends work back. Start from the latest " +
        "implementation_summary artifact and run Step 5 inline in the orchestrator.",
      "audit" to
        "Resume at the completeness audit using the latest implementation_summary and review_result artifacts. Only " +
        "loop back to planning if the audit actually finds gaps.",
      "validate" to
        "Resume the final validation gate from the latest audit_report artifact, then continue the normal " +
        "finalization sequence without pausing unless validation fails.",
      "write_history" to
        "Skip directly to boundary history writing using the persisted implementation_summary and validation_result " +
        "artifacts, then continue with commit and PR creation.",
      "commit_push" to
        "Do not revisit earlier steps. Verify the persisted implementation_summary, validation_result, and " +
        "history_result artifacts are still current, then run commit/push.",
      "pr_description" to
        "Resume directly at PR creation using the saved branch and implementation_summary artifacts, then finish the " +
        "workflow and telemetry sequence.",
      "finish" to
        "Do not re-execute work. Close the workflow cleanly by inspecting pr_result and final telemetry state, then " +
        "emit only the terminal summary if anything is still missing.",
    ),
    continuationArtifactOrder = listOf("assessment", "branch"),
    openPriorStepsCompleted = false,
    completedTerminalSummaryArtifact = "pr_result",
  )
}
