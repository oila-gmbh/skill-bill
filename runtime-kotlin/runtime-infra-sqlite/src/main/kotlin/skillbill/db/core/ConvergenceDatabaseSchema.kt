package skillbill.db.core

internal object ConvergenceDatabaseSchema {
  val tableNames: Set<String> = setOf(
    "feature_task_convergence_records",
    "feature_task_convergence_legacy_imports",
  )

  val indexNames: Set<String> = setOf(
    "idx_convergence_history",
    "idx_convergence_unresolved",
  )

  val statements: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS feature_task_convergence_records (
      record_id TEXT PRIMARY KEY CHECK(length(record_id) BETWEEN 1 AND 160),
      contract_version TEXT NOT NULL CHECK(contract_version = '0.1'),
      workflow_id TEXT NOT NULL CHECK(length(workflow_id) BETWEEN 1 AND 160),
      record_kind TEXT NOT NULL CHECK(record_kind IN (
        'IMPLEMENTATION_OUTCOME', 'IMPLEMENTATION_OBLIGATION', 'AUDIT_GAP', 'AUDIT_REPAIR',
        'REVIEW_FINDING', 'REVIEW_DISPOSITION', 'REPOSITORY_CHECKPOINT', 'LEGACY_IMPORT'
      )),
      generation INTEGER NOT NULL CHECK(generation BETWEEN 1 AND 2147483647),
      logical_id TEXT NOT NULL CHECK(length(logical_id) BETWEEN 1 AND 160),
      parent_logical_id TEXT CHECK(
        parent_logical_id IS NULL OR length(parent_logical_id) BETWEEN 1 AND 160
      ),
      parent_record_id TEXT,
      phase_id TEXT NOT NULL CHECK(phase_id IN ('implement', 'audit', 'review')),
      attempt INTEGER CHECK(attempt IS NULL OR attempt BETWEEN 1 AND 10000),
      review_pass INTEGER CHECK(review_pass IS NULL OR review_pass BETWEEN 1 AND 10000),
      review_pass_key INTEGER GENERATED ALWAYS AS (COALESCE(review_pass, 0)) STORED,
      record_status TEXT NOT NULL CHECK(
        record_status IN ('OPEN', 'RESOLVED', 'COMPLETED', 'FAILED', 'QUARANTINED')
      ),
      classification TEXT CHECK(classification IS NULL OR (
        length(classification) BETWEEN 1 AND 64 AND
        substr(classification, 1, 1) GLOB '[a-z]' AND
        classification NOT GLOB '*[^a-z0-9_-]*'
      )),
      summary TEXT CHECK(summary IS NULL OR length(summary) BETWEEN 1 AND 512),
      affected_path TEXT CHECK(affected_path IS NULL OR length(affected_path) BETWEEN 1 AND 512),
      evidence_digest TEXT NOT NULL CHECK(length(evidence_digest) = 64),
      evidence_ref TEXT CHECK(evidence_ref IS NULL OR length(evidence_ref) BETWEEN 1 AND 512),
      created_at TEXT NOT NULL,
      CHECK(
        (record_kind IN ('AUDIT_REPAIR', 'REVIEW_DISPOSITION') AND parent_logical_id IS NOT NULL) OR
        (record_kind NOT IN ('AUDIT_REPAIR', 'REVIEW_DISPOSITION') AND parent_logical_id IS NULL)
      ),
      CHECK(record_kind != 'REVIEW_FINDING' OR record_status = 'OPEN'),
      CHECK(record_kind != 'REPOSITORY_CHECKPOINT' OR (attempt IS NULL AND review_pass IS NULL)),
      CHECK(record_kind != 'REPOSITORY_CHECKPOINT' OR evidence_ref IS NOT NULL),
      CHECK(
        record_kind NOT IN ('IMPLEMENTATION_OUTCOME', 'IMPLEMENTATION_OBLIGATION') OR
        (phase_id = 'implement' AND attempt IS NOT NULL AND review_pass IS NULL)
      ),
      CHECK(
        record_kind NOT IN ('AUDIT_GAP', 'AUDIT_REPAIR') OR
        (phase_id = 'audit' AND attempt IS NULL AND review_pass IS NULL)
      ),
      CHECK(
        record_kind NOT IN ('REVIEW_FINDING', 'REVIEW_DISPOSITION') OR
        (phase_id = 'review' AND attempt IS NULL AND review_pass IS NOT NULL)
      ),
      CHECK(record_kind != 'LEGACY_IMPORT' OR (attempt IS NULL AND review_pass IS NULL)),
      UNIQUE(workflow_id, record_kind, generation, logical_id),
      UNIQUE(workflow_id, record_kind, generation, review_pass_key, logical_id),
      FOREIGN KEY(workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE,
      FOREIGN KEY(parent_record_id)
        REFERENCES feature_task_convergence_records(record_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_convergence_history
      ON feature_task_convergence_records(workflow_id, generation, created_at)
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_convergence_unresolved
      ON feature_task_convergence_records(
        workflow_id, record_kind, record_status, classification, generation
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
      FOREIGN KEY(workflow_id) REFERENCES feature_task_workflows(workflow_id)
    )
    """.trimIndent(),
  )
}
