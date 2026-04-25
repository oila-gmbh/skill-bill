@file:Suppress("LongMethod", "LongParameterList", "ReturnCount", "TooManyFunctions")

package skillbill.workflow

import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.WorkflowContracts
import skillbill.workflow.model.WorkflowContinueDecision
import skillbill.workflow.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowUpdateInput

object WorkflowEngine {
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
        val attemptCount = update["attempt_count"] as? Number
        if (attemptCount == null || attemptCount.toInt() < 0) {
          return "step_updates[$index].attempt_count must be an integer >= 0."
        }
      }
    }
    return null
  }

  fun openRecord(
    definition: WorkflowDefinition,
    workflowId: String,
    sessionId: String,
    currentStepId: String,
  ): WorkflowStateSnapshot = WorkflowStateSnapshot(
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

  fun updateRecord(
    definition: WorkflowDefinition,
    existing: WorkflowStateSnapshot,
    input: WorkflowUpdateInput,
  ): WorkflowStateSnapshot {
    val existingArtifacts = decodeObject(existing.artifactsJson)
    val mergedArtifacts = LinkedHashMap(existingArtifacts)
    input.artifactsPatch?.let { patch -> mergedArtifacts.putAll(patch) }
    val terminal = input.workflowStatus in definition.terminalStatuses
    return existing.copy(
      sessionId = input.sessionId.trim().ifBlank { existing.sessionId.orEmpty() },
      workflowStatus = input.workflowStatus,
      currentStepId = input.currentStepId.trim().ifBlank { existing.currentStepId.orEmpty() },
      stepsJson = jsonString(mergeStepUpdates(definition, decodeSteps(existing.stepsJson), input.stepUpdates)),
      artifactsJson = jsonString(mergedArtifacts),
      finishedAt = if (terminal) existing.finishedAt ?: "" else null,
    )
  }

  fun fullPayload(definition: WorkflowDefinition, record: WorkflowStateSnapshot): Map<String, Any?> =
    WorkflowContracts.fullWorkflowPayload(
      mapOf(
        "workflow_id" to record.workflowId,
        "session_id" to record.sessionId.orEmpty(),
        "workflow_name" to record.workflowName.ifBlank { definition.workflowName },
        "contract_version" to record.contractVersion.ifBlank { definition.contractVersion },
        "workflow_status" to record.workflowStatus.ifBlank { "pending" },
        "current_step_id" to record.currentStepId.orEmpty(),
        "steps" to decodeSteps(record.stepsJson),
        "artifacts" to decodeObject(record.artifactsJson),
        "started_at" to record.startedAt.orEmpty(),
        "updated_at" to record.updatedAt.orEmpty(),
        "finished_at" to record.finishedAt.orEmpty(),
      ),
    )

  fun summaryPayload(definition: WorkflowDefinition, record: WorkflowStateSnapshot): Map<String, Any?> =
    WorkflowContracts.summaryWorkflowPayload(
      mapOf(
        "workflow_id" to record.workflowId,
        "session_id" to record.sessionId.orEmpty(),
        "workflow_name" to record.workflowName.ifBlank { definition.workflowName },
        "contract_version" to record.contractVersion.ifBlank { definition.contractVersion },
        "workflow_status" to record.workflowStatus.ifBlank { "pending" },
        "current_step_id" to record.currentStepId.orEmpty(),
        "started_at" to record.startedAt.orEmpty(),
        "updated_at" to record.updatedAt.orEmpty(),
        "finished_at" to record.finishedAt.orEmpty(),
      ),
    )

  fun resumePayload(definition: WorkflowDefinition, record: WorkflowStateSnapshot): Map<String, Any?> {
    val payload = fullPayload(definition, record)
    val workflowStatus = payload["workflow_status"] as String
    val currentStepId = payload["current_step_id"] as String
    val steps = payload["steps"].asStepList()
    val artifacts = payload["artifacts"].asStringAnyMap()
    val stepsById = steps.associateBy { it["step_id"].toString() }
    val lastCompletedStepId =
      definition.stepIds.lastOrNull { stepId -> stepsById[stepId]?.get("status") == "completed" }.orEmpty()

    var resumeStepId = currentStepId
    val resumeMode =
      when {
        workflowStatus == "completed" -> "done"
        workflowStatus in definition.terminalStatuses -> "recover"
        else -> "resume"
      }
    if (resumeMode == "resume" && stepsById[currentStepId]?.get("status") == "completed") {
      resumeStepId =
        definition.stepIds.firstOrNull { stepId -> stepsById[stepId]?.get("status") in resumableStepStatuses }
          ?: currentStepId
    }
    val availableArtifacts = artifacts.keys.sorted()
    val requiredArtifacts = definition.requiredArtifactsByStep[resumeStepId].orEmpty()
    val missingArtifacts = requiredArtifacts.filterNot(artifacts::containsKey)
    val canResume = resumeMode != "done" && missingArtifacts.isEmpty()
    val nextAction =
      if (resumeMode == "done") {
        "Workflow already completed. Inspect ${definition.completedTerminalSummaryArtifact} or telemetry for a summary."
      } else {
        definition.resumeActions[resumeStepId]
          ?: "Inspect workflow state, refresh missing artifacts, and continue from the current step."
      }
    return WorkflowContracts.resumePayload(
      payload,
      mapOf(
        "resume_mode" to resumeMode,
        "resume_step_id" to resumeStepId,
        "last_completed_step_id" to lastCompletedStepId,
        "available_artifacts" to availableArtifacts,
        "required_artifacts" to requiredArtifacts,
        "missing_artifacts" to missingArtifacts,
        "can_resume" to canResume,
        "next_action" to nextAction,
      ),
    )
  }

  fun continueDecision(
    definition: WorkflowDefinition,
    record: WorkflowStateSnapshot,
    sessionSummary: Map<String, Any?> = emptyMap(),
  ): WorkflowContinueDecision {
    val resumePayload = resumePayload(definition, record)
    val workflowStatusBefore = resumePayload["workflow_status"] as String
    val resumeMode = resumePayload["resume_mode"] as String
    val resumeStepId = resumePayload["resume_step_id"] as String
    val currentStepId = resumePayload["current_step_id"] as String
    val artifacts = resumePayload["artifacts"].asStringAnyMap()
    val steps = resumePayload["steps"].asStepList()
    val canResume = resumePayload["can_resume"] as Boolean
    val stepArtifacts = continueArtifactKeys(definition, resumeStepId, artifacts)
    val currentStep = steps.firstOrNull { it["step_id"] == resumeStepId }.orEmpty()
    val attemptCount = (currentStep["attempt_count"] as? Number)?.toInt() ?: 0
    val alreadyRunning =
      workflowStatusBefore == "running" &&
        currentStepId == resumeStepId &&
        currentStep["status"] == "running"
    val continueStatus =
      when {
        resumeMode == "done" -> "done"
        canResume && alreadyRunning -> "already_running"
        canResume -> "reopened"
        else -> "blocked"
      }
    val extraFields =
      if (definition.skillName == "bill-feature-implement") {
        implementExtraFields(artifacts)
      } else {
        emptyMap()
      }
    val payload =
      WorkflowContracts.continuePayload(
        resumePayload,
        mapOf(
          "skill_name" to definition.skillName,
          "workflow_status_before_continue" to workflowStatusBefore,
          "continue_status" to continueStatus,
          "continue_step_id" to resumeStepId,
          "continue_step_label" to (definition.stepLabels[resumeStepId] ?: resumeStepId),
          "continue_step_directive" to (
            definition.continuationDirectives[resumeStepId]
              ?: "Resume the workflow from the current step using the recovered artifacts as authoritative context."
            ),
          "reference_sections" to definition.continuationReferenceSections[resumeStepId].orEmpty(),
          "step_artifact_keys" to stepArtifacts,
          "step_artifacts" to stepArtifacts.associateWith { artifacts.getValue(it) },
          "session_summary" to sessionSummary,
          "continuation_brief" to
            continuationBrief(
              definition = definition,
              workflowId = record.workflowId,
              resumeStepId = resumeStepId,
              continueStatus = continueStatus,
              nextAction = resumePayload["next_action"].toString(),
              artifactKeys = stepArtifacts,
            ),
          "continuation_entry_prompt" to
            continuationEntryPrompt(
              definition = definition,
              workflowId = record.workflowId,
              sessionId = record.sessionId.orEmpty(),
              resumeStepId = resumeStepId,
              continueStatus = continueStatus,
              artifactKeys = stepArtifacts,
              nextAction = resumePayload["next_action"].toString(),
              sessionSummary = sessionSummary,
              extraFields = extraFields,
            ),
          "extra_fields" to extraFields,
        ),
      )
    return WorkflowContinueDecision(
      payload = payload,
      shouldReopen = continueStatus == "reopened",
      resumeStepId = resumeStepId,
      nextAttemptCount = maxOf(attemptCount + 1, 1),
    )
  }

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
      byStepId[stepId] = step(stepId, update["status"].toString(), (update["attempt_count"] as Number).toInt())
    }
    return definition.stepIds.mapNotNull(byStepId::get)
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

  private fun continuationBrief(
    definition: WorkflowDefinition,
    workflowId: String,
    resumeStepId: String,
    continueStatus: String,
    nextAction: String,
    artifactKeys: List<String>,
  ): String {
    val stepLabel = definition.stepLabels[resumeStepId] ?: resumeStepId
    val artifacts = artifactKeys.joinToString().ifBlank { "none" }
    val instructionPath =
      if (definition.skillName == "bill-feature-implement") {
        "`skills/bill-feature-implement/SKILL.md` and `skills/bill-feature-implement/reference.md`"
      } else {
        "`skills/bill-feature-verify/SKILL.md`"
      }
    return "Resume `${definition.skillName}` workflow `$workflowId` from `$stepLabel` (`$resumeStepId`). " +
      "Follow the normal step instructions in $instructionPath. " +
      "Use the recovered `step_artifacts` in this payload ($artifacts) instead of reconstructing prior context from " +
      "chat history. Workflow activation status: `$continueStatus`. Next action: $nextAction"
  }

  private fun continuationEntryPrompt(
    definition: WorkflowDefinition,
    workflowId: String,
    sessionId: String,
    resumeStepId: String,
    continueStatus: String,
    artifactKeys: List<String>,
    nextAction: String,
    sessionSummary: Map<String, Any?>,
    extraFields: Map<String, Any?>,
  ): String {
    val references = definition.continuationReferenceSections[resumeStepId].orEmpty().joinToString("; ")
    val directive =
      definition.continuationDirectives[resumeStepId]
        ?: "Resume the workflow from the recovered current step using the persisted artifacts as authoritative context."
    val artifacts = artifactKeys.joinToString().ifBlank { "none" }
    val commonLines =
      mutableListOf(
        "Use `${definition.skillName}` in continuation mode.",
        "Workflow id: $workflowId",
        "Session id: ${sessionId.ifBlank { "(none)" }}",
        "Continue status: $continueStatus",
        "Resume step: $resumeStepId (${definition.stepLabels[resumeStepId] ?: resumeStepId})",
      )
    if (definition.skillName == "bill-feature-implement") {
      commonLines += "Feature: ${(extraFields["feature_name"] as String).ifBlank { "(unknown)" }}"
      commonLines += "Feature size: ${(extraFields["feature_size"] as String).ifBlank { "(unknown)" }}"
      commonLines += "Branch: ${(extraFields["branch_name"] as String).ifBlank { "(unknown)" }}"
    }
    commonLines += "Recovered artifacts: $artifacts"
    if (definition.skillName == "bill-feature-verify") {
      commonLines += "Acceptance criteria count: ${sessionSummary["acceptance_criteria_count"] ?: 0}"
      commonLines += "Rollout relevant: ${sessionSummary["rollout_relevant"] ?: false}"
    }
    val specSummary = sessionSummary["spec_summary"]?.toString()?.ifBlank { "(none saved)" } ?: "(none saved)"
    commonLines += "Spec summary: $specSummary"
    commonLines += "Reference sections: ${references.ifBlank { "normal step instructions only" }}"
    commonLines +=
      "Rules: do not rerun completed steps unless the workflow sends work backwards; treat artifacts as authoritative."
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

  private fun decodeSteps(rawValue: String): List<Map<String, Any?>> = JsonSupport.parseArrayOrEmpty(rawValue)
    .mapNotNull(JsonSupport::anyToStringAnyMap)

  private fun decodeObject(rawValue: String): Map<String, Any?> = JsonSupport.parseObjectOrNull(rawValue)
    ?.let(JsonSupport::jsonElementToValue)
    ?.let(JsonSupport::anyToStringAnyMap)
    ?: emptyMap()

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

  private fun Any?.asStepList(): List<Map<String, Any?>> =
    (this as? List<*>).orEmpty().mapNotNull(JsonSupport::anyToStringAnyMap)

  private fun Any?.asStringAnyMap(): Map<String, Any?> = JsonSupport.anyToStringAnyMap(this).orEmpty()
}
