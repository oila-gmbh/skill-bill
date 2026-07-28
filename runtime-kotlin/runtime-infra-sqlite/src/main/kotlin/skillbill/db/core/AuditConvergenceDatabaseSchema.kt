package skillbill.db.core

internal object AuditConvergenceDatabaseSchema {
  val tableNames: Set<String> = setOf(
    "feature_task_audit_generations",
    "feature_task_audit_satisfied_criteria",
    "feature_task_audit_gaps",
    "feature_task_audit_repair_batches",
    "feature_task_audit_repair_items",
    "feature_task_audit_repair_item_batch_mapping",
    "feature_task_audit_repair_item_dependencies",
    "feature_task_audit_repair_item_results",
    "feature_task_audit_repair_non_regression",
    "feature_task_audit_gap_dispositions",
  )

  val indexNames: Set<String> = setOf(
    "idx_audit_generations_workflow",
    "idx_audit_gaps_workflow_generation",
    "idx_audit_gaps_status",
    "idx_audit_repair_items_gap",
    "idx_audit_repair_results_item",
  )

  val statements: List<String> = listOf(
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_generations (
      generation_id TEXT PRIMARY KEY CHECK(length(generation_id) BETWEEN 1 AND 160),
      workflow_id TEXT NOT NULL CHECK(length(workflow_id) BETWEEN 1 AND 160),
      generation INTEGER NOT NULL CHECK(generation > 0),
      repository_fingerprint TEXT NOT NULL CHECK(length(repository_fingerprint) = 64),
      repository_evidence_ref TEXT NOT NULL CHECK(length(repository_evidence_ref) BETWEEN 1 AND 512),
      created_at TEXT NOT NULL,
      UNIQUE(workflow_id, generation),
      FOREIGN KEY(workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_audit_generations_workflow
      ON feature_task_audit_generations(workflow_id, generation)
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_satisfied_criteria (
      workflow_id TEXT NOT NULL,
      generation INTEGER NOT NULL,
      criterion_ref TEXT NOT NULL CHECK(criterion_ref GLOB 'AC-[0-9][0-9][0-9]'),
      PRIMARY KEY(workflow_id, generation, criterion_ref),
      FOREIGN KEY(workflow_id, generation) REFERENCES feature_task_audit_generations(workflow_id, generation) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_gaps (
      gap_id TEXT PRIMARY KEY CHECK(length(gap_id) BETWEEN 1 AND 160),
      workflow_id TEXT NOT NULL CHECK(length(workflow_id) BETWEEN 1 AND 160),
      generation INTEGER NOT NULL CHECK(generation > 0),
      acceptance_criterion_ref TEXT NOT NULL CHECK(acceptance_criterion_ref GLOB 'AC-[0-9][0-9][0-9]'),
      acceptance_criterion_text TEXT NOT NULL CHECK(length(acceptance_criterion_text) BETWEEN 1 AND 2048),
      diagnosis TEXT NOT NULL CHECK(length(diagnosis) BETWEEN 1 AND 2048),
      affected_boundary TEXT NOT NULL CHECK(length(affected_boundary) BETWEEN 1 AND 2048),
      status TEXT NOT NULL CHECK(status IN ('NEW', 'RECURRING', 'RESOLVED', 'SUPERSEDED', 'STILL_OPEN')),
      recurrence INTEGER NOT NULL CHECK(recurrence >= 0),
      first_seen_generation INTEGER NOT NULL CHECK(first_seen_generation > 0),
      failure_observation TEXT NOT NULL CHECK(failure_observation IN (
        'REQUIRED_BEHAVIOR_ABSENT', 'VERIFICATION_FAILED', 'CONTRACT_REJECTED', 'STATE_MISMATCH',
        'FIX_VERIFIED', 'ALREADY_SATISFIED_VERIFIED', 'RESOLUTION_VERIFIED', 'RECURRENCE_VERIFIED'
      )),
      failure_artifact_ref TEXT NOT NULL CHECK(length(failure_artifact_ref) BETWEEN 1 AND 256),
      failure_check_ref TEXT NOT NULL CHECK(length(failure_check_ref) BETWEEN 1 AND 256),
      FOREIGN KEY(workflow_id, generation) REFERENCES feature_task_audit_generations(workflow_id, generation) ON DELETE CASCADE,
      UNIQUE(workflow_id, generation, gap_id)
    )
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_audit_gaps_workflow_generation
      ON feature_task_audit_gaps(workflow_id, generation)
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_audit_gaps_status
      ON feature_task_audit_gaps(status)
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_repair_batches (
      batch_id TEXT PRIMARY KEY CHECK(length(batch_id) BETWEEN 1 AND 160),
      generation_id TEXT NOT NULL CHECK(length(generation_id) BETWEEN 1 AND 160),
      is_active INTEGER NOT NULL CHECK(is_active IN (0, 1)),
      FOREIGN KEY(generation_id) REFERENCES feature_task_audit_generations(generation_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_repair_items (
      item_id TEXT PRIMARY KEY CHECK(length(item_id) BETWEEN 1 AND 160),
      gap_id TEXT NOT NULL CHECK(length(gap_id) BETWEEN 1 AND 160),
      intended_outcome TEXT NOT NULL CHECK(length(intended_outcome) BETWEEN 1 AND 2048),
      implementation_actions TEXT NOT NULL CHECK(length(implementation_actions) > 0),
      affected_paths_or_symbols TEXT NOT NULL CHECK(length(affected_paths_or_symbols) > 0),
      required_verification TEXT NOT NULL CHECK(length(required_verification) > 0),
      dependencies TEXT NOT NULL,
      FOREIGN KEY(gap_id) REFERENCES feature_task_audit_gaps(gap_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_audit_repair_items_gap
      ON feature_task_audit_repair_items(gap_id)
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_repair_item_batch_mapping (
      batch_id TEXT NOT NULL,
      item_id TEXT NOT NULL,
      PRIMARY KEY(batch_id, item_id),
      FOREIGN KEY(batch_id) REFERENCES feature_task_audit_repair_batches(batch_id) ON DELETE CASCADE,
      FOREIGN KEY(item_id) REFERENCES feature_task_audit_repair_items(item_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_repair_item_dependencies (
      batch_id TEXT NOT NULL,
      item_id TEXT NOT NULL,
      depends_on_item_id TEXT NOT NULL,
      PRIMARY KEY(batch_id, item_id, depends_on_item_id),
      FOREIGN KEY(batch_id, item_id) REFERENCES feature_task_audit_repair_item_batch_mapping(batch_id, item_id) ON DELETE CASCADE,
      FOREIGN KEY(depends_on_item_id) REFERENCES feature_task_audit_repair_items(item_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_repair_item_results (
      result_id TEXT PRIMARY KEY CHECK(length(result_id) BETWEEN 1 AND 160),
      item_id TEXT NOT NULL,
      workflow_id TEXT NOT NULL,
      outcome TEXT NOT NULL CHECK(outcome IN ('FIXED', 'ALREADY_SATISFIED', 'SUPERSEDED')),
      evidence_ref TEXT NOT NULL CHECK(length(evidence_ref) BETWEEN 1 AND 512),
      verification_ref TEXT NOT NULL CHECK(length(verification_ref) BETWEEN 1 AND 512),
      disposition_generation INTEGER NOT NULL CHECK(disposition_generation > 0),
      created_at TEXT NOT NULL,
      FOREIGN KEY(item_id) REFERENCES feature_task_audit_repair_items(item_id) ON DELETE CASCADE,
      FOREIGN KEY(workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE INDEX IF NOT EXISTS idx_audit_repair_results_item
      ON feature_task_audit_repair_item_results(item_id)
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_repair_non_regression (
      item_id TEXT NOT NULL,
      constraint_text TEXT NOT NULL CHECK(length(constraint_text) BETWEEN 1 AND 2048),
      priority INTEGER NOT NULL CHECK(priority >= 0),
      PRIMARY KEY(item_id, priority),
      FOREIGN KEY(item_id) REFERENCES feature_task_audit_repair_items(item_id) ON DELETE CASCADE
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS feature_task_audit_gap_dispositions (
      disposition_id TEXT PRIMARY KEY CHECK(length(disposition_id) BETWEEN 1 AND 160),
      gap_id TEXT NOT NULL CHECK(length(gap_id) BETWEEN 1 AND 160),
      status TEXT NOT NULL CHECK(status IN ('RESOLVED', 'RECURRING', 'SUPERSEDED')),
      evidence_observation TEXT NOT NULL CHECK(evidence_observation IN (
        'FIX_VERIFIED', 'ALREADY_SATISFIED_VERIFIED', 'RESOLUTION_VERIFIED', 'RECURRENCE_VERIFIED'
      )),
      evidence_artifact_ref TEXT NOT NULL CHECK(length(evidence_artifact_ref) BETWEEN 1 AND 256),
      evidence_check_ref TEXT NOT NULL CHECK(length(evidence_check_ref) BETWEEN 1 AND 256),
      disposition_generation INTEGER NOT NULL CHECK(disposition_generation > 0),
      superseded_by_generation INTEGER CHECK(superseded_by_generation IS NULL OR superseded_by_generation > 0),
      FOREIGN KEY(gap_id) REFERENCES feature_task_audit_gaps(gap_id) ON DELETE CASCADE
    )
    """.trimIndent(),
  )
}
