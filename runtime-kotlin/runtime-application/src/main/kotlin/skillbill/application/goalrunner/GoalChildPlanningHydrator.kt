package skillbill.application.goalrunner

import skillbill.application.decomposition.decodeArtifacts
import skillbill.application.featuretask.sha256HexUtf8
import skillbill.error.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.ports.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint
import skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
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

internal class GoalChildPlanningHydrator(
  phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
) {
  private val payloadValidator = PreparedPlanningPayloadValidator(phaseOutputValidator)
  private val importMatcher = GoalChildPlanningImportMatcher(payloadValidator)

  fun hydrate(
    unitOfWork: UnitOfWork,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydration {
    val prepared = loadRequiredPreparation(unitOfWork, setup, request)
    requireMatchingPreparation(setup, request, prepared)
    payloadValidator.requireValid(
      "preplan",
      prepared.shared.preplanPayload,
      prepared.shared.payloadSha256,
      setup.workflowId,
    )
    payloadValidator.requireValid("plan", prepared.plan.planPayload, prepared.plan.payloadSha256, setup.workflowId)
    return createHydration(request, prepared)
  }

  fun requireMatchingImport(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val request = requireNotNull(setup.planningHydration) {
      "Prepared goal child '${setup.subtaskId}' requires planning hydration."
    }
    if (!importMatcher.matches(unitOfWork, existing, setup, request)) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        request.identity.parentGoalWorkflowId,
        setup.subtaskId,
        "existing child planning import conflicts with request",
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
  ): GoalChildPlanningHydration {
    val importedAt = Instant.now().toString()
    val records = createImportedRecords(prepared, importedAt)
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

  private fun createImportedRecords(
    prepared: PreparedGoalPlanning,
    importedAt: String,
  ): Map<String, Map<String, Any?>> = linkedMapOf(
    "preplan" to importedRecord("preplan", prepared.shared.preplanPayload, importedAt).toArtifactMap(),
    "plan" to importedRecord("plan", prepared.plan.planPayload, importedAt).toArtifactMap(),
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
  private val phaseOutputValidator: FeatureTaskRuntimePhaseOutputValidator,
) {
  fun requireValid(phaseId: String, payload: String, expectedDigest: String, workflowId: String) {
    if (sha256HexUtf8(payload) != expectedDigest) {
      throw InvalidGoalPlanningPreparationSchemaError(
        workflowId,
        "$phaseId.payload_sha256",
        "payload digest differs",
      )
    }
    val decoded = phaseOutputValidator.validateAndReadPhaseOutput(payload, phaseId)
    val payloadMatches = listOf(
      decoded["phase_id"] == phaseId,
      decoded["status"] == "completed",
      (decoded["produced_outputs"] as? Map<*, *>)?.isEmpty() == false,
    ).all { it }
    if (!payloadMatches) {
      throw InvalidGoalPlanningPreparationSchemaError(
        workflowId,
        "$phaseId.payload",
        "imported phase output must match its phase and be completed with non-empty produced_outputs",
      )
    }
  }
}

private class GoalChildPlanningImportMatcher(
  private val payloadValidator: PreparedPlanningPayloadValidator,
) {
  fun matches(
    unitOfWork: UnitOfWork,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): Boolean {
    val artifacts = decodeArtifacts(existing.artifactsJson)
    val expected = artifacts[FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT_ARTIFACT_KEY] as? Map<*, *>
      ?: return false
    val shared = unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)
    val plan = unitOfWork.goalPlanningPreparations.findSubtaskPlan(
      request.identity,
      request.descriptor.subtaskId,
      request.descriptor.governedSubSpecPath,
    )
    validateAvailablePayloads(shared, plan, setup.workflowId)
    return listOf(
      provenanceMatches(expected, request),
      preparedMatches(shared, plan, expected, request),
      recordsMatch(artifacts, expected, shared, plan),
      ledgerMatches(artifacts),
      stepsMatch(existing),
    ).all { it }
  }

  private fun validateAvailablePayloads(
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
    workflowId: String,
  ) {
    shared?.let {
      payloadValidator.requireValid("preplan", it.preplanPayload, it.payloadSha256, workflowId)
    }
    plan?.let {
      payloadValidator.requireValid("plan", it.planPayload, it.payloadSha256, workflowId)
    }
  }

  private fun provenanceMatches(expected: Map<*, *>, request: GoalChildPlanningHydrationRequest): Boolean =
    expectedProvenance(request).all { (key, value) -> expected[key] == value }

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

  private fun recordsMatch(
    artifacts: Map<String, Any?>,
    expected: Map<*, *>,
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
  ): Boolean {
    val records = artifacts[FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY] as? Map<*, *>
      ?: return false
    val payloads = mapOf("preplan" to shared?.preplanPayload, "plan" to plan?.planPayload)
    return PLANNING_PHASE_IDS.all { phaseId ->
      val record = records[phaseId] as? Map<*, *> ?: return@all false
      recordMatches(phaseId, record, payloads[phaseId], expected)
    }
  }

  private fun recordMatches(
    phaseId: String,
    record: Map<*, *>,
    preparedPayload: String?,
    expected: Map<*, *>,
  ): Boolean {
    val output = record["output_artifact"] as? String ?: return false
    return listOf(
      record["phase_id"] == phaseId,
      record["status"] == "completed",
      (record["attempt_count"] as? Number)?.toInt() == 1,
      record["resolved_agent_id"] == "goal-planning-import",
      output == preparedPayload,
      sha256HexUtf8(output) == expected["${phaseId}_payload_sha256"],
      (record["duration_millis"] as? Number)?.toLong() == 0L,
      record["started_at"] == record["finished_at"],
    ).all { it }
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

  private fun stepsMatch(existing: WorkflowStateSnapshot): Boolean {
    val planningSteps = decodeWorkflowSteps(existing.stepsJson).filter { it.stepId in PLANNING_PHASE_IDS }
    return planningSteps.size == PLANNING_PHASE_IDS.size && planningSteps.all {
      it.status == "completed" && it.attemptCount == 1
    }
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

private fun importedRecord(phaseId: String, payload: String, importedAt: String): FeatureTaskRuntimePhaseRecord =
  FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = "completed",
    attemptCount = 1,
    startedAt = importedAt,
    finishedAt = importedAt,
    durationMillis = 0,
    resolvedAgentId = "goal-planning-import",
    executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
    outputArtifact = payload,
  )

private val PLANNING_PHASE_IDS = listOf("preplan", "plan")
