package skillbill.db.core

import java.sql.Connection

/**
 * Review-ledger column heals, held apart from [DatabaseColumnMigrations] so the shared column-heal
 * object stays within its size budget. Ordering relative to the rest of the startup pass is still
 * owned by [DatabaseColumnMigrations.apply].
 */
internal object DatabaseReviewColumnMigrations {
  fun apply(connection: Connection) {
    ensureReviewRunLaneDispositionColumns(connection)
    ensureFindingColumns(connection)
    ensureUnaddressedFindingColumns(connection)
    ensureReviewFindingOutcomeColumns(connection)
    ensureReviewStageStateTables(connection)
    ensureFindingVerdictReasonColumn(connection)
    backfillReviewSessionIds(connection)
  }

  fun ensureFindingVerdictReasonColumn(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "review_run_finding_verdicts")) return
    ensureColumn(connection, "review_run_finding_verdicts", "rejection_reason", "TEXT")
  }

  fun ensureReviewStageStateTables(connection: Connection) {
    DatabaseReviewLedgerSchema.reviewStageStateStatements.forEach { sql ->
      connection.createStatement().use { statement -> statement.execute(sql) }
    }
  }

  private fun ensureUnaddressedFindingColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "unaddressed_findings")) return
    ensureColumn(connection, "unaddressed_findings", "issue_key", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "subtask_id", "INTEGER NOT NULL DEFAULT 0")
    ensureColumn(connection, "unaddressed_findings", "severity", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "issue_category", "TEXT NOT NULL DEFAULT 'other'")
    ensureColumn(connection, "unaddressed_findings", "location", "TEXT NOT NULL DEFAULT '<unknown>'")
    ensureColumn(connection, "unaddressed_findings", "summary", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "recorded_at", "TEXT NOT NULL DEFAULT ''")
    ensureReviewFindingOutcomeKeyColumns(connection)
    ensureUnaddressedFindingVerdictColumns(connection)
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_unaddressed_findings_issue " +
          "ON unaddressed_findings(issue_key, subtask_id, review_pass_number)",
      )
    }
  }

  fun ensureUnaddressedFindingVerdictColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "unaddressed_findings")) return
    ensureColumn(connection, "unaddressed_findings", "claim_verdict", "TEXT")
    ensureColumn(connection, "unaddressed_findings", "scope_disposition", "TEXT")
    ensureColumn(connection, "unaddressed_findings", "citations", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "unaddressed_findings", "severity_adjustment_direction", "TEXT")
    ensureColumn(connection, "unaddressed_findings", "severity_adjustment_justification", "TEXT")
  }

  /**
   * The shared review-run/finding key on the workflow-loop ledger. Both columns are nullable with no
   * default: a pass for which no review run was imported keeps them NULL and is read as unresolved,
   * rather than being bucketed to a guessed review run.
   */
  fun ensureReviewFindingOutcomeKeyColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "unaddressed_findings")) return
    ensureColumn(connection, "unaddressed_findings", "review_run_id", "TEXT")
    ensureColumn(connection, "unaddressed_findings", "finding_id", "TEXT")
    // The index lives here, not in the shared schema statement list: on a pre-existing store the
    // CREATE TABLE is a no-op and the index would be created before these columns exist.
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_unaddressed_findings_run " +
          "ON unaddressed_findings(review_run_id, finding_id)",
      )
    }
  }

  /**
   * Content-derived cross-pass identity on the durable outcome table. Nullable with no backfill: a
   * row written before this column existed has no location or summary left to derive a key from —
   * `unaddressed_findings` is retracted on every pass — so it stays NULL and is excluded from
   * cross-pass reconciliation rather than being matched on its per-run positional finding id.
   */
  fun ensureReviewFindingOutcomeColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "review_finding_outcomes")) return
    ensureColumn(connection, "review_finding_outcomes", "finding_key", "TEXT")
    // Declared here rather than in the shared schema list: on an existing store the CREATE TABLE is a
    // no-op and the index would be created before the column exists.
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_review_finding_outcomes_key " +
          "ON review_finding_outcomes(workflow_id, finding_key)",
      )
    }
  }

  private fun ensureFindingColumns(connection: Connection) {
    ensureColumn(
      connection = connection,
      tableName = "findings",
      columnName = "issue_category",
      definition = "TEXT NOT NULL DEFAULT 'other'",
    )
    ensureFindingLaneColumns(connection)
  }

  // Per-finding lane attribution. Additive and nullable: a finding recorded before lane attribution
  // existed keeps a NULL lane rather than being guessed into a bucket.
  fun ensureFindingLaneColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "findings")) return
    ensureColumn(connection, "findings", "lane_skill_name", "TEXT")
    ensureColumn(connection, "findings", "lane_area", "TEXT")
    ensureColumn(connection, "findings", "lane_pack_slug", "TEXT")
    connection.createStatement().use { statement ->
      statement.execute(
        "CREATE INDEX IF NOT EXISTS idx_findings_lane ON findings(lane_skill_name, review_run_id)",
      )
    }
  }

  fun ensureReviewRunLaneDispositionColumns(connection: Connection) {
    if (!DatabaseColumnMigrations.tableExists(connection, "review_run_lanes")) return
    ensureColumn(connection, "review_run_lanes", "review_disposition", "TEXT NOT NULL DEFAULT 'incomplete'")
    ensureColumn(connection, "review_run_lanes", "bundle_composition_digest", "TEXT")
    ensureColumn(connection, "review_run_lanes", "segment_accounting_json", "TEXT")
    ensureColumn(connection, "review_run_lanes", "unreviewed_segment_ids", "TEXT NOT NULL DEFAULT ''")
    ensureColumn(connection, "review_run_lanes", "budget_dimension", "TEXT")
  }

  private fun backfillReviewSessionIds(connection: Connection) {
    connection.prepareStatement(
      """
      UPDATE review_runs
      SET review_session_id = review_run_id
      WHERE review_session_id IS NULL OR review_session_id = ''
      """.trimIndent(),
    ).use { statement ->
      statement.executeUpdate()
    }
  }

  private fun ensureColumn(connection: Connection, tableName: String, columnName: String, definition: String) {
    DatabaseColumnMigrations.ensureColumn(connection, tableName, columnName, definition)
  }
}
