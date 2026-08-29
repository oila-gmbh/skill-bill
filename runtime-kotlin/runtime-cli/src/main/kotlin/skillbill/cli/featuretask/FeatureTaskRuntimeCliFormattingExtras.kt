package skillbill.cli.featuretask

import com.github.ajalt.clikt.core.UsageError
import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.agentaddon.model.HydratedAgentAddonSelection
import skillbill.agentaddon.model.PersistedAgentAddonSelectionEntry
import skillbill.application.featuretask.model.FeatureTaskRuntimeAgentAssignment
import skillbill.application.featuretask.model.FeatureTaskRuntimeModelAssignment
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunReport
import skillbill.application.featuretask.model.FeatureTaskRuntimeSubtaskOutcome
import skillbill.cli.core.requireInvokingAgentId
import skillbill.config.model.CompactionSettings
import skillbill.config.model.PhaseModelDirective
import skillbill.contracts.JsonSupport
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path

internal fun parsePhaseAgents(rawAssignments: List<String>): Map<String, String> {
  val parsed = LinkedHashMap<String, String>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      throw UsageError("--phase-agent must be phase=agent, e.g. --phase-agent plan=claude (got '$assignment').")
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val agentId = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      throw UsageError(
        "--phase-agent phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    parsed[phaseId] = agentId
  }
  return parsed
}

internal fun parsePhaseModels(rawAssignments: List<String>): Map<String, PhaseModelDirective> {
  val parsed = LinkedHashMap<String, PhaseModelDirective>()
  rawAssignments.forEach { assignment ->
    val separatorIndex = assignment.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == assignment.length - 1) {
      invalidPhaseModel(
        "--phase-model must be phase=model[@effort], e.g. --phase-model plan=claude-opus-4-8@high " +
          "(got '$assignment').",
      )
    }
    val phaseId = assignment.substring(0, separatorIndex).trim()
    val modelAndEffort = assignment.substring(separatorIndex + 1).trim()
    if (phaseId !in FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds) {
      invalidPhaseModel(
        "--phase-model phase '$phaseId' is not a runtime phase " +
          "(${FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.joinToString()}).",
      )
    }
    if (modelAndEffort.count { it == '@' } > 1) {
      invalidPhaseModel("--phase-model allows at most one @ separating model and effort (got '$assignment').")
    }
    val effortSeparator = modelAndEffort.indexOf('@')
    val model = modelAndEffort.substringBefore('@').trim()
    val effort = if (effortSeparator == -1) null else modelAndEffort.substring(effortSeparator + 1).trim()
    if (model.isBlank() || effort?.isBlank() == true) {
      invalidPhaseModel("--phase-model requires non-blank model and effort segments (got '$assignment').")
    }
    parsed[phaseId] = PhaseModelDirective(model = model, effort = effort)
  }
  return parsed
}

internal fun invalidPhaseModel(message: String): Nothing = throw UsageError(message)

internal data class PreparedRuntimeRun(
  val repoRoot: Path,
  val invokedAgentId: String,
  val agentAssignment: FeatureTaskRuntimeAgentAssignment,
  val modelAssignment: FeatureTaskRuntimeModelAssignment,
  val compactionSettings: CompactionSettings,
  val agentAddonSelection: HydratedAgentAddonSelection,
)

internal fun parseAgentAddonSelection(raw: String?): AgentAddonSelection {
  if (raw == null) return AgentAddonSelection()
  val root = JsonSupport.parseObjectOrNull(raw)
    ?: invalidAgentAddonSelection("--agent-addon-selection-json must be a JSON object.")
  val map = JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(root))
    ?: invalidAgentAddonSelection("--agent-addon-selection-json must decode to an object.")
  if (map.keys != setOf("contract_version", "entries") || map["contract_version"] != "0.1") {
    invalidAgentAddonSelection("Agent add-on selection must contain only contract_version=0.1 and entries.")
  }
  val entries = map["entries"] as? List<*>
    ?: invalidAgentAddonSelection("Agent add-on selection entries must be an ordered array.")
  return try {
    AgentAddonSelection(
      entries.mapIndexed { index, valueEntry ->
        val entry = JsonSupport.anyToStringAnyMap(valueEntry)
          ?: invalidAgentAddonSelection("Agent add-on selection entry $index must be an object.")
        val persistedKeys = setOf("slug", "source_identity", "content_sha256")
        if (!entry.keys.containsAll(persistedKeys) || entry.keys.any { it !in persistedKeys + "description" }) {
          invalidAgentAddonSelection("Agent add-on selection entry $index has unsupported or missing fields.")
        }
        PersistedAgentAddonSelectionEntry(
          slug = entry["slug"] as? String
            ?: invalidAgentAddonSelection("Entry $index slug is required."),
          sourceIdentity = entry["source_identity"] as? String
            ?: invalidAgentAddonSelection("Entry $index source_identity is required."),
          contentSha256 = entry["content_sha256"] as? String
            ?: invalidAgentAddonSelection("Entry $index content_sha256 is required."),
        )
      },
    )
  } catch (error: IllegalArgumentException) {
    invalidAgentAddonSelection("Invalid agent add-on selection: ${error.message}", error)
  }
}

internal fun invalidAgentAddonSelection(message: String, cause: Throwable? = null): Nothing {
  throw UsageError(message).apply { cause?.let(::initCause) }
}

internal fun resolveInvokedRuntimeAgentId(explicitAgent: String?, environment: Map<String, String>): String =
  requireInvokingAgentId(explicitAgent, environment, "--agent")

internal fun FeatureTaskRuntimeRunReport.toRuntimeRunCliMap(): Map<String, Any?> = when (this) {
  is FeatureTaskRuntimeRunReport.Completed -> linkedMapOf(
    "status" to "complete",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Blocked -> linkedMapOf(
    "status" to "blocked",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "last_incomplete_phase" to lastIncompletePhase,
    "blocked_reason" to blockedReason,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Paused -> linkedMapOf(
    "status" to "paused",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "paused_phase" to pausedPhase,
    "pause_reason" to pauseReason,
    "resumable_step" to resumableStep,
    "completed_phases" to completedPhaseIds,
  ).withSubtaskOutcome(subtaskOutcome)
  is FeatureTaskRuntimeRunReport.Decomposed -> linkedMapOf(
    "status" to "decomposed",
    "issue_key" to issueKey,
    "workflow_id" to workflowId,
    "feature_size" to featureSize,
    "resolved_branch" to resolvedBranch,
    "reason" to reason,
    "completed_phases" to completedPhaseIds,
    "parent_spec_path" to parentSpecPath,
    "decomposition_manifest_path" to decompositionManifestPath,
    "subtask_spec_paths" to subtaskSpecPaths,
    "subtask_count" to subtaskSpecPaths.size,
    "guidance" to DECOMPOSE_GUIDANCE,
  )
}

internal fun Map<String, Any?>.withSubtaskOutcome(outcome: FeatureTaskRuntimeSubtaskOutcome?): Map<String, Any?> =
  if (outcome == null) {
    this
  } else {
    LinkedHashMap(this).apply {
      put(
        "subtask_outcome",
        linkedMapOf(
          "issue_key" to outcome.issueKey,
          "subtask_id" to outcome.subtaskId,
          "status" to outcome.status,
          "commit_sha" to outcome.commitSha,
          "workflow_id" to outcome.workflowId,
          "blocked_reason" to outcome.blockedReason,
          "last_resumable_step" to outcome.lastResumableStep,
          "finalizing_agent_id" to outcome.finalizingAgentId,
          "participating_agent_ids" to outcome.participatingAgentIds,
        ),
      )
    }
  }

internal fun Map<String, Any?>.runtimeRunExitCode(): Int = if (isTerminalSuccessStatus()) 0 else 1

internal fun Map<String, Any?>.isTerminalSuccessStatus(): Boolean = this["status"] in setOf("complete", "decomposed")
