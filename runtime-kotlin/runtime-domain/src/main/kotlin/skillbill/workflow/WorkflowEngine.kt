@file:Suppress("LongMethod", "LongParameterList", "ReturnCount", "TooManyFunctions")

package skillbill.workflow

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.WorkflowContracts
import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.workflow.model.WorkflowCompactContinueView
import skillbill.workflow.model.WorkflowContinuationArtifactSummary
import skillbill.workflow.model.WorkflowContinueDecision
import skillbill.workflow.model.WorkflowContinueView
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowResumeView
import skillbill.workflow.model.WorkflowSnapshotView
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepState
import skillbill.workflow.model.WorkflowSummaryView
import skillbill.workflow.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.model.WorkflowUpdateInput
import java.math.BigDecimal
import java.math.BigInteger

/**
 * SKILL-52.2 Subtask 4: pure workflow engine. The schema-validator
 * dependency is now a domain-owned port
 * ([WorkflowSnapshotValidator]) supplied by the application boundary,
 * so this class no longer imports `skillbill.contracts.*` schema
 * validators directly. Loud-fail contract preserved verbatim at every
 * seam: `openRecord`, `updateRecord`, `snapshotView`, `summaryView`,
 * `resumeView`, `continueDecision`, plus the durable-record JSON parse
 * sites.
 *
 * Stateless helpers (enum validation, wire-shape ordering maps) live on
 * the [Companion] so existing call sites like
 * `WorkflowEngine.snapshotMap(view)` and method references like
 * `WorkflowEngine::summaryMap` keep working without an instance.
 */
class WorkflowEngine(private val schemaValidator: WorkflowSnapshotValidator) {
  /**
   * SKILL-48 Subtask 2a: builds the canonical snapshot-shape map for the
   * given [record], validates it against the workflow-state schema, and
   * returns the validated map for callers to derive their payloads from.
   *
   * SKILL-52.1: kept PRIVATE and `Map<String, Any?>`-shaped because the
   * schema validator validates against the canonical map envelope. The
   * map shape never escapes this object — every public surface returns a
   * typed view derived from this map.
   */
  private fun validatedSnapshotMap(definition: WorkflowDefinition, record: WorkflowStateSnapshot): Map<String, Any?> {
    val snapshot = linkedMapOf<String, Any?>(
      "workflow_id" to record.workflowId,
      "session_id" to record.sessionId.orEmpty(),
      "workflow_name" to record.workflowName,
      "contract_version" to record.contractVersion,
      "workflow_status" to record.workflowStatus,
      "current_step_id" to record.currentStepId.orEmpty(),
      "steps" to decodeSteps(record.stepsJson),
      "artifacts" to decodeObject(record.artifactsJson),
      "started_at" to record.startedAt.orEmpty(),
      "updated_at" to record.updatedAt.orEmpty(),
      "finished_at" to record.finishedAt.orEmpty(),
    )
    schemaValidator.validate(snapshot, definition.workflowName)
    return snapshot
  }

  fun openRecord(
    definition: WorkflowDefinition,
    workflowId: String,
    sessionId: String,
    currentStepId: String,
  ): WorkflowStateSnapshot {
    val snapshot = WorkflowStateSnapshot(
      workflowId = workflowId,
      sessionId = sessionId.trim(),
      workflowName = definition.workflowName,
      contractVersion = definition.contractVersion,
      workflowStatus = "running",
      currentStepId = currentStepId,
      stepsJson = jsonString(defaultSteps(definition, currentStepId)),
      artifactsJson = jsonString(emptyMap<String, Any?>()),
      startedAt = null,
      updatedAt = null,
      finishedAt = null,
    )
    // SKILL-48 Subtask 2a: validate the freshly-opened snapshot against the
    // canonical schema. A malformed snapshot escaping `openRecord` would
    // poison every downstream payload, so the in-process construction site
    // is the right place to loud-fail.
    validatedSnapshotMap(definition, snapshot)
    return snapshot
  }

  fun updateRecord(
    definition: WorkflowDefinition,
    existing: WorkflowStateSnapshot,
    input: WorkflowUpdateInput,
  ): WorkflowStateSnapshot {
    val existingArtifacts = decodeObject(existing.artifactsJson)
    val mergedArtifacts = LinkedHashMap(existingArtifacts)
    input.artifactsPatch?.let { patch -> mergedArtifacts.putAll(patch) }
    val terminal = input.workflowStatus in definition.terminalStatuses
    val updated = existing.copy(
      sessionId = input.sessionId.trim().ifBlank { existing.sessionId.orEmpty() },
      workflowStatus = input.workflowStatus,
      currentStepId = input.currentStepId.trim().ifBlank { existing.currentStepId.orEmpty() },
      stepsJson = jsonString(mergeStepUpdates(definition, decodeSteps(existing.stepsJson), input.stepUpdates)),
      artifactsJson = jsonString(mergedArtifacts),
      finishedAt = if (terminal) existing.finishedAt ?: "" else null,
    )
    // SKILL-48 Subtask 2a: validate the updated snapshot. `validateUpdate`
    // already loud-fails on enum/required violations, but the schema
    // catches structural drift the validator does not.
    validatedSnapshotMap(definition, updated)
    return updated
  }

  /**
   * SKILL-52.1: returns the typed snapshot view. Map-shaped derivative
   * still passes through the schema validator at construction time via
   * [validatedSnapshotMap].
   */
  fun snapshotView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowSnapshotView {
    val map = validatedSnapshotMap(definition, record)
    return snapshotViewFromMap(map)
  }

  fun summaryView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowSummaryView {
    val map = validatedSnapshotMap(definition, record)
    return WorkflowSummaryView(
      workflowId = map["workflow_id"] as String,
      sessionId = map["session_id"] as String,
      workflowName = map["workflow_name"] as String,
      contractVersion = map["contract_version"] as String,
      workflowStatus = map["workflow_status"] as String,
      currentStepId = map["current_step_id"] as String,
      startedAt = map["started_at"] as String,
      updatedAt = map["updated_at"] as String,
      finishedAt = map["finished_at"] as String,
    )
  }

  fun updateAcknowledgementView(
    snapshot: WorkflowSnapshotView,
    input: WorkflowUpdateInput,
  ): WorkflowUpdateAcknowledgementView = WorkflowUpdateAcknowledgementView(
    status = "ok",
    workflowId = snapshot.workflowId,
    workflowName = snapshot.workflowName,
    workflowStatus = snapshot.workflowStatus,
    currentStepId = snapshot.currentStepId,
    updatedStepIds = input.stepUpdates.orEmpty().mapNotNull { it["step_id"] as? String },
    updatedArtifactKeys = input.artifactsPatch.orEmpty().keys.sorted(),
    readOnlyFullStateGuidance =
    "Update returns a compact acknowledgement. Use explicit read-only workflow get/show for full state, " +
      "including steps and the complete durable artifacts map.",
  )

  fun resumeView(definition: WorkflowDefinition, record: WorkflowStateSnapshot): WorkflowResumeView {
    val snapshot = snapshotView(definition, record)
    val stepsById = snapshot.steps.associateBy { it.stepId }
    val lastCompletedStepId =
      definition.stepIds.lastOrNull { stepId -> stepsById[stepId]?.status == "completed" }.orEmpty()

    var resumeStepId = snapshot.currentStepId
    val resumeMode =
      when {
        snapshot.workflowStatus == "completed" -> "done"
        snapshot.workflowStatus in definition.terminalStatuses -> "recover"
        else -> "resume"
      }
    if (resumeMode == "resume" && stepsById[snapshot.currentStepId]?.status == "completed") {
      resumeStepId =
        definition.stepIds.firstOrNull { stepId -> stepsById[stepId]?.status in resumableStepStatuses }
          ?: snapshot.currentStepId
    }
    val availableArtifacts = snapshot.artifacts.keys.sorted()
    val requiredArtifacts = definition.requiredArtifactsByStep[resumeStepId].orEmpty()
    val missingArtifacts = requiredArtifacts.filterNot(snapshot.artifacts::containsKey)
    val canResume = resumeMode != "done" && missingArtifacts.isEmpty()
    val nextAction =
      if (resumeMode == "done") {
        "Workflow already completed. Inspect ${definition.completedTerminalSummaryArtifact} or telemetry for a summary."
      } else {
        definition.resumeActions[resumeStepId]
          ?: "Inspect workflow state, refresh missing artifacts, and continue from the current step."
      }
    return WorkflowResumeView(
      snapshot = snapshot,
      resumeMode = resumeMode,
      resumeStepId = resumeStepId,
      lastCompletedStepId = lastCompletedStepId,
      availableArtifacts = availableArtifacts,
      requiredArtifacts = requiredArtifacts,
      missingArtifacts = missingArtifacts,
      canResume = canResume,
      nextAction = nextAction,
    )
  }

  fun continueDecision(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
    sessionSummary: Map<String, Any?> = emptyMap(),
    continueStatusOverride: String? = null,
    workflowStatusBeforeContinueOverride: String? = null,
  ): WorkflowContinueDecision {
    val resume = resumeView(definition, record)
    val snapshot = resume.snapshot
    val currentStep = snapshot.steps.firstOrNull { it.stepId == resume.resumeStepId }
    val attemptCount = currentStep?.attemptCount ?: 0
    val nextAttemptCount = maxOf(attemptCount + 1, 1)
    val actualContinueStatus = continueStatusFor(snapshot, resume, currentStep)
    val continueStatus = continueStatusOverride ?: actualContinueStatus
    val workflowStatusBeforeContinue = workflowStatusBeforeContinueOverride ?: snapshot.workflowStatus
    val stepArtifactKeys = continueArtifactKeys(definition, resume.resumeStepId, snapshot.artifacts)
    val stepArtifacts = stepArtifactKeys.associateWith { snapshot.artifacts.getValue(it) }
    val currentStepArtifactKeys = resume.requiredArtifacts
    val omittedArtifactKeys = resume.availableArtifacts.filterNot(currentStepArtifactKeys::contains)
    val extraFields =
      if (definition.skillName == "bill-feature-task") {
        implementExtraFields(snapshot.artifacts)
      } else {
        emptyMap()
      }
    val continuationBrief = continuationBrief(
      definition = definition,
      workflowId = record.workflowId,
      resumeStepId = resume.resumeStepId,
      continueStatus = continueStatus,
      nextAction = resume.nextAction,
      currentStepArtifactKeys = currentStepArtifactKeys,
      omittedArtifactKeys = omittedArtifactKeys,
    )
    val continuationEntryPrompt = continuationEntryPrompt(
      definition = definition,
      workflowId = record.workflowId,
      sessionId = record.sessionId.orEmpty(),
      resumeStepId = resume.resumeStepId,
      continueStatus = continueStatus,
      currentStepArtifactKeys = currentStepArtifactKeys,
      omittedArtifactKeys = omittedArtifactKeys,
      nextAction = resume.nextAction,
      sessionSummary = sessionSummary,
      extraFields = extraFields,
      nextAttemptCount = nextAttemptCount,
    )
    val compact = compactContinueView(
      definition = definition,
      snapshot = snapshot,
      resume = resume,
      continueStatus = continueStatus,
      workflowStatusBeforeContinue = workflowStatusBeforeContinue,
      continueStepLabel = definition.stepLabels[resume.resumeStepId] ?: resume.resumeStepId,
      continueStepDirective = definition.continuationDirectives[resume.resumeStepId]
        ?: "Resume the workflow from the current step using the recovered artifacts as authoritative context.",
      continuationBrief = continuationBrief,
      continuationEntryPrompt = continuationEntryPrompt,
    )
    val view = WorkflowContinueView(
      resume = resume,
      skillName = definition.skillName,
      workflowStatusBeforeContinue = workflowStatusBeforeContinue,
      continueStatus = continueStatus,
      continueStepId = resume.resumeStepId,
      continueStepLabel = definition.stepLabels[resume.resumeStepId] ?: resume.resumeStepId,
      continueStepDirective = definition.continuationDirectives[resume.resumeStepId]
        ?: "Resume the workflow from the current step using the recovered artifacts as authoritative context.",
      referenceSections = definition.continuationReferenceSections[resume.resumeStepId].orEmpty(),
      stepArtifactKeys = stepArtifactKeys,
      stepArtifacts = stepArtifacts,
      extraFields = extraFields,
      sessionSummary = sessionSummary,
      continuationBrief = continuationBrief,
      continuationEntryPrompt = continuationEntryPrompt,
      compact = compact,
    )
    return WorkflowContinueDecision(
      view = view,
      shouldReopen = actualContinueStatus == "reopened",
      resumeStepId = resume.resumeStepId,
      nextAttemptCount = nextAttemptCount,
    )
  }

  companion object {
    private val resumableStepStatuses = setOf("running", "blocked", "pending")

    fun validateOpen(definition: WorkflowDefinition, currentStepId: String): String? =
      validateEnum(currentStepId, definition.stepIds, "current_step_id")

    fun validateUpdate(definition: WorkflowDefinition, input: WorkflowUpdateInput): String? {
      validateEnum(input.workflowStatus, definition.workflowStatuses, "workflow_status")?.let { return it }
      if (input.currentStepId.isNotBlank()) {
        validateEnum(input.currentStepId, definition.stepIds, "current_step_id")?.let { return it }
      }
      input.stepUpdates?.let { updates ->
        val seenStepIds = mutableSetOf<String>()
        updates.forEachIndexed { index, update ->
          val stepId = update["step_id"] as? String
          if (stepId.isNullOrBlank()) {
            return "step_updates[$index].step_id must be a non-empty string."
          }
          validateEnum(stepId, definition.stepIds, "step_updates.step_id")?.let { return it }
          if (!seenStepIds.add(stepId)) {
            return "Duplicate step_id '$stepId' in step_updates."
          }
          val status = update["status"] as? String
          if (status.isNullOrBlank()) {
            return "step_updates[$index].status must be a non-empty string."
          }
          validateEnum(status, definition.stepStatuses, "step_updates.status")?.let { return it }
          val attemptCount = update["attempt_count"].asExactIntOrNull()
          if (attemptCount == null || attemptCount < 0) {
            return "step_updates[$index].attempt_count must be an integer >= 0."
          }
        }
      }
      return null
    }

    /**
     * SKILL-52.1: open-boundary serializer that produces the wire-shape
     * ordered map for a snapshot view. Used by CLI/MCP adapter mappers
     * to preserve byte-equivalent JSON payloads against goldens. The map
     * shape is locked by `WorkflowContracts.fullWorkflowPayload`.
     */
    @OpenBoundaryMap("Wire-shape ordered snapshot map for CLI/MCP adapters")
    fun snapshotMap(view: WorkflowSnapshotView): Map<String, Any?> = WorkflowContracts.fullWorkflowPayload(
      linkedMapOf(
        "workflow_id" to view.workflowId,
        "session_id" to view.sessionId,
        "workflow_name" to view.workflowName,
        "contract_version" to view.contractVersion,
        "workflow_status" to view.workflowStatus,
        "current_step_id" to view.currentStepId,
        "steps" to view.steps.map(::stepMap),
        "artifacts" to view.artifacts,
        "started_at" to view.startedAt,
        "updated_at" to view.updatedAt,
        "finished_at" to view.finishedAt,
      ),
    )

    @OpenBoundaryMap("Wire-shape ordered summary map for CLI/MCP adapters")
    fun summaryMap(view: WorkflowSummaryView): Map<String, Any?> = WorkflowContracts.summaryWorkflowPayload(
      linkedMapOf(
        "workflow_id" to view.workflowId,
        "session_id" to view.sessionId,
        "workflow_name" to view.workflowName,
        "contract_version" to view.contractVersion,
        "workflow_status" to view.workflowStatus,
        "current_step_id" to view.currentStepId,
        "started_at" to view.startedAt,
        "updated_at" to view.updatedAt,
        "finished_at" to view.finishedAt,
      ),
    )

    @OpenBoundaryMap("Wire-shape ordered resume map for CLI/MCP adapters")
    fun resumeMap(view: WorkflowResumeView): Map<String, Any?> = WorkflowContracts.resumePayload(
      snapshotMap(view.snapshot),
      linkedMapOf(
        "resume_mode" to view.resumeMode,
        "resume_step_id" to view.resumeStepId,
        "last_completed_step_id" to view.lastCompletedStepId,
        "available_artifacts" to view.availableArtifacts,
        "required_artifacts" to view.requiredArtifacts,
        "missing_artifacts" to view.missingArtifacts,
        "can_resume" to view.canResume,
        "next_action" to view.nextAction,
      ),
    )

    @OpenBoundaryMap("Wire-shape ordered continue map for CLI/MCP adapters")
    fun continueMap(view: WorkflowContinueView): Map<String, Any?> = WorkflowContracts.continuePayload(
      resumeMap(view.resume),
      linkedMapOf(
        "skill_name" to view.skillName,
        "workflow_status_before_continue" to view.workflowStatusBeforeContinue,
        "continue_status" to view.continueStatus,
        "continue_step_id" to view.continueStepId,
        "continue_step_label" to view.continueStepLabel,
        "continue_step_directive" to view.continueStepDirective,
        "reference_sections" to view.referenceSections,
        "step_artifact_keys" to view.stepArtifactKeys,
        "step_artifacts" to view.stepArtifacts,
        "session_summary" to view.sessionSummary,
        "continuation_brief" to view.continuationBrief,
        "continuation_entry_prompt" to view.continuationEntryPrompt,
        "extra_fields" to view.extraFields,
      ),
    )

    @OpenBoundaryMap("Compact wire-shape ordered continue map for CLI/MCP adapters")
    fun compactContinueMap(view: WorkflowCompactContinueView): Map<String, Any?> = linkedMapOf(
      "workflow_id" to view.workflowId,
      "skill_name" to view.skillName,
      "workflow_status_before_continue" to view.workflowStatusBeforeContinue,
      "started_at" to view.startedAt,
      "updated_at" to view.updatedAt,
      "continue_status" to view.continueStatus,
      "resume_step_id" to view.resumeStepId,
      "resume_step_label" to view.resumeStepLabel,
      "continue_step_id" to view.resumeStepId,
      "continue_step_label" to view.resumeStepLabel,
      "continue_step_directive" to view.continueStepDirective,
      "reference_sections" to view.referenceSections,
      "required_artifact_keys" to view.requiredArtifactKeys,
      "available_artifact_keys" to view.availableArtifactKeys,
      "missing_artifact_keys" to view.missingArtifactKeys,
      "required_artifacts" to view.requiredArtifactKeys,
      "available_artifacts" to view.availableArtifactKeys,
      "missing_artifacts" to view.missingArtifactKeys,
      "current_step_artifacts" to view.currentStepArtifacts.map(::artifactSummaryMap),
      "omitted_artifact_keys" to view.omittedArtifactKeys,
      "continuation_brief" to view.continuationBrief,
      "continuation_entry_prompt" to view.continuationEntryPrompt,
      "read_only_full_state_guidance" to view.readOnlyFullStateGuidance,
    )

    @OpenBoundaryMap("Compact wire-shape ordered workflow-update acknowledgement map for CLI/MCP adapters")
    fun updateAcknowledgementMap(view: WorkflowUpdateAcknowledgementView): Map<String, Any?> = linkedMapOf(
      "status" to view.status,
      "workflow_id" to view.workflowId,
      "workflow_name" to view.workflowName,
      "workflow_status" to view.workflowStatus,
      "current_step_id" to view.currentStepId,
      "updated_step_ids" to view.updatedStepIds,
      "updated_artifact_keys" to view.updatedArtifactKeys,
      "read_only_full_state_guidance" to view.readOnlyFullStateGuidance,
    )

    private fun snapshotViewFromMap(map: Map<String, Any?>): WorkflowSnapshotView {
      @Suppress("UNCHECKED_CAST")
      val rawSteps = map["steps"] as List<Map<String, Any?>>

      @Suppress("UNCHECKED_CAST")
      val artifacts = map["artifacts"] as Map<String, Any?>
      val steps = rawSteps.map { stepMap ->
        WorkflowStepState(
          stepId = stepMap["step_id"] as String,
          status = stepMap["status"] as String,
          attemptCount = stepMap["attempt_count"].asExactIntOrNull()
            ?: throw InvalidWorkflowStateSchemaError(
              "Workflow state step attempt_count must decode to an integer.",
            ),
        )
      }
      return WorkflowSnapshotView(
        workflowId = map["workflow_id"] as String,
        sessionId = map["session_id"] as String,
        workflowName = map["workflow_name"] as String,
        contractVersion = map["contract_version"] as String,
        workflowStatus = map["workflow_status"] as String,
        currentStepId = map["current_step_id"] as String,
        steps = steps,
        artifacts = artifacts,
        startedAt = map["started_at"] as String,
        updatedAt = map["updated_at"] as String,
        finishedAt = map["finished_at"] as String,
      )
    }

    private fun stepMap(step: WorkflowStepState): Map<String, Any?> = linkedMapOf(
      "step_id" to step.stepId,
      "status" to step.status,
      "attempt_count" to step.attemptCount,
    )

    private fun defaultSteps(definition: WorkflowDefinition, initialStepId: String): List<Map<String, Any?>> {
      var seenInitial = false
      return definition.stepIds.map { stepId ->
        when {
          stepId == initialStepId -> {
            seenInitial = true
            step(stepId, "running", 1)
          }
          definition.openPriorStepsCompleted && !seenInitial -> step(stepId, "completed", 1)
          else -> step(stepId, "pending", 0)
        }
      }
    }

    private fun mergeStepUpdates(
      definition: WorkflowDefinition,
      existingSteps: List<Map<String, Any?>>,
      stepUpdates: List<Map<String, Any?>>?,
    ): List<Map<String, Any?>> {
      if (stepUpdates == null) {
        return existingSteps
      }
      val byStepId = existingSteps.associateByTo(LinkedHashMap()) { it["step_id"].toString() }
      stepUpdates.forEach { update ->
        val stepId = update["step_id"].toString()
        val attemptCount = requireNotNull(update["attempt_count"].asExactIntOrNull()) {
          "step_updates.attempt_count must be an integer >= 0."
        }
        require(attemptCount >= 0) {
          "step_updates.attempt_count must be an integer >= 0."
        }
        byStepId[stepId] = step(stepId, update["status"].toString(), attemptCount)
      }
      return definition.stepIds.mapNotNull(byStepId::get)
    }

    private fun continueStatusFor(
      snapshot: WorkflowSnapshotView,
      resume: WorkflowResumeView,
      currentStep: WorkflowStepState?,
    ): String {
      val alreadyRunning =
        snapshot.workflowStatus == "running" &&
          snapshot.currentStepId == resume.resumeStepId &&
          currentStep?.status == "running"
      return when {
        resume.resumeMode == "done" -> "done"
        resume.canResume && alreadyRunning -> "already_running"
        resume.canResume -> "reopened"
        else -> "blocked"
      }
    }

    private fun continueArtifactKeys(
      definition: WorkflowDefinition,
      resumeStepId: String,
      artifacts: Map<String, Any?>,
    ): List<String> {
      val keys = mutableListOf<String>()
      definition.continuationArtifactOrder.forEach { key ->
        if (key in artifacts) {
          keys += key
        }
      }
      definition.requiredArtifactsByStep[resumeStepId].orEmpty().forEach { key ->
        if (key in artifacts && key !in keys) {
          keys += key
        }
      }
      return keys
    }

    private fun compactContinueView(
      definition: WorkflowDefinition,
      snapshot: WorkflowSnapshotView,
      resume: WorkflowResumeView,
      continueStatus: String,
      workflowStatusBeforeContinue: String,
      continueStepLabel: String,
      continueStepDirective: String,
      continuationBrief: String,
      continuationEntryPrompt: String,
    ): WorkflowCompactContinueView {
      val requiredKeys = resume.requiredArtifacts
      val availableKeys = resume.availableArtifacts
      val currentStepArtifactKeys = requiredKeys
      val currentStepArtifacts = currentStepArtifactKeys.map { key ->
        artifactSummary(key, snapshot.artifacts[key], key in snapshot.artifacts)
      }
      val omittedKeys = availableKeys.filterNot(currentStepArtifactKeys::contains)
      return WorkflowCompactContinueView(
        workflowId = snapshot.workflowId,
        skillName = definition.skillName,
        continueStatus = continueStatus,
        workflowStatusBeforeContinue = workflowStatusBeforeContinue,
        startedAt = snapshot.startedAt,
        updatedAt = snapshot.updatedAt,
        resumeStepId = resume.resumeStepId,
        resumeStepLabel = continueStepLabel,
        continueStepDirective = continueStepDirective,
        referenceSections = definition.continuationReferenceSections[resume.resumeStepId].orEmpty(),
        requiredArtifactKeys = requiredKeys,
        availableArtifactKeys = availableKeys,
        missingArtifactKeys = resume.missingArtifacts,
        currentStepArtifacts = currentStepArtifacts,
        omittedArtifactKeys = omittedKeys,
        continuationBrief = continuationBrief,
        continuationEntryPrompt = continuationEntryPrompt,
        readOnlyFullStateGuidance =
        "Use workflow show for read-only full-state inspection, including the complete durable artifacts map.",
      )
    }

    private fun artifactSummary(key: String, value: Any?, present: Boolean): WorkflowContinuationArtifactSummary {
      if (!present) {
        return WorkflowContinuationArtifactSummary(
          key = key,
          present = false,
          inline = false,
          sizeBytes = null,
          value = null,
          preview = null,
          truncated = false,
          omitted = true,
          omissionReason = "missing_required_artifact",
        )
      }
      val serialized = jsonString(value)
      val sizeBytes = serialized.toByteArray(Charsets.UTF_8).size
      val inline = sizeBytes <= COMPACT_ARTIFACT_INLINE_MAX_BYTES
      return WorkflowContinuationArtifactSummary(
        key = key,
        present = true,
        inline = inline,
        sizeBytes = sizeBytes,
        value = if (inline) value else null,
        preview = if (inline) null else serialized.take(COMPACT_ARTIFACT_PREVIEW_CHARS),
        truncated = !inline && serialized.length > COMPACT_ARTIFACT_PREVIEW_CHARS,
        omitted = !inline,
        omissionReason = if (inline) null else "artifact_exceeds_inline_limit",
      )
    }

    private fun artifactSummaryMap(summary: WorkflowContinuationArtifactSummary): Map<String, Any?> = linkedMapOf(
      "key" to summary.key,
      "present" to summary.present,
      "inline" to summary.inline,
      "size_bytes" to summary.sizeBytes,
      "value" to summary.value,
      "preview" to summary.preview,
      "truncated" to summary.truncated,
      "omitted" to summary.omitted,
      "omission_reason" to summary.omissionReason,
    )

    private fun continuationBrief(
      definition: WorkflowDefinition,
      workflowId: String,
      resumeStepId: String,
      continueStatus: String,
      nextAction: String,
      currentStepArtifactKeys: List<String>,
      omittedArtifactKeys: List<String>,
    ): String {
      val stepLabel = definition.stepLabels[resumeStepId] ?: resumeStepId
      val currentArtifacts = currentStepArtifactKeys.joinToString().ifBlank { "none" }
      val omittedArtifacts = omittedArtifactKeys.joinToString().ifBlank { "none" }
      val instructionPath =
        if (definition.skillName == "bill-feature-task") {
          "`skills/bill-feature-task/content.md`"
        } else {
          "`skills/bill-feature-verify/content.md`"
        }
      return "Resume `${definition.skillName}` workflow `$workflowId` from `$stepLabel` (`$resumeStepId`). " +
        "Follow the normal step instructions in $instructionPath. " +
        "Use `current_step_artifacts` in this compact payload ($currentArtifacts) as authoritative " +
        "current-step context instead of reconstructing prior context from chat history. " +
        "Omitted artifact keys ($omittedArtifacts) " +
        "require read-only inspection with `workflow show` when needed. Workflow activation status: " +
        "`$continueStatus`. Next action: $nextAction"
    }

    private fun continuationEntryPrompt(
      definition: WorkflowDefinition,
      workflowId: String,
      sessionId: String,
      resumeStepId: String,
      continueStatus: String,
      currentStepArtifactKeys: List<String>,
      omittedArtifactKeys: List<String>,
      nextAction: String,
      sessionSummary: Map<String, Any?>,
      extraFields: Map<String, Any?>,
      nextAttemptCount: Int,
    ): String {
      val references = definition.continuationReferenceSections[resumeStepId].orEmpty().joinToString("; ")
      val directive =
        definition.continuationDirectives[resumeStepId]
          ?: (
            "Resume the workflow from the recovered current step using the persisted artifacts as " +
              "authoritative context."
            )
      val currentArtifacts = currentStepArtifactKeys.joinToString().ifBlank { "none" }
      val omittedArtifacts = omittedArtifactKeys.joinToString().ifBlank { "none" }
      val commonLines =
        mutableListOf(
          "Use `${definition.skillName}` in continuation mode.",
          "Workflow id: $workflowId",
          "Session id: ${sessionId.ifBlank { "(none)" }}",
          "Continue status: $continueStatus",
          "Resume step: $resumeStepId (${definition.stepLabels[resumeStepId] ?: resumeStepId})",
        )
      if (definition.skillName == "bill-feature-task") {
        commonLines += "Feature: ${(extraFields["feature_name"] as String).ifBlank { "(unknown)" }}"
        commonLines += "Feature size: ${(extraFields["feature_size"] as String).ifBlank { "(unknown)" }}"
        commonLines += "Branch: ${(extraFields["branch_name"] as String).ifBlank { "(unknown)" }}"
      }
      commonLines += "Current-step artifacts: $currentArtifacts"
      commonLines += "Omitted artifact keys: $omittedArtifacts"
      if (definition.skillName == "bill-feature-verify") {
        commonLines += "Acceptance criteria count: ${sessionSummary["acceptance_criteria_count"] ?: 0}"
        commonLines += "Rollout relevant: ${sessionSummary["rollout_relevant"] ?: false}"
      }
      val specSummary = sessionSummary["spec_summary"]?.toString()?.ifBlank { "(none saved)" } ?: "(none saved)"
      commonLines += "Spec summary: $specSummary"
      commonLines += "Reference sections: ${references.ifBlank { "normal step instructions only" }}"
      commonLines +=
        "Rules: do not rerun completed steps unless the workflow sends work backwards; treat " +
        "`current_step_artifacts` as authoritative and inspect omitted keys with read-only workflow show when needed."
      commonLines +=
        "Workflow update rule: every step_updates item must include step_id, status, and integer " +
        "attempt_count; use attempt_count $nextAttemptCount for `$resumeStepId` unless a later retry increments it."
      commonLines += "Keep the same workflow_id and session_id, then continue `${definition.skillName}`."
      commonLines += "Step directive: $directive"
      commonLines += "Immediate next action: $nextAction"
      return commonLines.joinToString("\n")
    }

    private fun implementExtraFields(artifacts: Map<String, Any?>): Map<String, Any?> {
      val assessment = artifacts["assessment"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
      val branch = artifacts["branch"]
      val branchName =
        when (branch) {
          is Map<*, *> -> branch["branch_name"]?.toString().orEmpty().trim()
          is String -> branch.trim()
          else -> ""
        }
      return linkedMapOf(
        "feature_name" to assessment["feature_name"].toStringOrEmpty(),
        "feature_size" to assessment["feature_size"].toStringOrEmpty(),
        "branch_name" to branchName,
      )
    }

    private fun step(stepId: String, status: String, attemptCount: Int): Map<String, Any?> =
      linkedMapOf("step_id" to stepId, "status" to status, "attempt_count" to attemptCount)

    private fun decodeSteps(rawValue: String): List<Map<String, Any?>> {
      val parsed = parseDurableJson(rawValue, "stepsJson")
      if (parsed !is JsonArray) {
        throw InvalidWorkflowStateSchemaError("Workflow state stepsJson must decode to a JSON array.")
      }
      return parsed.mapIndexed { index, element ->
        JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(element))
          ?: throw InvalidWorkflowStateSchemaError(
            "Workflow state stepsJson[$index] must decode to a JSON object.",
          )
      }
    }

    private fun decodeObject(rawValue: String): Map<String, Any?> {
      val parsed = parseDurableJson(rawValue, "artifactsJson")
      if (parsed !is JsonObject) {
        throw InvalidWorkflowStateSchemaError("Workflow state artifactsJson must decode to a JSON object.")
      }
      return JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(parsed))
        ?: throw InvalidWorkflowStateSchemaError("Workflow state artifactsJson must decode to a JSON object.")
    }

    private fun parseDurableJson(rawValue: String, fieldName: String): JsonElement = try {
      JsonSupport.json.parseToJsonElement(rawValue)
    } catch (error: SerializationException) {
      throw InvalidWorkflowStateSchemaError("Workflow state $fieldName contains malformed JSON.", error)
    } catch (error: IllegalArgumentException) {
      throw InvalidWorkflowStateSchemaError("Workflow state $fieldName contains malformed JSON.", error)
    }

    private fun jsonString(value: Any?): String = JsonSupport.json.encodeToString(
      kotlinx.serialization.json.JsonElement.serializer(),
      JsonSupport.valueToJsonElement(value),
    )

    private fun validateEnum(value: String, allowed: Collection<String>, fieldName: String): String? =
      if (value !in allowed) {
        "Invalid $fieldName '$value'. Allowed: ${allowed.joinToString()}"
      } else {
        null
      }

    private fun Any?.toStringOrEmpty(): String = this?.toString().orEmpty()

    private fun Any?.asExactIntOrNull(): Int? = when (this) {
      is Byte -> toInt()
      is Short -> toInt()
      is Int -> this
      is Long -> intValueExactOrNull()
      is BigInteger -> intValueExactOrNull()
      is BigDecimal -> intValueExactOrNull()
      is Float -> intValueExactOrNull()
      is Double -> intValueExactOrNull()
      else -> null
    }

    private fun Long.intValueExactOrNull(): Int? =
      takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    private fun BigInteger.intValueExactOrNull(): Int? = try {
      intValueExact()
    } catch (_: ArithmeticException) {
      null
    }

    private fun BigDecimal.intValueExactOrNull(): Int? = try {
      intValueExact()
    } catch (_: ArithmeticException) {
      null
    }

    private fun Float.intValueExactOrNull(): Int? {
      if (!isFinite() || toDouble() < Int.MIN_VALUE.toDouble() || toDouble() > Int.MAX_VALUE.toDouble()) {
        return null
      }
      val intValue = toInt()
      return intValue.takeIf { it.toFloat() == this }
    }

    private fun Double.intValueExactOrNull(): Int? {
      if (!isFinite() || this < Int.MIN_VALUE.toDouble() || this > Int.MAX_VALUE.toDouble()) {
        return null
      }
      val intValue = toInt()
      return intValue.takeIf { it.toDouble() == this }
    }
  }
}

private const val COMPACT_ARTIFACT_INLINE_MAX_BYTES = 4096
private const val COMPACT_ARTIFACT_PREVIEW_CHARS = 1024
