package skillbill.application.featuretask.model
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId

internal const val SUBTASK_TRAILER_KEY = "Skill-Bill-Subtask"

data class FeatureTaskRuntimeSubtaskCommitIdentity(val issueKey: IssueKey, val subtaskId: SubtaskId) {
  init {
    require(issueKey.value.isNotBlank()) { "FeatureTaskRuntimeSubtaskCommitIdentity.issueKey must be non-blank." }
    require(
      subtaskId.value.toString().isNotBlank(),
    ) { "FeatureTaskRuntimeSubtaskCommitIdentity.subtaskId must be non-blank." }
  }

  val trailer: String get() = "$SUBTASK_TRAILER_KEY: ${issueKey.value}/${subtaskId.value}"

  fun checkpointRefName(sequenceNumber: Int): String =
    skillbill.workflow.taskruntime.model.featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)

  fun matches(commitMessage: String): Boolean = parse(commitMessage) == this

  companion object {
    fun parse(commitMessage: String): FeatureTaskRuntimeSubtaskCommitIdentity? = commitMessage.lineSequence()
      .map(String::trim)
      .filter { it.startsWith("$SUBTASK_TRAILER_KEY:") }
      .mapNotNull { line -> identityFrom(line.removePrefix("$SUBTASK_TRAILER_KEY:").trim()) }
      .lastOrNull()

    private fun identityFrom(value: String): FeatureTaskRuntimeSubtaskCommitIdentity? {
      val segments = value.split('/')
      if (segments.size != 2) return null
      val (issueKey, subtaskId) = segments
      if (issueKey.isBlank() || subtaskId.isBlank()) return null
      val parsedSubtaskId = subtaskId.toIntOrNull() ?: return null
      return FeatureTaskRuntimeSubtaskCommitIdentity(IssueKey(issueKey), SubtaskId(parsedSubtaskId))
    }
  }
}
