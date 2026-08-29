package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.workflow.WorkflowFamily
import skillbill.error.ShellContentContractException
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.ports.goalrunner.runner.GoalRunnerManifestStore
import skillbill.ports.goalrunner.runner.GoalRunnerWorkflowOutcomeStore
import skillbill.ports.goalrunner.runner.model.GoalRunnerWorkflowProgress
import skillbill.workflow.decomposition.model.DecompositionSubtask
import kotlin.coroutines.cancellation.CancellationException

internal val RUNTIME_WORKFLOW_ID_PREFIX: String = WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix

internal const val FEATURE_SPEC_ROOT = ".feature-specs"
internal const val GIT_PORCELAIN_MIN_LENGTH = 4
internal const val GIT_PORCELAIN_STATUS_PREFIX_LENGTH = 3
internal const val MAX_VALIDATION_QUALITY_RETRIES = 3
internal const val MAX_REPORTED_FINALIZE_DIRTY_PATHS = 10

internal val PROTECTED_GOAL_BRANCHES: Set<String> = setOf("main", "master", "trunk")
internal val CHILD_WORKFLOW_BLOCK_REASONS: Set<GoalRunnerStopReason> = setOf(
  GoalRunnerStopReason.NO_TERMINAL_STORE_OUTCOME,
  GoalRunnerStopReason.TIMEOUT,
  GoalRunnerStopReason.INTERRUPTED,
)

internal fun isFeatureSpecPath(path: String): Boolean {
  val normalized = path.trim().trimEnd('/').removeSurrounding("\"").removePrefix("./")
  val dotted = if (normalized.startsWith(".")) normalized else ".$normalized"
  return dotted == FEATURE_SPEC_ROOT || dotted.startsWith("$FEATURE_SPEC_ROOT/")
}

internal fun protectedBranchName(branch: String?): String? = branch
  ?.trim()
  ?.takeIf(String::isNotBlank)
  ?.takeIf { normalized -> normalized.lowercase() in PROTECTED_GOAL_BRANCHES }

internal fun parseGitPorcelainPaths(output: String): List<String> = output
  .lineSequence()
  .map(String::trimEnd)
  .filter { line -> line.length >= GIT_PORCELAIN_MIN_LENGTH }
  .map { line -> line.substring(GIT_PORCELAIN_STATUS_PREFIX_LENGTH).substringAfterLast(" -> ").trim() }
  .filter(String::isNotBlank)
  .toList()

internal data class GoalRunnerProgressState(
  val subtask: DecompositionSubtask,
  val childProgress: GoalRunnerWorkflowProgress?,
)

internal class GoalRunnerTickProgressReader(
  private val manifestStore: GoalRunnerManifestStore,
  private val outcomeStore: GoalRunnerWorkflowOutcomeStore,
  private val issueKey: String,
  private val subtaskId: Int,
  private val request: GoalRunnerRunRequest,
  private val clockNanos: () -> Long = System::nanoTime,
) {
  private var cachedAtNanos: Long = 0
  private var cachedHasValue: Boolean = false
  private var cached: GoalRunnerProgressState? = null

  fun progressState(): GoalRunnerProgressState? {
    val now = clockNanos()
    if (cachedHasValue && now - cachedAtNanos < TICK_MEMO_WINDOW_NANOS) {
      return cached
    }
    cached = resolve()
    cachedAtNanos = now
    cachedHasValue = true
    return cached
  }

  private fun resolve(): GoalRunnerProgressState? {
    val subtask = manifestStore.loadByIssueKey(issueKey, request.dbPathOverride, request.repoRoot)
      ?.manifest
      ?.subtasks
      ?.firstOrNull { subtask -> subtask.id == subtaskId }
      ?: return null
    val childProgress = subtask.workflowId
      ?.takeIf(String::isNotBlank)
      ?.let { workflowId ->
        try {
          outcomeStore.progress(workflowId, request.dbPathOverride)
        } catch (error: CancellationException) {
          throw error
        } catch (error: ShellContentContractException) {
          throw error
        } catch (_: Exception) {
          null
        }
      }
    return GoalRunnerProgressState(subtask, childProgress)
  }

  internal companion object {
    const val SUPERVISOR_POLL_CADENCE_NANOS: Long = 250_000_000L
    const val TICK_MEMO_WINDOW_NANOS: Long = SUPERVISOR_POLL_CADENCE_NANOS - 50_000_000L
  }
}
