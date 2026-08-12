package skillbill.db.core

import java.sql.Connection

/**
 * SKILL-185: a validation-gate repair cycle re-runs an agent inside one phase attempt without
 * advancing `attempt`, so both diagnostic keys had to widen by the repair-turn ordinal. Every
 * pre-existing row is carried across at turn 0, which is exactly the key an ordinary (non-repair)
 * attempt still writes, so no identity already stored on a quarantine entry changes.
 *
 * Both rebuilds are guarded on the live DDL rather than on the ledger, so a store that reached the
 * widened shape by any path is left alone and a store that did not self-heals on the next open.
 */
internal fun rekeyDiagnosticEvidenceByRepairTurn(connection: Connection) {
  rekeyProducerOutputEvidenceByRepairTurn(connection)
  rekeyRejectedOutputDiagnosticsByRepairTurn(connection)
}

private fun rekeyProducerOutputEvidenceByRepairTurn(connection: Connection) {
  if (tableDdlMentions(connection, "producer_output_evidence", "repair_turn")) return
  connection.createStatement().use {
    it.execute("ALTER TABLE producer_output_evidence RENAME TO producer_output_evidence_pre_repair_turn")
    it.execute(
      """
      CREATE TABLE IF NOT EXISTS producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        repair_turn INTEGER NOT NULL DEFAULT 0 CHECK (repair_turn >= 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, generation, attempt, repair_turn, agent_id)
      )
      """.trimIndent(),
    )
    it.execute(
      """
      INSERT INTO producer_output_evidence (
        workflow_id, phase_id, generation, attempt, repair_turn, agent_id, model, recorded_at,
        byte_size, sha256, payload
      )
      SELECT workflow_id, phase_id, generation, attempt, 0, agent_id, model, recorded_at,
             byte_size, sha256, payload
      FROM producer_output_evidence_pre_repair_turn
      """.trimIndent(),
    )
    it.execute("DROP TABLE producer_output_evidence_pre_repair_turn")
  }
}

private fun rekeyRejectedOutputDiagnosticsByRepairTurn(connection: Connection) {
  if (tableDdlMentions(connection, "rejected_output_diagnostics", "repair_turn")) return
  connection.createStatement().use {
    it.execute("DROP INDEX IF EXISTS idx_rejected_output_diagnostics_selector")
    it.execute("ALTER TABLE rejected_output_diagnostics RENAME TO rejected_output_diagnostics_pre_repair_turn")
    it.execute(
      """
      CREATE TABLE IF NOT EXISTS rejected_output_diagnostics (
        identity TEXT PRIMARY KEY,
        workflow_id TEXT NOT NULL,
        phase_id TEXT NOT NULL,
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        repair_turn INTEGER NOT NULL DEFAULT 0 CHECK (repair_turn >= 0),
        rule TEXT NOT NULL,
        rejection_path TEXT NOT NULL,
        reason TEXT NOT NULL,
        agent_id TEXT NOT NULL,
        model TEXT NOT NULL,
        recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
        sha256 TEXT NOT NULL,
        lifecycle TEXT NOT NULL CHECK (lifecycle IN ('stored', 'oversized', 'expired')),
        payload BLOB,
        UNIQUE (workflow_id, phase_id, attempt, repair_turn),
        CHECK (
          (lifecycle = 'stored' AND payload IS NOT NULL) OR
          (lifecycle IN ('oversized', 'expired') AND payload IS NULL)
        )
      )
      """.trimIndent(),
    )
    it.execute(
      """
      INSERT INTO rejected_output_diagnostics (
        identity, workflow_id, phase_id, attempt, repair_turn, rule, rejection_path, reason,
        agent_id, model, recorded_at, byte_size, sha256, lifecycle, payload
      )
      SELECT identity, workflow_id, phase_id, attempt, 0, rule, rejection_path, reason,
             agent_id, model, recorded_at, byte_size, sha256, lifecycle, payload
      FROM rejected_output_diagnostics_pre_repair_turn
      """.trimIndent(),
    )
    it.execute("DROP TABLE rejected_output_diagnostics_pre_repair_turn")
    it.execute(
      """
      CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostics_selector
        ON rejected_output_diagnostics(workflow_id, phase_id, attempt, repair_turn)
      """.trimIndent(),
    )
    // DROP TABLE takes every index with it, including the two version-14 indexes the base schema
    // does not declare. Version 14 is already in the ledger on any store reaching this point, so
    // nothing else would put them back: without this, a migrated store would lose the retention
    // index that keeps markExpired off a full scan of a table holding raw agent-output blobs, and
    // would diverge from a freshly created store.
    it.execute(
      "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_selection " +
        "ON rejected_output_diagnostics(workflow_id, phase_id, attempt)",
    )
    it.execute(
      "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_retention " +
        "ON rejected_output_diagnostics(lifecycle, recorded_at)",
    )
  }
}

private fun tableDdlMentions(connection: Connection, table: String, column: String): Boolean {
  val ddl = connection.prepareStatement(
    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
  ).use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { rows ->
      // An absent table is created in its widened shape by the base schema, so there is nothing to rebuild.
      if (!rows.next()) return true
      rows.getString("sql").orEmpty()
    }
  }
  return Regex("""\b$column\b""", RegexOption.IGNORE_CASE).containsMatchIn(ddl)
}
