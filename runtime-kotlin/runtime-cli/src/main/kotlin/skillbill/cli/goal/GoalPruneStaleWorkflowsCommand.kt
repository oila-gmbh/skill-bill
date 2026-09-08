package skillbill.cli.goal

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.application.featuretask.FeatureTaskRuntimeStaleWorkflowService
import skillbill.application.featuretask.model.FeatureTaskRuntimeStaleWorkflowPruneRequest
import skillbill.application.featuretask.model.FeatureTaskRuntimeStaleWorkflowPruneResult
import skillbill.cli.kernel.CliRunState
import skillbill.cli.kernel.DocumentedCliCommand
import skillbill.cli.kernel.formatOption
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs

@Inject
class GoalPruneStaleWorkflowsCommand(
  private val service: FeatureTaskRuntimeStaleWorkflowService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
  "prune-stale-workflows",
  "Report invalid feature-task runtime snapshots. Pass --confirm to delete selected stale workflow ids.",
) {
  private val workflowIds by option(
    "--workflow-id",
    help = "Workflow id to select for retirement. Repeat to select multiple rows; omit to select all stale rows.",
  ).multiple()
  private val confirm by option(
    "--confirm",
    help = "Retire selected stale workflows. Without this flag the command is report-only.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    val result = service.prune(
      FeatureTaskRuntimeStaleWorkflowPruneRequest(
        workflowIds = workflowIds,
        confirm = confirm,
        dbPathOverride = inputs.dbPathOverride,
      ),
    )
    val payload = result.toCliMap()
    if (format == CliFormat.JSON) {
      state.complete(payload, format)
    } else {
      state.completeText(result.toCliText(), payload)
    }
  }
}

internal fun FeatureTaskRuntimeStaleWorkflowPruneResult.toCliMap(): Map<String, Any?> = linkedMapOf(
  "status" to "ok",
  "db_path" to dbPath,
  "mode" to if (confirmed) "confirmed" else "report_only",
  "requested_workflow_ids" to requestedWorkflowIds,
  "stale_workflows" to staleWorkflows.map { stale ->
    linkedMapOf(
      "workflow_id" to stale.workflowId,
      "issue_key" to stale.issueKey,
      "validation_failure" to stale.validationFailure,
    )
  },
  "deleted_workflow_ids" to deletedWorkflowIds,
  "retained_workflow_ids" to retainedWorkflowIds,
)

internal fun FeatureTaskRuntimeStaleWorkflowPruneResult.toCliText(): String = buildString {
  appendLine("goal: prune-stale-workflows")
  appendLine("status: ok")
  appendLine("mode: ${if (confirmed) "confirmed" else "report_only"}")
  appendLine("stale_workflows:")
  if (staleWorkflows.isEmpty()) {
    appendLine("  - none")
  } else {
    staleWorkflows.forEach { stale ->
      appendLine(
        "  - workflow_id=${stale.workflowId}; issue_key=${stale.issueKey ?: "none"}; " +
          "validation_failure=${stale.validationFailure}",
      )
    }
  }
  appendLine("deleted_workflow_ids: ${deletedWorkflowIds.ifEmpty { listOf("none") }.joinToString(",")}")
  appendLine("retained_workflow_ids: ${retainedWorkflowIds.ifEmpty { listOf("none") }.joinToString(",")}")
}
