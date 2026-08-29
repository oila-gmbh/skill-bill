package skillbill.application.goalrunner

import skillbill.ports.agentrun.model.AgentRunDeclaredProgressProbe
import skillbill.ports.agentrun.model.AgentRunDeclaredProgressSnapshot
import skillbill.ports.agentrun.model.AgentRunProgressProbe
import skillbill.workflow.decomposition.model.DecompositionSubtask

internal fun progressProbe(reader: GoalRunnerTickProgressReader, subtaskId: Int): AgentRunProgressProbe =
  GoalRunnerWorkflowProgressProbe(reader = reader, subtaskId = subtaskId)

internal class GoalRunnerWorkflowProgressProbe(
  private val reader: GoalRunnerTickProgressReader,
  private val subtaskId: Int,
) : AgentRunProgressProbe {
  override fun progressToken(): String? = reader.progressState()
    ?.let { progress ->
      listOfNotNull(progress.subtask.progressToken(), progress.childProgress?.progressToken)
    }
    ?.joinToString("\n")
    ?.takeIf(String::isNotBlank)

  override fun progressLabel(): String? = reader.progressState()?.let { progress ->
    progress.childProgress?.let { child ->
      listOfNotNull(
        "subtask $subtaskId",
        "workflow ${child.workflowId}",
        "step ${child.currentStepId}",
        child.latestLivenessSignal,
      ).joinToString(" ")
    } ?: "subtask $subtaskId manifest updated"
  }
}

internal fun declaredProgressProbe(
  reader: GoalRunnerTickProgressReader,
): AgentRunDeclaredProgressProbe =
  AgentRunDeclaredProgressProbe {
    reader.progressState()
      ?.childProgress
      ?.latestDeclaredProgressEvent
      ?.let { event ->
        AgentRunDeclaredProgressSnapshot(
          latestEvent = event,
          processAlive = event.processAlive,
        )
      }
}

internal fun DecompositionSubtask.progressToken(): String = listOf(
  status,
  workflowId.orEmpty(),
  branch.orEmpty(),
  commitSha.orEmpty(),
  blockedReason.orEmpty(),
  lastResumableStep.orEmpty(),
).joinToString("|")
