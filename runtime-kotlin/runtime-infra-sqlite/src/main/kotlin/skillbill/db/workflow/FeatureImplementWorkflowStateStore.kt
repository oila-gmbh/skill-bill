package skillbill.db.workflow
import skillbill.error.ProseFeatureTaskWorkflowWriteRefusedError
import skillbill.ports.workflow.FeatureImplementWorkflowStateRepository
import skillbill.ports.workflow.model.FeatureImplementSessionSummary
import skillbill.ports.workflow.model.FeatureTaskWorkflowMode
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId
import java.sql.Connection

internal class FeatureImplementWorkflowStateStore(
  private val connection: Connection,
) : FeatureImplementWorkflowStateRepository {
  // SKILL-175 subtask 6: compatibility-alias writer for mode=prose, neutered per the "no live
  // writer" policy (runtime-kotlin/agent/decisions.md, "In-flight prose row policy" rule 1). Reads
  // below stay live so quarantined rows remain visible for history.
  override fun saveFeatureImplementWorkflow(row: WorkflowStateRecord) {
    throw ProseFeatureTaskWorkflowWriteRefusedError(row.workflowId.value)
  }

  override fun getFeatureImplementWorkflow(workflowId: WorkflowId): WorkflowStateRecord? =
    connection.getFeatureTaskWorkflowRowAsMode(workflowId.value, FeatureTaskWorkflowMode.PROSE)

  override fun getFeatureImplementWorkflows(workflowIds: Set<WorkflowId>): Map<WorkflowId, WorkflowStateRecord> =
    connection.getFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.PROSE, workflowIds.map { it.value }.toSet())
      .mapKeys { (workflowId, _) -> WorkflowId(workflowId) }

  override fun listFeatureImplementWorkflows(limit: Int): List<WorkflowStateRecord> =
    connection.listFeatureTaskWorkflowRows(FeatureTaskWorkflowMode.PROSE, limit)

  override fun latestFeatureImplementWorkflow(): WorkflowStateRecord? = listFeatureImplementWorkflows(1).firstOrNull()

  override fun getFeatureImplementSessionSummary(sessionId: SessionId): FeatureImplementSessionSummary? =
    connection.prepareStatement(
      """
      SELECT
        session_id,
        issue_key_provided,
        issue_key_type,
        spec_input_types,
        spec_word_count,
        feature_size,
        feature_name,
        rollout_needed,
        acceptance_criteria_count,
        open_questions_count,
        spec_summary
      FROM feature_implement_sessions
      WHERE session_id = ?
      """.trimIndent(),
    ).use { statement ->
      statement.setString(WORKFLOW_ID_PARAMETER_INDEX, sessionId.value)
      statement.executeQuery().use { resultSet ->
        if (!resultSet.next()) {
          return null
        }
        FeatureImplementSessionSummary(
          sessionId = SessionId(resultSet.getString("session_id")),
          issueKeyProvided = resultSet.getInt("issue_key_provided") == 1,
          issueKeyType = resultSet.getString("issue_key_type"),
          specInputTypes = decodeWorkflowStringList(resultSet.getString("spec_input_types")),
          specWordCount = resultSet.getInt("spec_word_count"),
          featureSize = resultSet.getString("feature_size"),
          featureName = resultSet.getString("feature_name"),
          rolloutNeeded = resultSet.getInt("rollout_needed") == 1,
          acceptanceCriteriaCount = resultSet.getInt("acceptance_criteria_count"),
          openQuestionsCount = resultSet.getInt("open_questions_count"),
          specSummary = resultSet.getString("spec_summary"),
        )
      }
    }
}
