package skillbill.db.workflow

import skillbill.db.telemetry.bind
import skillbill.ports.featuretask.FeatureTaskRuntimeAuditGenerationRepository
import skillbill.ports.featuretask.model.FeatureTaskRuntimeAuditGenerationRow
import java.sql.Connection

/**
 * Insert-only SQLite store for the append-only audit-generation history.
 *
 * The only mutating statement in this class is an INSERT with no upsert clause, so a duplicate ordinal
 * raises the primary-key constraint instead of overwriting durable history. SQLException surfaces through
 * the session factory's typed [skillbill.error.DatabaseAccessError] mapping, which is the same seam every
 * other workflow store reports failures through.
 */
internal class FeatureTaskRuntimeAuditGenerationStore(
  private val connection: Connection,
) : FeatureTaskRuntimeAuditGenerationRepository {
  override fun append(row: FeatureTaskRuntimeAuditGenerationRow) {
    connection.prepareStatement(
      """
      INSERT INTO feature_task_runtime_audit_generations (
        workflow_id, generation_ordinal, repository_checkpoint, contract_version, generation_json
      ) VALUES (?, ?, ?, ?, ?)
      """.trimIndent(),
    ).use { statement ->
      statement.bind(
        row.workflowId,
        row.generationOrdinal,
        row.repositoryCheckpoint,
        row.contractVersion,
        row.generationJson,
      )
      statement.executeUpdate()
    }
  }

  override fun listOrdered(workflowId: String): List<FeatureTaskRuntimeAuditGenerationRow> =
    connection.prepareStatement(
      """
      SELECT workflow_id, generation_ordinal, repository_checkpoint, contract_version, generation_json
      FROM feature_task_runtime_audit_generations
      WHERE workflow_id = ?
      ORDER BY generation_ordinal ASC
      """.trimIndent(),
    ).use { statement ->
      statement.setString(1, workflowId)
      statement.executeQuery().use { rows ->
        buildList {
          while (rows.next()) {
            add(
              FeatureTaskRuntimeAuditGenerationRow(
                workflowId = rows.getString("workflow_id"),
                generationOrdinal = rows.getInt("generation_ordinal"),
                repositoryCheckpoint = rows.getString("repository_checkpoint"),
                contractVersion = rows.getString("contract_version"),
                generationJson = rows.getString("generation_json"),
              ),
            )
          }
        }
      }
    }

  override fun quarantineAll(workflowId: String): Int = connection.prepareStatement(
    "DELETE FROM feature_task_runtime_audit_generations WHERE workflow_id = ?",
  ).use { statement ->
    statement.setString(1, workflowId)
    statement.executeUpdate()
  }
}
