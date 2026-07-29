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
      version = 19,
      name = "retain-exact-convergence-parent-identity",
      operation = ::retainExactConvergenceParentIdentity,
    ),
  )
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
