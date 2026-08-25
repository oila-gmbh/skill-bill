package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.requireGoalPlanningPhaseEnvelope
import skillbill.application.featuretask.requireValidPlanningProjection
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.FeatureTaskRuntimePlanningProjectionValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.NormalizedFeatureTaskRuntimePhaseOutput
import java.time.Instant

internal data class GoalChildPlanningHydration(
  val currentStepId: String,
  val stepUpdates: List<Map<String, Any?>>,
  val artifacts: Map<String, Any?>,
)

private data class PreparedGoalPlanning(
  val shared: SharedGoalPreplanCheckpoint,
  val plan: GoalSubtaskPlanCheckpoint,
)

private data class PreparedPlanningOutput(
  val normalizedOutput: NormalizedFeatureTaskRuntimePhaseOutput,
  val repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
)

internal class GoalChildPlanningHydrator(
  planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
) {
  private val payloadValidator = PreparedPlanningPayloadValidator(planningProjectionValidator)
  private val importMatcher = GoalChildPlanningImportMatcher(payloadValidator)

  fun hydrate(
    unitOfWork: UnitOfWork,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydration {
    val prepared = loadRequiredPreparation(unitOfWork, setup, request)
    requireMatchingPreparation(setup, request, prepared)
    val preplan = payloadValidator.requireValid(
      "preplan",
      prepared.shared.preplanPayload,
      prepared.shared.payloadSha256,
      setup.workflowId,
    )
    val plan = payloadValidator.requireValid(
      "plan",
      prepared.plan.planPayload,
      prepared.plan.payloadSha256,
      setup.workflowId,
    )
    return createHydration(request, prepared, preplan, plan)
  }

  fun requireMatchingImport(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val request = requireNotNull(setup.planningHydration) {
      "Prepared goal child '${setup.subtaskId}' requires planning hydration."
    }
    importMatcher.firstDivergence(unitOfWork, existing, setup, request)?.let { divergence ->
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        request.identity.parentGoalWorkflowId,
        setup.subtaskId,
        "existing child planning import conflicts with request: $divergence",
      )
    }
  }

  private fun loadRequiredPreparation(
    unitOfWork: UnitOfWork,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): PreparedGoalPlanning {
    val shared = unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)
      ?: throw InvalidGoalPlanningPreparationSchemaError(
        setup.workflowId,
        "preplan",
        "shared preplan is missing",
      )
    val plan = unitOfWork.goalPlanningPreparations.findSubtaskPlan(
      request.identity,
      request.descriptor.subtaskId,
      request.descriptor.governedSubSpecPath,
    ) ?: throw InvalidGoalPlanningPreparationSchemaError(
      setup.workflowId,
      "plan",
      "subtask plan is missing",
    )
    return PreparedGoalPlanning(shared, plan)
  }

  private fun requireMatchingPreparation(
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
  ) {
    val matches = listOf(
      prepared.shared.provenance == request.provenance,
      prepared.plan.provenance == request.provenance,
      prepared.plan.manifestOrder == request.descriptor.manifestOrder,
      prepared.plan.subSpecHash == request.descriptor.subSpecHash,
    ).all { it }
    if (!matches) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        request.identity.parentGoalWorkflowId,
        setup.subtaskId,
        "stored planning provenance or selected subtask descriptor differs from the hydration request",
      )
    }
  }

  private fun createHydration(
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
    preplan: PreparedPlanningOutput,
    plan: PreparedPlanningOutput,
  ): GoalChildPlanningHydration {
    val importedAt = Instant.now().toString()
    val records = createImportedRecords(
      preplan.forPlanningImport(prepared.shared.preplanPayload, prepared.shared.repairEvidence),
      plan.forPlanningImport(prepared.plan.planPayload, prepared.plan.repairEvidence),
      importedAt,
    )
    return GoalChildPlanningHydration(
      currentStepId = "implement",
      stepUpdates = records.keys.map(::completedStep),
      artifacts = mapOf(
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to records,
        FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY to createImportedLedger(importedAt),
        FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY to createProvenance(request, prepared),
      ),
    )
  }

  private fun PreparedPlanningOutput.forPlanningImport(
    storedPayload: String,
    storedEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
  ): PreparedPlanningOutput = copy(
    normalizedOutput = if (repairEvidence == null) {
      normalizedOutput.copy(canonicalJson = storedPayload)
    } else {
      normalizedOutput
    },
    repairEvidence = repairEvidence ?: storedEvidence,
  )

  private fun createImportedRecords(
    preplan: PreparedPlanningOutput,
    plan: PreparedPlanningOutput,
    importedAt: String,
  ): Map<String, Map<String, Any?>> = linkedMapOf(
    "preplan" to importedRecord(
      "preplan",
      preplan.normalizedOutput.canonicalJson,
      preplan.repairEvidence,
      importedAt,
    ).toArtifactMap(),
    "plan" to importedRecord(
      "plan",
      plan.normalizedOutput.canonicalJson,
      plan.repairEvidence,
      importedAt,
    ).toArtifactMap(),
  )

  private fun createImportedLedger(importedAt: String): List<Map<String, Any?>> =
    PLANNING_PHASE_IDS.mapIndexed { sequence, phaseId ->
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        sequenceNumber = sequence,
        timestamp = importedAt,
        phaseId = phaseId,
        attemptCount = 1,
        executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
      ).toArtifactMap()
    }

  private fun createProvenance(
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
  ): Map<String, Any?> = FeatureTaskRuntimeGoalPlanningImport(
    parentGoalWorkflowId = request.identity.parentGoalWorkflowId,
    normalizedIssueKey = request.identity.normalizedIssueKey,
    repositoryIdentity = request.identity.repositoryIdentity,
    parentSpecHash = request.provenance.parentSpecHash,
    decompositionManifestHash = request.provenance.decompositionManifestHash,
    planningContractId = request.provenance.planningContractId,
    planningContractVersion = request.provenance.planningContractVersion,
    phaseOutputContractId = request.provenance.phaseOutputContractId,
    phaseOutputContractVersion = request.provenance.phaseOutputContractVersion,
    subtaskId = request.descriptor.subtaskId,
    manifestOrder = request.descriptor.manifestOrder,
    governedSubSpecPath = request.descriptor.governedSubSpecPath,
    subSpecHash = request.descriptor.subSpecHash,
    preplanPayloadSha256 = prepared.shared.payloadSha256,
    planPayloadSha256 = prepared.plan.payloadSha256,
  ).toArtifactMap()
}

private class PreparedPlanningPayloadValidator(
  private val planningProjectionValidator: FeatureTaskRuntimePlanningProjectionValidator,
) {
  fun requireValid(
    phaseId: String,
    payload: String,
    expectedDigest: String,
    workflowId: String,
  ): PreparedPlanningOutput {
    val originalDigest = sha256HexUtf8(payload)
    if (originalDigest != expectedDigest) {
      invalidPlanningPreparation(workflowId, "$phaseId.payload_sha256", "payload digest differs")
    }
    val normalized = requireGoalPlanningPhaseEnvelope(payload, phaseId, workflowId)
    val decoded = normalized.envelope
    if (decoded["phase_id"] != phaseId || decoded["status"] != "completed") {
      invalidPlanningPreparation(
        workflowId,
        "$phaseId.payload",
        "imported phase output must match its phase and be completed",
      )
    }
    requireValidPlanningProjection(
      envelope = decoded,
      phaseId = phaseId,
      sourceLabel = workflowId,
      planningProjectionValidator = planningProjectionValidator,
      fieldPath = "$phaseId.payload",
    )
    return PreparedPlanningOutput(normalizedOutput = normalized, repairEvidence = null)
  }
}

private fun invalidPlanningPreparation(workflowId: String, fieldPath: String, reason: String): Nothing =
  throw InvalidGoalPlanningPreparationSchemaError(workflowId, fieldPath, reason)

private class GoalChildPlanningImportMatcher(
  private val payloadValidator: PreparedPlanningPayloadValidator,
) {
  fun firstDivergence(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): String? {
    val artifacts = decodeArtifacts(existing.artifactsJson)
    val expected = artifacts[FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY] as? Map<*, *>
      ?: return "child carries no goal planning import artifact"
    val shared = unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)
    val plan = unitOfWork.goalPlanningPreparations.findSubtaskPlan(
      request.identity,
      request.descriptor.subtaskId,
      request.descriptor.governedSubSpecPath,
    )
    validateAvailablePayloads(shared, plan, setup, request)
    val provenanceDivergence = provenanceDivergence(expected, request)
    return when {
      provenanceDivergence != null -> provenanceDivergence
      !preparedMatches(shared, plan, expected, request) ->
        "parent planning checkpoints are missing or differ from the imported payload digests"
      !ledgerMatches(artifacts) ->
        "phase ledger no longer opens with the goal planning import prefix"
      !planningPhasesSettled(artifacts, existing) ->
        "child planning phases are not settled as completed"
      else -> null
    }
  }

  private fun validateAvailablePayloads(
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ) {
    shared?.let {
      requireImportedPayloadValid("preplan", it.preplanPayload, it.payloadSha256, setup, request)
    }
    plan?.let {
      requireImportedPayloadValid("plan", it.planPayload, it.payloadSha256, setup, request)
    }
  }

  private fun requireImportedPayloadValid(
    phaseId: String,
    payload: String,
    digest: String,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ) {
    try {
      payloadValidator.requireValid(phaseId, payload, digest, setup.workflowId)
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw importedPayloadRecoveryError(phaseId, setup, request, error)
    }
  }

  private fun importedPayloadRecoveryError(
    phaseId: String,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    error: Throwable,
  ): IncompatibleGoalPlanningPreparationRecoveryError {
    val detail = error.message.orEmpty()
    val reason = if (classifyGoalPlanningRecovery(detail, error) == GoalPlanningRecoveryKind.HARD_RESET) {
      "stored goal planning '$phaseId' record for subtask ${request.descriptor.subtaskId} fails the " +
        "installed goal-planning preparation contract and requires a hard reset. Projection failure: $detail"
    } else {
      "stored goal planning '$phaseId' record for subtask ${request.descriptor.subtaskId} was already " +
        "imported by this child and the stored version now fails its projection contract. " +
        "This occurs when the shared preplan or subtask plan was regenerated after the child was hydrated, " +
        "making the previously-imported bytes stale. Projection failure: $detail"
    }
    return IncompatibleGoalPlanningPreparationRecoveryError(
      request.identity.parentGoalWorkflowId,
      setup.subtaskId,
      reason,
      error,
    )
  }

  private fun provenanceDivergence(expected: Map<*, *>, request: GoalChildPlanningHydrationRequest): String? {
    val mismatched = expectedProvenance(request).filter { (key, value) -> expected[key] != value }.keys
    if (mismatched.isEmpty()) return null
    return "stored import provenance differs from the hydration request at " +
      mismatched.joinToString(", ")
  }

  private fun preparedMatches(
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
    expected: Map<*, *>,
    request: GoalChildPlanningHydrationRequest,
  ): Boolean = listOf(
    shared != null,
    plan != null,
    shared?.provenance == request.provenance,
    plan?.provenance == request.provenance,
    plan?.manifestOrder == request.descriptor.manifestOrder,
    plan?.subSpecHash == request.descriptor.subSpecHash,
    shared?.payloadSha256 == expected["preplan_payload_sha256"],
    plan?.payloadSha256 == expected["plan_payload_sha256"],
  ).all { it }

  private fun planningPhasesSettled(artifacts: Map<String, Any?>, existing: WorkflowStateSnapshot): Boolean {
    val records = artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] as? Map<*, *> ?: return false
    val expected = artifacts[FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY] as? Map<*, *> ?: return false
    val expectedStepStatuses = PLANNING_PHASE_IDS.mapNotNull { phaseId ->
      settledStepStatus(records[phaseId] as? Map<*, *>, phaseId)?.let { phaseId to it }
    }.toMap()
    val preplanOutput = (records["preplan"] as? Map<*, *>)?.get("output_artifact") as? String ?: return false
    return expectedStepStatuses.size == PLANNING_PHASE_IDS.size &&
      sha256HexUtf8(preplanOutput) == expected["preplan_payload_sha256"] &&
      stepsSettled(existing, expectedStepStatuses)
  }

  private fun settledStepStatus(record: Map<*, *>?, phaseId: String): String? {
    if (record == null || record["phase_id"] != phaseId) return null
    return when (record["status"]) {
      "completed" -> "completed".takeIf { (record["output_artifact"] as? String)?.isNotBlank() == true }
      "running" -> "running"
      "blocked" -> "blocked"
      else -> null
    }
  }

  private fun ledgerMatches(artifacts: Map<String, Any?>): Boolean {
    val ledger = (artifacts[FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY] as? List<*>)
      ?.mapNotNull { it as? Map<*, *> }
      ?: return false
    if (ledger.size < PLANNING_PHASE_IDS.size) return false
    return ledger.take(PLANNING_PHASE_IDS.size).withIndex().all { (index, entry) ->
      listOf(
        entry["action"] == "complete",
        (entry["sequence_number"] as? Number)?.toInt() == index,
        entry["phase_id"] == PLANNING_PHASE_IDS[index],
        (entry["attempt_count"] as? Number)?.toInt() == 1,
        entry["resolved_agent_id"] == null,
      ).all { it }
    }
  }

  private fun stepsSettled(existing: WorkflowStateSnapshot, expected: Map<String, String>): Boolean {
    val planningSteps = decodeWorkflowSteps(existing.stepsJson).filter { it.stepId in PLANNING_PHASE_IDS }
    return planningSteps.size == PLANNING_PHASE_IDS.size &&
      planningSteps.all { it.status == expected[it.stepId] }
  }
}

private fun expectedProvenance(request: GoalChildPlanningHydrationRequest): Map<String, Any?> = mapOf(
  "source_kind" to "imported_goal_planning",
  "parent_goal_workflow_id" to request.identity.parentGoalWorkflowId,
  "normalized_issue_key" to request.identity.normalizedIssueKey,
  "repository_identity" to request.identity.repositoryIdentity,
  "subtask_id" to request.descriptor.subtaskId,
  "manifest_order" to request.descriptor.manifestOrder,
  "governed_sub_spec_path" to request.descriptor.governedSubSpecPath,
  "sub_spec_hash" to request.descriptor.subSpecHash,
  "parent_spec_hash" to request.provenance.parentSpecHash,
  "decomposition_manifest_hash" to request.provenance.decompositionManifestHash,
  "planning_contract_id" to request.provenance.planningContractId,
  "planning_contract_version" to request.provenance.planningContractVersion,
  "phase_output_contract_id" to request.provenance.phaseOutputContractId,
  "phase_output_contract_version" to request.provenance.phaseOutputContractVersion,
)

private fun completedStep(phaseId: String): Map<String, Any?> = linkedMapOf(
  "step_id" to phaseId,
  "status" to "completed",
  "attempt_count" to 1,
)

private fun importedRecord(
  phaseId: String,
  payload: String,
  repairEvidence: skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseOutputRepairEvidence?,
  importedAt: String,
): FeatureTaskRuntimePhaseRecord = FeatureTaskRuntimePhaseRecord(
  phaseId = phaseId,
  status = "completed",
  attemptCount = 1,
  startedAt = importedAt,
  finishedAt = importedAt,
  durationMillis = 0,
  resolvedAgentId = "goal-planning-import",
  executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
  outputArtifact = payload,
  repairEvidence = repairEvidence,
)

private val PLANNING_PHASE_IDS = listOf("preplan", "plan")
