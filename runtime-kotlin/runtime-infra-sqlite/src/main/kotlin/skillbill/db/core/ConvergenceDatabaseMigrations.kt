package skillbill.db.core

internal object ConvergenceDatabaseMigrations {
  val migrations: List<DatabaseMigration> = listOf(
    DatabaseMigration(
      version = 16,
      name = "add-feature-task-convergence-state",
      operation = ::createInitialConvergenceTables,
    ),
    DatabaseMigration(
      version = 17,
      name = "retain-convergence-evidence-and-classification",
      operation = ::retainConvergenceEvidenceAndClassification,
    ),
    DatabaseMigration(
      version = 18,
      name = "add-audit-convergence-tables",
      operation = ::createAuditConvergenceTables,
    ),
    DatabaseMigration(
      version = 20,
      name = "retain-exact-convergence-parent-identity",
      operation = ::retainExactConvergenceParentIdentity,
    ),
    DatabaseMigration(
      version = 21,
      name = "retain-audit-history-and-one-active-repair-batch",
      operation = ::retainAuditHistoryAndOneActiveRepairBatch,
    ),
    DatabaseMigration(
      version = 22,
      name = "align-convergence-record-shapes-and-review-ordinals",
      operation = ::alignConvergenceRecordShapesAndReviewOrdinals,
    ),
  )
}

private fun alignConvergenceRecordShapesAndReviewOrdinals(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    statement.execute("DROP INDEX IF EXISTS idx_convergence_history")
    statement.execute("DROP INDEX IF EXISTS idx_convergence_unresolved")
    statement.execute("ALTER TABLE feature_task_convergence_records RENAME TO feature_task_convergence_records_v21")
    statement.execute(ConvergenceDatabaseSchema.statements.first().replace(" IF NOT EXISTS", ""))
    statement.execute(
      """
      INSERT INTO feature_task_convergence_records(
        record_id, contract_version, workflow_id, record_kind, generation, logical_id,
        parent_logical_id, parent_record_id, phase_id, attempt, review_pass, record_status,
        classification, summary, affected_path, evidence_digest, evidence_ref, created_at
      )
      SELECT record_id, contract_version, workflow_id, record_kind, generation, logical_id,
        parent_logical_id, parent_record_id, phase_id, attempt, review_pass, record_status,
        classification, summary, affected_path, evidence_digest, evidence_ref, created_at
      FROM feature_task_convergence_records_v21
      """.trimIndent(),
    )
    statement.execute("DROP TABLE feature_task_convergence_records_v21")
    ConvergenceDatabaseSchema.statements.filter { it.startsWith("CREATE INDEX") }.forEach(statement::execute)
    if (!connection.hasColumn("review_generations", "generation_ordinal")) {
      statement.execute("ALTER TABLE review_generations ADD COLUMN generation_ordinal INTEGER")
    }
    statement.execute(
      """
      UPDATE review_generations
      SET generation_ordinal = (
        SELECT COUNT(*)
        FROM review_generations candidate
        WHERE candidate.workflow_id = review_generations.workflow_id
          AND (candidate.created_at < review_generations.created_at OR
            (candidate.created_at = review_generations.created_at AND
              candidate.generation_id <= review_generations.generation_id))
      )
      """.trimIndent(),
    )
    statement.execute(
      "CREATE UNIQUE INDEX IF NOT EXISTS idx_review_generation_ordinal " +
        "ON review_generations(workflow_id, generation_ordinal)",
    )
  }
}

private fun java.sql.Connection.hasColumn(table: String, column: String): Boolean =
  createStatement().use { statement ->
    statement.executeQuery("PRAGMA table_info($table)").use { rows ->
      generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == column }
    }
  }

private fun createInitialConvergenceTables(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    initialConvergenceStatements.forEach(statement::execute)
  }
}

private val initialConvergenceStatements = listOf(
  """
  CREATE TABLE IF NOT EXISTS feature_task_convergence_records (
    record_id TEXT PRIMARY KEY CHECK(length(record_id) BETWEEN 1 AND 160),
    contract_version TEXT NOT NULL CHECK(contract_version = '0.1'),
    workflow_id TEXT NOT NULL CHECK(length(workflow_id) BETWEEN 1 AND 160),
    record_kind TEXT NOT NULL CHECK(record_kind IN (
      'IMPLEMENTATION_OUTCOME', 'IMPLEMENTATION_OBLIGATION', 'AUDIT_GAP', 'AUDIT_REPAIR',
      'REVIEW_FINDING', 'REVIEW_DISPOSITION', 'REPOSITORY_CHECKPOINT', 'LEGACY_IMPORT'
    )),
    generation INTEGER NOT NULL CHECK(generation > 0),
    logical_id TEXT NOT NULL CHECK(length(logical_id) BETWEEN 1 AND 160),
    parent_logical_id TEXT CHECK(
      parent_logical_id IS NULL OR length(parent_logical_id) BETWEEN 1 AND 160
    ),
    phase_id TEXT NOT NULL CHECK(phase_id IN ('implement', 'audit', 'review')),
    attempt INTEGER CHECK(attempt IS NULL OR attempt > 0),
    review_pass INTEGER CHECK(review_pass IS NULL OR review_pass > 0),
    record_status TEXT NOT NULL CHECK(
      record_status IN ('OPEN', 'RESOLVED', 'COMPLETED', 'FAILED', 'QUARANTINED')
    ),
    summary TEXT CHECK(summary IS NULL OR length(summary) BETWEEN 1 AND 512),
    affected_path TEXT CHECK(affected_path IS NULL OR length(affected_path) BETWEEN 1 AND 512),
    evidence_digest TEXT NOT NULL CHECK(length(evidence_digest) = 64),
    evidence_ref TEXT CHECK(evidence_ref IS NULL OR length(evidence_ref) BETWEEN 1 AND 512),
    created_at TEXT NOT NULL,
    UNIQUE(workflow_id, record_kind, generation, logical_id),
    FOREIGN KEY(workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
  )
  """.trimIndent(),
  """
  CREATE INDEX IF NOT EXISTS idx_convergence_history
    ON feature_task_convergence_records(workflow_id, generation, created_at)
  """.trimIndent(),
  """
  CREATE INDEX IF NOT EXISTS idx_convergence_unresolved
    ON feature_task_convergence_records(
      workflow_id, record_kind, record_status, generation
    )
  """.trimIndent(),
  """
  CREATE TABLE IF NOT EXISTS feature_task_convergence_legacy_imports (
    workflow_id TEXT NOT NULL,
    source_digest TEXT NOT NULL CHECK(length(source_digest) = 64),
    disposition TEXT NOT NULL CHECK(disposition IN ('imported', 'quarantined')),
    reason_code TEXT CHECK(reason_code IS NULL OR length(reason_code) BETWEEN 1 AND 64),
    imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(workflow_id, source_digest),
    FOREIGN KEY(workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
  )
  """.trimIndent(),
)

private fun retainConvergenceEvidenceAndClassification(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    statement.execute("DROP INDEX IF EXISTS idx_convergence_history")
    statement.execute("DROP INDEX IF EXISTS idx_convergence_unresolved")
    statement.execute(
      """
      ALTER TABLE feature_task_convergence_records
      RENAME TO feature_task_convergence_records_v16
      """.trimIndent(),
    )
    statement.execute(ConvergenceDatabaseSchema.statements.first().replace(" IF NOT EXISTS", ""))
    statement.execute(
      """
      INSERT INTO feature_task_convergence_records(
        record_id, contract_version, workflow_id, record_kind, generation, logical_id,
        parent_logical_id, phase_id, attempt, review_pass, record_status, classification,
        summary, affected_path, evidence_digest, evidence_ref, created_at
      )
      SELECT record_id, contract_version, workflow_id, record_kind, generation, logical_id,
        parent_logical_id, phase_id, attempt, review_pass, record_status, NULL,
        summary, affected_path, evidence_digest, evidence_ref, created_at
      FROM feature_task_convergence_records_v16
      """.trimIndent(),
    )
    statement.execute("DROP TABLE feature_task_convergence_records_v16")
    statement.execute(
      """
      ALTER TABLE feature_task_convergence_legacy_imports
      RENAME TO feature_task_convergence_legacy_imports_v16
      """.trimIndent(),
    )
    statement.execute(ConvergenceDatabaseSchema.statements.last().replace(" IF NOT EXISTS", ""))
    statement.execute(
      """
      INSERT INTO feature_task_convergence_legacy_imports(
        workflow_id, source_digest, disposition, reason_code, imported_at
      )
      SELECT workflow_id, source_digest, disposition, reason_code, imported_at
      FROM feature_task_convergence_legacy_imports_v16
      """.trimIndent(),
    )
    statement.execute("DROP TABLE feature_task_convergence_legacy_imports_v16")
    ConvergenceDatabaseSchema.statements
      .filter { it.startsWith("CREATE INDEX") }
      .forEach(statement::execute)
  }
}

private fun createAuditConvergenceTables(connection: java.sql.Connection) {
  connection.createStatement().use { statement ->
    AuditConvergenceDatabaseSchema.statements.forEach(statement::execute)
  }
}

private fun retainAuditHistoryAndOneActiveRepairBatch(connection: java.sql.Connection) =
  AuditHistoryMigration.run(connection)

private object AuditHistoryMigration {
  private val tables = listOf(
    "feature_task_audit_gaps",
    "feature_task_audit_repair_batches",
    "feature_task_audit_repair_items",
    "feature_task_audit_repair_item_batch_mapping",
    "feature_task_audit_repair_item_dependencies",
    "feature_task_audit_repair_item_results",
    "feature_task_audit_repair_non_regression",
    "feature_task_audit_gap_dispositions",
  )

  fun run(connection: java.sql.Connection) {
    if (hasColumn(connection, "feature_task_audit_repair_batches", "workflow_id") &&
      hasColumn(connection, "feature_task_audit_repair_items", "workflow_id")
    ) {
      createIndexes(connection)
      return
    }
    connection.createStatement().use { statement ->
      snapshot(statement)
      recreateTables(statement)
      migrateCore(statement)
      migrateRelations(statement)
      tables.forEach { table -> statement.execute("DROP TABLE ${table}_v18") }
    }
    createIndexes(connection)
  }

  private fun hasColumn(connection: java.sql.Connection, table: String, column: String): Boolean =
    connection.createStatement().use { statement ->
      statement.executeQuery("PRAGMA table_info($table)").use { rows ->
        generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == column }
      }
    }

  private fun createIndexes(connection: java.sql.Connection) {
    connection.createStatement().use { statement ->
      AuditConvergenceDatabaseSchema.statements
        .filter { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") }
        .forEach(statement::execute)
    }
  }

  private fun snapshot(statement: java.sql.Statement) {
    tables.forEach { table ->
      statement.execute("CREATE TEMP TABLE ${table}_v18 AS SELECT * FROM $table")
    }
    tables.asReversed().forEach { table -> statement.execute("DROP TABLE $table") }
  }

  private fun recreateTables(statement: java.sql.Statement) {
    AuditConvergenceDatabaseSchema.statements
      .filter { sql -> tables.any { table -> sql.startsWith("CREATE TABLE IF NOT EXISTS $table") } }
      .forEach(statement::execute)
  }

  private fun migrateCore(statement: java.sql.Statement) {
    statement.execute("INSERT INTO feature_task_audit_gaps SELECT * FROM feature_task_audit_gaps_v18")
    statement.execute(
      """
      INSERT INTO feature_task_audit_repair_batches(batch_id, workflow_id, generation_id, is_active)
      SELECT old.batch_id, generation.workflow_id, old.generation_id,
        CASE WHEN old.is_active = 1 AND generation.generation = (
          SELECT MAX(candidate_generation.generation)
          FROM feature_task_audit_repair_batches_v18 candidate
          JOIN feature_task_audit_generations candidate_generation
            ON candidate.generation_id = candidate_generation.generation_id
          WHERE candidate.is_active = 1
            AND candidate_generation.workflow_id = generation.workflow_id
        ) THEN 1 ELSE 0 END
      FROM feature_task_audit_repair_batches_v18 old
      JOIN feature_task_audit_generations generation ON old.generation_id = generation.generation_id
      """.trimIndent(),
    )
    statement.execute(
      """
      INSERT INTO feature_task_audit_repair_items
      SELECT generation.workflow_id, item.*
      FROM feature_task_audit_repair_items_v18 item
      JOIN feature_task_audit_gaps_v18 gap ON gap.gap_id = item.gap_id
      JOIN feature_task_audit_generations generation
        ON generation.workflow_id = gap.workflow_id AND generation.generation = gap.generation
      """.trimIndent(),
    )
  }

  private fun migrateRelations(statement: java.sql.Statement) {
    statement.execute(
      """
      INSERT INTO feature_task_audit_repair_item_batch_mapping
      SELECT batch.workflow_id, mapping.*
      FROM feature_task_audit_repair_item_batch_mapping_v18 mapping
      JOIN feature_task_audit_repair_batches batch ON batch.batch_id = mapping.batch_id
      """.trimIndent(),
    )
    statement.execute(
      """
      INSERT INTO feature_task_audit_repair_item_dependencies
      SELECT batch.workflow_id, dependency.*
      FROM feature_task_audit_repair_item_dependencies_v18 dependency
      JOIN feature_task_audit_repair_batches batch ON batch.batch_id = dependency.batch_id
      """.trimIndent(),
    )
    statement.execute(
      "INSERT INTO feature_task_audit_repair_item_results " +
        "SELECT * FROM feature_task_audit_repair_item_results_v18",
    )
    statement.execute(
      """
      INSERT INTO feature_task_audit_repair_non_regression
      SELECT item.workflow_id, constraint_row.*
      FROM feature_task_audit_repair_non_regression_v18 constraint_row
      JOIN feature_task_audit_repair_items item ON item.item_id = constraint_row.item_id
      """.trimIndent(),
    )
    statement.execute(
      """
      INSERT INTO feature_task_audit_gap_dispositions
      SELECT disposition.disposition_id, gap.workflow_id, disposition.gap_id, disposition.status,
        disposition.evidence_observation, disposition.evidence_artifact_ref,
        disposition.evidence_check_ref, disposition.disposition_generation,
        disposition.superseded_by_generation
      FROM feature_task_audit_gap_dispositions_v18 disposition
      JOIN feature_task_audit_gaps_v18 gap ON gap.gap_id = disposition.gap_id
      """.trimIndent(),
    )
  }
}

private fun retainExactConvergenceParentIdentity(connection: java.sql.Connection) {
  val alreadyCurrent = connection.createStatement().use { statement ->
    statement.executeQuery("PRAGMA table_info(feature_task_convergence_records)").use { rows ->
      generateSequence { if (rows.next()) rows.getString("name") else null }.any { it == "parent_record_id" }
    }
  }
  if (alreadyCurrent) return
  connection.createStatement().use { statement ->
    statement.execute("DROP INDEX IF EXISTS idx_convergence_history")
    statement.execute("DROP INDEX IF EXISTS idx_convergence_unresolved")
    statement.execute(
      "ALTER TABLE feature_task_convergence_records RENAME TO feature_task_convergence_records_v18",
    )
    statement.execute(ConvergenceDatabaseSchema.statements.first().replace(" IF NOT EXISTS", ""))
    statement.execute(
      """
      INSERT INTO feature_task_convergence_records(
        record_id, contract_version, workflow_id, record_kind, generation, logical_id,
        parent_logical_id, parent_record_id, phase_id, attempt, review_pass, record_status,
        classification, summary, affected_path, evidence_digest, evidence_ref, created_at
      )
      SELECT child.record_id, child.contract_version, child.workflow_id, child.record_kind,
        child.generation, child.logical_id, child.parent_logical_id, parent.record_id,
        child.phase_id, child.attempt, child.review_pass, child.record_status,
        child.classification, child.summary, child.affected_path, child.evidence_digest,
        child.evidence_ref, child.created_at
      FROM feature_task_convergence_records_v18 child
      LEFT JOIN feature_task_convergence_records_v18 parent
        ON child.workflow_id = parent.workflow_id
       AND child.generation = parent.generation
       AND child.parent_logical_id = parent.logical_id
       AND (
         (child.record_kind = 'AUDIT_REPAIR' AND parent.record_kind = 'AUDIT_GAP') OR
         (child.record_kind = 'REVIEW_DISPOSITION' AND parent.record_kind = 'REVIEW_FINDING')
       )
      """.trimIndent(),
    )
    statement.execute("DROP TABLE feature_task_convergence_records_v18")
    ConvergenceDatabaseSchema.statements
      .filter { it.startsWith("CREATE INDEX") }
      .forEach(statement::execute)
  }
}
