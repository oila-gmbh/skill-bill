package skillbill.workflow.taskruntime

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePlanningProjectionContract
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePriorGapMemory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.PhaseHandoffProjectionDeclaration

internal object FeatureTaskRuntimePhaseWorkflowProjectionDeclarations {
  @Suppress("LongParameterList")
  fun phaseProjection(
    consumerPhaseId: String,
    producingPhaseId: String,
    name: String,
    contractId: String,
    fields: List<String>,
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
    required: Boolean = true,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = name,
    projectionContractId = contractId,
    projectionContractVersion = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = fields,
    checkpointPolicy = checkpointPolicy,
    required = required,
  )

  fun auditRemediationProjections(): List<PhaseHandoffProjectionDeclaration> = listOf(
    phaseProseDeclaration(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
    ),
    phaseProseDeclaration(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
    ),
    phaseProseDeclaration(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
    ),
    priorGapMemoryDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
  )

  fun phaseProseDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  ): PhaseHandoffProjectionDeclaration = PhaseHandoffProjectionDeclaration(
    consumerPhaseId = consumerPhaseId,
    sourceRef = FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(producingPhaseId),
    projectionName = "${producingPhaseId}_prose",
    projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PHASE_PROSE,
    projectionContractVersion = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VERSION,
    promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
    budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
    declaredFieldNames = listOf("value", "directive"),
    checkpointPolicy = checkpointPolicy,
    required = true,
  )

  fun sharedReviewEvidenceDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = consumerPhaseId,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.SharedReviewEvidence,
      projectionName = FeatureTaskRuntimePhaseWorkflowDefinition.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME,
      projectionContractId = FeatureTaskRuntimePlanningProjectionContract.SHARED_REVIEW_EVIDENCE_ID,
      projectionContractVersion = FeatureTaskRuntimePlanningProjectionContract.VERSION,
      promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
      budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
      declaredFieldNames = FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES,
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      required = false,
    )

  fun repairLedgerDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = consumerPhaseId,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.RepairLedger,
      projectionName = FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME,
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REPAIR_LEDGER,
      projectionContractVersion = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VERSION,
      promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
      budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
      declaredFieldNames = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.REPAIR_LEDGER_PROJECTION_NAME),
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
      required = false,
    )

  fun priorGapMemoryDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = consumerPhaseId,
      sourceRef = FeatureTaskRuntimeHandoffSourceRef.PriorGapMemory,
      projectionName = FeatureTaskRuntimePhaseWorkflowDefinition.PRIOR_GAP_MEMORY_PROJECTION_NAME,
      projectionContractId = FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PRIOR_GAP_MEMORY,
      projectionContractVersion = "0.2",
      promptVisibility = FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
      budget = FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION,
      declaredFieldNames = FeatureTaskRuntimePriorGapMemory.DECLARED_FIELD_NAMES,
      checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
      required = false,
    )

  /**
   * Closed-world projection matrix for every phase. Every upstream edge has an explicit typed
   * declaration; an omitted phase or edge is a contract error rather than permission to deliver a
   * complete producer receipt.
   */
  val phaseProjectionMatrix: Map<String, List<PhaseHandoffProjectionDeclaration>> = mapOf(
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to emptyList(),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to listOf(
      phaseProseDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      ),
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      sharedReviewEvidenceDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to listOf(
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        "review_repair_request",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.REVIEW_REPAIR_REQUEST,
        listOf("unresolved_blocker_findings", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.MUST_MATCH,
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to listOf(
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        "review_findings_for_verification",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.FINDINGS_VERIFICATION_INPUT,
        listOf("findings", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to listOf(
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        "audit_clearance",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
        listOf("clearance_status", "review_scope", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      sharedReviewEvidenceDeclaration(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        "validation_request",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
        listOf(
          "changed_paths",
          "repository_checkpoint",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        "audit_clearance",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
        listOf("verdict", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        "validation_request",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_REQUEST,
        listOf(
          "changed_paths",
          "repository_checkpoint",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        "audit_clearance",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.AUDIT_CLEARANCE,
        listOf("verdict", "repository_checkpoint"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        "boundary_candidates",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BOUNDARY_CANDIDATES,
        listOf("changed_paths", "boundary_candidates"),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        "validation_receipt",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        "build_receipt",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
        required = false,
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        "commit_request",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_REQUEST,
        listOf(
          "path_inventory",
          "required_inclusions",
          "branch_identity",
          "gate_attestations",
          "repository_checkpoint",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        "validation_receipt",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.VALIDATION_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        "build_receipt",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.BUILD_RECEIPT,
        listOf(
          "validation_status",
          "checks",
          "repository_checkpoint",
          "gate_run_count",
          "gate_runs",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
        required = false,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        "history_receipt",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.HISTORY_RECEIPT,
        listOf("changed_paths", "decisions_recorded"),
      ),
    ),
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to listOf(
      phaseProseDeclaration(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        "pr_request",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.PR_REQUEST,
        listOf(
          "changed_paths",
          "validation_summary",
          "base_branch",
          "diff_reference",
        ),
        FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
      ),
      phaseProjection(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        "commit_receipt",
        FeatureTaskRuntimePhaseWorkflowDefinition.PhaseProjectionContract.COMMIT_RECEIPT,
        listOf("commit_sha", "branch", "base_branch", "pushed"),
      ),
    ),
  )

  fun runtimeProjectorProducerPhaseIds(consumerPhaseId: String): Set<String> = when (consumerPhaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW)
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE -> setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY -> setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH -> setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
    )
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR -> setOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
    )
    else -> emptySet()
  }

  fun phaseDeclarations(definition: WorkflowDefinition): Map<String, FeatureTaskRuntimePhaseDeclaration> =
    definition.stepIds.associateWith { phaseId ->
      FeatureTaskRuntimePhaseDeclaration(
        phaseId = phaseId,
        projectionDeclarations = requireNotNull(phaseProjectionMatrix[phaseId]) {
          "No closed-world projection declaration for runtime phase '$phaseId'."
        },
        derivedContextKeys = when (phaseId) {
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW -> listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF)
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR -> listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF)
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT -> listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE)
          else -> emptyList()
        },
      )
    }
}
