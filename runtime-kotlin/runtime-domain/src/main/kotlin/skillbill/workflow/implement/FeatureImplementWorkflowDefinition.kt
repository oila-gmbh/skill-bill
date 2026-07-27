package skillbill.workflow.implement

import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowInputProjectionDeclaration

object FeatureImplementWorkflowDefinition {
  private val implementationReceiptFields = setOf(
    "tasks_completed",
    "files_created",
    "files_modified",
    "tests_written",
    "plan_deviation_notes",
    "criteria_to_file_map",
    "notes_for_review",
    "stopped_early",
    "stopped_reason",
  )
  private val validationRequestFields =
    setOf("validation_strategy", "changed_paths", "required_checks", "repository_checkpoint")
  private val validationReceiptFields = setOf("validation_status", "checks", "repository_checkpoint")
  private val boundaryCandidateFields = setOf("changed_paths", "boundary_candidates")
  private val historyReceiptFields = setOf("changed_paths", "decisions_recorded")
  private val commitRequestFields = setOf(
    "path_inventory",
    "required_inclusions",
    "required_exclusions",
    "branch_identity",
    "gate_attestations",
    "repository_checkpoint",
  )
  private val commitReceiptFields = setOf("commit_sha", "branch", "base_branch", "pushed")
  private val prRequestFields = setOf(
    "completed_task_ids",
    "changed_paths",
    "tests_added",
    "tests_updated",
    "deviations",
    "validation_summary",
    "base_branch",
    "diff_reference",
  )

  private val privateArtifactKeys = setOf(
    "step_artifacts",
    "telemetry_payload",
    "progress",
    "prompt",
    "logs",
    "source_body",
    "complete_diff",
    "progress_write_failures",
  )

  private fun projection(
    vararg keys: String,
    typedFields: Map<String, Set<String>> = emptyMap(),
    repositoryCheckpointArtifactKey: String = "repository_evidence",
  ) = WorkflowInputProjectionDeclaration(
    requiredArtifactKeys = keys.toList(),
    projectedFieldsByArtifactKey = typedFields,
    forbiddenArtifactKeys = privateArtifactKeys,
    maxUtf8Bytes = 64 * 1024,
    maxCollectionItems = 512,
    repositoryCheckpointArtifactKey = repositoryCheckpointArtifactKey,
  )

  val definition: WorkflowDefinition = WorkflowDefinition(
    skillName = "bill-feature-task",
    workflowName = "bill-feature-task",
    workflowIdPrefix = "wfl",
    defaultSessionPrefix = "fis",
    contractVersion = "0.1",
    workflowStatuses = setOf("pending", "running", "completed", "failed", "abandoned", "blocked", "paused"),
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
      "audit",
      "review",
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
      "plan" to "Step 3: Create Implementation Plan or Decompose",
      "implement" to "Step 4: Execute Plan",
      "audit" to "Step 5: Completeness Audit",
      "review" to "Step 6: Code Review",
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
      "implement" to listOf("plan"),
      "audit" to listOf("plan", "implementation_summary", "repository_evidence"),
      "review" to listOf("acceptance_criteria", "review_scope", "audit_clearance"),
      "validate" to listOf("validation_request", "audit_clearance", "repository_evidence"),
      "write_history" to listOf("boundary_candidates", "validation_receipt", "repository_evidence"),
      "commit_push" to listOf("commit_request", "validation_receipt", "history_receipt", "repository_evidence"),
      "pr_description" to listOf("acceptance_criteria", "pr_request", "commit_receipt", "repository_evidence"),
      "finish" to listOf("pr_result"),
    ),
    resumeActions =
    mapOf(
      "assess" to "Reconstruct or confirm the Step 1 assessment, then reopen the workflow from create_branch.",
      "create_branch" to "Create or verify the feature branch, persist the branch artifact, then continue to preplan.",
      "preplan" to
        "Re-run the pre-planning phase using the assessment and branch artifacts, then persist preplan_digest.",
      "plan" to
        "Re-run the planning phase using assessment and preplan_digest. Persist either the implementation plan " +
        "or the terminal decomposition package.",
      "implement" to
        "Resume implementation from the persisted plan, then refresh implementation_summary.",
      "review" to
        "Resume code review from acceptance_criteria, the exact checkpointed review_scope, and audit_clearance.",
      "audit" to
        "Resume the completeness audit from the executable plan, implementation receipt, acceptance criteria, " +
        "and checkpoint-scoped repository evidence; review_result is not an audit input.",
      "validate" to
        "Resume final validation from validation_request and audit_clearance, then persist validation_receipt.",
      "write_history" to
        "Resume boundary history from boundary_candidates and validation_receipt, then persist history_receipt.",
      "commit_push" to
        "Resume commit/push from commit_request, validation_receipt, and history_receipt.",
      "pr_description" to "Resume PR creation from pr_request and commit_receipt, then persist pr_result.",
      "finish" to "Close the workflow by marking finish completed and setting the final workflow_status.",
    ),
    continuationReferenceSections =
    mapOf(
      "assess" to listOf(
        "content.md :: Workflow State",
        "content.md :: Step 1: Collect Design Doc + Assess Size (orchestrator)",
      ),
      "create_branch" to listOf(
        "content.md :: Workflow State",
        "content.md :: Step 1b: Create Feature Branch (orchestrator)",
      ),
      "preplan" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Durable Progress Write Contract",
        "content.md :: Step 2: Pre-Planning (subagent)",
        "content.md :: Pre-planning subagent briefing",
      ),
      "plan" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Durable Progress Write Contract",
        "content.md :: Step 3: Create Implementation Plan (subagent)",
        "content.md :: Planning subagent briefing",
      ),
      "implement" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Durable Progress Write Contract",
        "content.md :: Step 4: Execute Plan (subagent)",
        "content.md :: Implementation subagent briefing",
      ),
      "review" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 6: Code Review (orchestrator)",
        "content.md :: Fix-loop briefing (used by Step 6 review loop)",
      ),
      "audit" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Durable Progress Write Contract",
        "content.md :: Step 5: Completeness Audit (subagent)",
        "content.md :: Completeness audit subagent briefing",
      ),
      "validate" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Durable Progress Write Contract",
        "content.md :: Step 6b: Final Validation Gate (subagent)",
        "content.md :: Quality-check subagent briefing",
      ),
      "write_history" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 7: Write Boundary History (orchestrator)",
      ),
      "commit_push" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Step 8: Commit and Push (orchestrator)",
      ),
      "pr_description" to listOf(
        "content.md :: Continuation Mode",
        "content.md :: Durable Progress Write Contract",
        "content.md :: Step 9: Generate PR Description (subagent)",
        "content.md :: PR-description subagent briefing",
      ),
      "finish" to listOf(
        "content.md :: Telemetry: Record Finished",
        "content.md :: Workflow State Contract",
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
        "then spawn the pre-planning subagent with those recovered inputs. Require durable progress writes " +
        "during execution using workflow_id, step_id=preplan, and the resumed attempt_count.",
      "plan" to
        "Skip the discovery steps. Reuse the saved assessment and preplan_digest artifacts, then spawn the planning " +
        "subagent from that recovered context. If it returns mode: \"decompose\", persist the subtask specs and " +
        "close the workflow at planning instead of proceeding to implementation. Require durable progress writes " +
        "using workflow_id, step_id=plan, and the resumed attempt_count.",
      "implement" to
        "Do not re-plan unless the recovered plan proves invalid. Reuse the saved plan artifact, " +
        "then resume the implementation subagent from Step 4. Require durable progress writes at task boundaries and " +
        "heartbeat intervals using workflow_id, step_id=implement, and the resumed attempt_count.",
      "review" to
        "Do not re-run implementation first unless the review loop sends work back. Start from acceptance_criteria, " +
        "the exact checkpointed review_scope, and compact audit_clearance, then run Step 6 inline.",
      "audit" to
        "Resume at the completeness audit using the executable plan, implementation receipt, acceptance criteria, " +
        "and checkpoint-scoped repository evidence. Never inject review_result. Only loop back to implementation " +
        "when the audit finds gaps. Require durable progress writes using workflow_id, " +
        "step_id=audit, and the resumed attempt_count.",
      "validate" to
        "Resume the final validation gate from validation_request and audit_clearance, then continue the normal " +
        "finalization sequence without pausing unless validation fails. Require durable progress writes using " +
        "workflow_id, step_id=validate, and the resumed attempt_count.",
      "write_history" to
        "Skip directly to boundary history writing using boundary_candidates and validation_receipt, then continue " +
        "with commit and PR creation.",
      "commit_push" to
        "Do not revisit earlier steps. Use commit_request, validation_receipt, and history_receipt, then run " +
        "commit/push.",
      "pr_description" to
        "Resume directly at PR creation using pr_request and commit_receipt, then finish the " +
        "workflow and telemetry sequence. Require durable progress writes using workflow_id, " +
        "step_id=pr_description, and the resumed attempt_count.",
      "finish" to
        "Do not re-execute work. Close the workflow cleanly by inspecting pr_result and final telemetry state, then " +
        "emit only the terminal summary if anything is still missing.",
    ),
    continuationArtifactOrder = listOf("assessment", "branch"),
    openPriorStepsCompleted = false,
    completedTerminalSummaryArtifact = "pr_result",
    workflowMode = "prose",
    inputProjectionsByStep = mapOf(
      "implement" to projection("plan", "repository_evidence"),
      "audit" to projection(
        "plan",
        "implementation_summary",
        "repository_evidence",
        typedFields = mapOf(
          "implementation_summary" to implementationReceiptFields,
        ),
      ),
      "review" to projection(
        "acceptance_criteria",
        "review_scope",
        "audit_clearance",
        typedFields = mapOf(
          "acceptance_criteria" to setOf("criteria"),
          "review_scope" to setOf("fingerprint", "comparison_scope", "changed_paths"),
          "audit_clearance" to setOf("contract_version", "verdict"),
        ),
        repositoryCheckpointArtifactKey = "review_scope",
      ),
      "validate" to projection(
        "validation_request",
        "audit_clearance",
        "repository_evidence",
        typedFields = mapOf(
          "validation_request" to validationRequestFields,
          "audit_clearance" to setOf("contract_version", "verdict"),
        ),
      ),
      "write_history" to projection(
        "boundary_candidates",
        "validation_receipt",
        "repository_evidence",
        typedFields = mapOf(
          "boundary_candidates" to boundaryCandidateFields,
          "validation_receipt" to validationReceiptFields,
        ),
      ),
      "commit_push" to projection(
        "commit_request",
        "validation_receipt",
        "history_receipt",
        "repository_evidence",
        typedFields = mapOf(
          "commit_request" to commitRequestFields,
          "validation_receipt" to validationReceiptFields,
          "history_receipt" to historyReceiptFields,
        ),
      ),
      "pr_description" to projection(
        "acceptance_criteria",
        "pr_request",
        "commit_receipt",
        "repository_evidence",
        typedFields = mapOf(
          "acceptance_criteria" to setOf("criteria"),
          "pr_request" to prRequestFields,
          "commit_receipt" to commitReceiptFields,
        ),
      ),
    ),
  )
}
