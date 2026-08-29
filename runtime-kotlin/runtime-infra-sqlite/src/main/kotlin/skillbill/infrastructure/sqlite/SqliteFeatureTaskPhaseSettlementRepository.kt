package skillbill.infrastructure.sqlite

import me.tatarka.inject.annotations.Inject
import skillbill.db.core.DatabaseRuntime
import skillbill.ports.featuretask.FeatureTaskPhaseSettlementRepository
import skillbill.ports.featuretask.model.FeatureTaskPhaseSettlement

@Inject
class SqliteFeatureTaskPhaseSettlementRepository : FeatureTaskPhaseSettlementRepository {
  override fun upsert(settlement: FeatureTaskPhaseSettlement, dbPathOverride: String?) {
    DatabaseRuntime.openDb(cliValue = dbPathOverride).use { database ->
      database.connection.prepareStatement(
        """
        INSERT INTO feature_task_phase_settlements (
          workflow_id, phase_id, attempt, kind, envelope_json, recorded_at
        ) VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(workflow_id, phase_id, attempt) DO UPDATE SET
          kind = excluded.kind,
          envelope_json = excluded.envelope_json,
          recorded_at = excluded.recorded_at
        """.trimIndent(),
      ).use { statement ->
        statement.setString(PARAM_ONE, settlement.workflowId)
        statement.setString(PARAM_TWO, settlement.phaseId)
        statement.setInt(PARAM_THREE, settlement.attempt)
        statement.setString(PARAM_FOUR, settlement.kind)
        statement.setString(PARAM_FIVE, settlement.envelopeJson)
        statement.setString(PARAM_SIX, settlement.recordedAt)
        statement.executeUpdate()
      }
    }
  }

  override fun find(
    workflowId: String,
    phaseId: String,
    attempt: Int,
    dbPathOverride: String?,
  ): FeatureTaskPhaseSettlement? {
    DatabaseRuntime.openDb(cliValue = dbPathOverride).use { database ->
      database.connection.prepareStatement(
        """
        SELECT workflow_id, phase_id, attempt, kind, envelope_json, recorded_at
        FROM feature_task_phase_settlements
        WHERE workflow_id = ? AND phase_id = ? AND attempt = ?
        """.trimIndent(),
      ).use { statement ->
        statement.setString(PARAM_ONE, workflowId)
        statement.setString(PARAM_TWO, phaseId)
        statement.setInt(PARAM_THREE, attempt)
        statement.executeQuery().use { rows ->
          if (!rows.next()) return null
          return FeatureTaskPhaseSettlement(
            workflowId = rows.getString(PARAM_ONE),
            phaseId = rows.getString(PARAM_TWO),
            attempt = rows.getInt(PARAM_THREE),
            kind = rows.getString(PARAM_FOUR),
            envelopeJson = rows.getString(PARAM_FIVE),
            recordedAt = rows.getString(PARAM_SIX),
          )
        }
      }
    }
  }

  override fun delete(workflowId: String, phaseId: String, attempt: Int, dbPathOverride: String?): Boolean {
    DatabaseRuntime.openDb(cliValue = dbPathOverride).use { database ->
      database.connection.prepareStatement(
        """
        DELETE FROM feature_task_phase_settlements
        WHERE workflow_id = ? AND phase_id = ? AND attempt = ?
        """.trimIndent(),
      ).use { statement ->
        statement.setString(PARAM_ONE, workflowId)
        statement.setString(PARAM_TWO, phaseId)
        statement.setInt(PARAM_THREE, attempt)
        return statement.executeUpdate() > 0
      }
    }
  }

  private companion object {
    const val PARAM_ONE: Int = 1
    const val PARAM_TWO: Int = 2
    const val PARAM_THREE: Int = 3
    const val PARAM_FOUR: Int = 4
    const val PARAM_FIVE: Int = 5
    const val PARAM_SIX: Int = 6
  }
}
