package skillbill.db.core

import skillbill.db.telemetry.FeedbackEventMigration
import skillbill.db.telemetry.GoalTelemetryMigration
import skillbill.db.telemetry.TelemetryOutboxLastErrorMigration
import skillbill.db.workflow.FeatureTaskRuntimeAuditGenerationMigration
import java.sql.Connection

internal object DatabaseMigrations {
  val migrations: List<DatabaseMigration> =
    listOf(
      DatabaseMigration(
        version = 1,
        name = "add-review-workflow-session-columns",
        operation = DatabaseColumnMigrations::apply,
      ),
      DatabaseMigration(
        version = 2,
        name = "normalize-feedback-event-outcomes",
        operation = FeedbackEventMigration::apply,
      ),
      DatabaseMigration(
        version = 3,
        name = "add-goal-telemetry-tables",
        operation = GoalTelemetryMigration::apply,
      ),
      DatabaseMigration(
        version = 4,
        name = "add-work-list-state-metadata",
        operation = DatabaseColumnMigrations::applyWorkListMetadata,
      ),
      DatabaseMigration(
        version = 5,
        name = "recover-work-list-issue-keys",
        operation = DatabaseColumnMigrations::recoverWorkListIssueKeys,
      ),
      DatabaseMigration(
        version = 6,
        name = "add-feature-task-execution-identities",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS feature_task_execution_identities (
                workflow_id TEXT PRIMARY KEY,
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                governed_spec_path TEXT NOT NULL,
                mode TEXT NOT NULL CHECK (mode IN ('prose', 'runtime')),
                route_scope TEXT NOT NULL CHECK (route_scope IN ('standalone', 'goal_child')),
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
                connection.optionalRepairEvidenceColumn("goal_shared_preplans_pre_0_2")
              },
                FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE INDEX IF NOT EXISTS idx_feature_task_identity_lookup
                ON feature_task_execution_identities(normalized_issue_key, repository_identity, route_scope)
              """.trimIndent(),
            )
          }
        },
      ),
      DatabaseMigration(
        version = 7,
        name = "add-feature-task-runtime-worker-leases",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS feature_task_runtime_worker_leases (
                workflow_id TEXT PRIMARY KEY,
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                generation INTEGER NOT NULL CHECK (generation > 0),
                owner_token TEXT NOT NULL,
                host_identity TEXT NOT NULL,
                boot_identity TEXT NOT NULL,
                pid INTEGER NOT NULL CHECK (pid > 0),
                process_birth_token TEXT NOT NULL,
                lease_state TEXT NOT NULL CHECK (lease_state IN ('active', 'takeover_reserved')),
                heartbeat_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                phase_id TEXT NOT NULL,
                phase_attempt INTEGER NOT NULL CHECK (phase_attempt > 0),
                FOREIGN KEY (workflow_id) REFERENCES feature_task_workflows(workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
          }
        },
      ),
      DatabaseMigration(
        version = 8,
        name = "add-goal-planning-preparations",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS goal_planning_preparations (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                governed_sub_spec_path TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status IN ('pending', 'prepared')) DEFAULT 'prepared',
                contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
                parent_spec_hash TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
                connection.optionalRepairEvidenceColumn("goal_subtask_plans_pre_0_2")
              },
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (parent_goal_workflow_id, subtask_id)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE INDEX IF NOT EXISTS idx_goal_planning_preparations_lookup
                ON goal_planning_preparations(normalized_issue_key, repository_identity)
              """.trimIndent(),
            )
          }
        },
      ),
      DatabaseMigration(
        version = 9,
        name = "normalize-goal-planning-preparations",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS goal_shared_preplans (
                parent_goal_workflow_id TEXT PRIMARY KEY,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
                payload_sha256 TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(normalized_issue_key, repository_identity)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS goal_subtask_plans (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
                governed_sub_spec_path TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version = '0.1'),
                payload_sha256 TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY(parent_goal_workflow_id, subtask_id),
                UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
                UNIQUE(parent_goal_workflow_id, manifest_order),
                FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
                "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
            )
          }
        },
      ),
      DatabaseMigration(
        version = 10,
        name = "rebuild-goal-planning-plans-for-phase-output-0-2",
        operation = ::rebuildGoalPlanningPlansForPhaseOutputV02,
      ),
      DatabaseMigration(
        version = 11,
        name = "require-goal-planning-phase-output-0-2",
        operation = ::requireGoalPlanningPhaseOutputV02,
      ),
      DatabaseMigration(
        version = 12,
        name = "add-bounded-review-accounting",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS review_accounting (
                review_id TEXT PRIMARY KEY,
                packet_digest TEXT NOT NULL,
                bounded_payload_json TEXT NOT NULL,
                updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
              )
              """.trimIndent(),
            )
          }
        },
      ),
      DatabaseMigration(
        version = 13,
        name = "allow-goal-planning-phase-output-0-3",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE goal_subtask_plans RENAME TO goal_subtask_plans_pre_0_3")
            statement.execute("ALTER TABLE goal_shared_preplans RENAME TO goal_shared_preplans_pre_0_3")
            statement.execute(
              """
              CREATE TABLE goal_shared_preplans (
                parent_goal_workflow_id TEXT PRIMARY KEY,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.2', '0.3')),
                payload_sha256 TEXT NOT NULL,
                preplan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
                connection.optionalRepairEvidenceColumn("goal_shared_preplans_pre_0_3")
              },
                UNIQUE(normalized_issue_key, repository_identity)
              )
              """.trimIndent(),
            )
            statement.execute(
              """
              CREATE TABLE goal_subtask_plans (
                parent_goal_workflow_id TEXT NOT NULL,
                normalized_issue_key TEXT NOT NULL,
                repository_identity TEXT NOT NULL,
                subtask_id INTEGER NOT NULL CHECK (subtask_id > 0),
                manifest_order INTEGER NOT NULL CHECK (manifest_order >= 0),
                governed_sub_spec_path TEXT NOT NULL,
                sub_spec_hash TEXT NOT NULL,
                preparation_status TEXT NOT NULL CHECK (preparation_status = 'prepared'),
                contract_version TEXT NOT NULL CHECK (contract_version = '0.2'),
                parent_spec_hash TEXT NOT NULL,
                decomposition_manifest_hash TEXT NOT NULL,
                planning_contract_id TEXT NOT NULL,
                planning_contract_version TEXT NOT NULL CHECK (planning_contract_version = '0.2'),
                phase_output_contract_id TEXT NOT NULL,
                phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version IN ('0.2', '0.3')),
                payload_sha256 TEXT NOT NULL,
                plan_payload_json TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${
                connection.optionalRepairEvidenceColumn("goal_subtask_plans_pre_0_3")
              },
                PRIMARY KEY(parent_goal_workflow_id, subtask_id),
                UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
                UNIQUE(parent_goal_workflow_id, manifest_order),
                FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
              )
              """.trimIndent(),
            )
            statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM goal_shared_preplans_pre_0_3")
            statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM goal_subtask_plans_pre_0_3")
            statement.execute("DROP TABLE goal_subtask_plans_pre_0_3")
            statement.execute("DROP TABLE goal_shared_preplans_pre_0_3")
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
                "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)",
            )
          }
        },
      ),
      DatabaseMigration(
        version = 14,
        name = "add-rejected-output-diagnostics",
        operation = { connection ->
          connection.createStatement().use { statement ->
            statement.execute(
              """
              CREATE TABLE IF NOT EXISTS rejected_output_diagnostics (
                identity TEXT PRIMARY KEY,
                workflow_id TEXT NOT NULL,
                phase_id TEXT NOT NULL,
                attempt INTEGER NOT NULL CHECK (attempt > 0),
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
                UNIQUE(workflow_id, phase_id, attempt),
                CHECK (
                  (lifecycle = 'stored' AND payload IS NOT NULL) OR
                  (lifecycle IN ('oversized', 'expired') AND payload IS NULL)
                )
              )
              """.trimIndent(),
            )
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_selection " +
                "ON rejected_output_diagnostics(workflow_id, phase_id, attempt)",
            )
            statement.execute(
              "CREATE INDEX IF NOT EXISTS idx_rejected_output_diagnostic_retention " +
                "ON rejected_output_diagnostics(lifecycle, recorded_at)",
            )
          }
        },
      ),
      DatabaseMigration(
        version = 15,
        name = "add-private-producer-output-evidence",
        operation = { connection ->
          connection.createStatement().use {
            it.execute(
              """
              CREATE TABLE IF NOT EXISTS producer_output_evidence (
                workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
                attempt INTEGER NOT NULL CHECK (attempt > 0),
                agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
                byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
                PRIMARY KEY (workflow_id, phase_id, attempt)
              )
              """.trimIndent(),
            )
          }
        },
      ),
      DatabaseMigration(
        version = 16,
        name = "rekey-producer-output-evidence-by-generation",
        operation = { connection ->
          val alreadyRekeyed = connection.prepareStatement(
            "SELECT 1 FROM pragma_table_info('producer_output_evidence') WHERE name = 'generation'",
          ).use { statement ->
            statement.executeQuery().use { resultSet -> resultSet.next() }
          }
          if (!alreadyRekeyed) {
            connection.createStatement().use {
              // SQLite cannot widen a PRIMARY KEY in place, so the table is rebuilt and every
              // pre-generation row is carried across at generation 0.
              it.execute(
                "ALTER TABLE producer_output_evidence RENAME TO producer_output_evidence_pre_generation",
              )
              it.execute(
                """
                CREATE TABLE IF NOT EXISTS producer_output_evidence (
                  workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
                  generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
                  attempt INTEGER NOT NULL CHECK (attempt > 0),
                  agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
                  byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
                  PRIMARY KEY (workflow_id, phase_id, generation, attempt)
                )
                """.trimIndent(),
              )
              it.execute(
                """
                INSERT INTO producer_output_evidence (
                  workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at,
                  byte_size, sha256, payload
                )
                SELECT workflow_id, phase_id, 0, attempt, agent_id, model, recorded_at,
                       byte_size, sha256, payload
                FROM producer_output_evidence_pre_generation
                """.trimIndent(),
              )
              it.execute("DROP TABLE producer_output_evidence_pre_generation")
            }
          }
        },
      ),
      DatabaseMigration(
        version = 17,
        name = "persist-goal-planning-repair-evidence",
        operation = ::persistGoalPlanningRepairEvidence,
      ),
      DatabaseMigration(
        version = 18,
        name = "persist-legacy-goal-planning-repair-evidence",
        operation = ::persistLegacyGoalPlanningRepairEvidence,
      ),
      DatabaseMigration(
        version = 19,
        name = "add-goal-runner-controls",
        operation = ::addGoalRunnerControls,
      ),
      DatabaseMigration(
        version = 20,
        name = "add-goal-runner-control-state",
        operation = ::addGoalRunnerControlState,
      ),
      DatabaseMigration(
        version = 21,
        name = "add-delegated-review-lifecycle-projection",
        operation = ::addDelegatedReviewLifecycleProjection,
      ),
      DatabaseMigration(
        version = 22,
        name = "drop-delegated-review-lifecycle-tables",
        operation = ::dropDelegatedReviewLifecycleTables,
      ),
      DatabaseMigration(
        version = 23,
        name = "add-feature-task-runtime-audit-generations",
        operation = FeatureTaskRuntimeAuditGenerationMigration::apply,
      ),
      DatabaseMigration(
        version = 24,
        name = "backfill-review-attribution-canonicals",
        operation = ReviewAttributionBackfillMigration::apply,
      ),
      DatabaseMigration(
        version = 25,
        name = "add-review-run-lane-attribution",
        operation = ::addReviewRunLaneAttribution,
      ),
      DatabaseMigration(
        version = 26,
        name = "relax-telemetry-outbox-last-error",
        operation = TelemetryOutboxLastErrorMigration::apply,
      ),
      DatabaseMigration(
        version = 27,
        name = "add-review-finding-outcome-key",
        operation = ::addReviewFindingOutcomeKey,
      ),
      DatabaseMigration(
        version = 28,
        name = "rekey-producer-output-evidence-by-agent",
        operation = ::rekeyProducerOutputEvidenceByAgent,
      ),
      DatabaseMigration(
        version = 29,
        name = "rekey-diagnostic-evidence-by-repair-turn",
        operation = ::rekeyDiagnosticEvidenceByRepairTurn,
      ),
    ).also(::requireDeterministicMigrations)

  fun apply(connection: Connection) {
    // Optimistic reads-only pre-check: every open would otherwise take the write lock just to learn
    // there is nothing to do. The in-lock re-derivation below stays the sole authority.
    val ledger = MigrationLedger.readState(connection)
    if (!ledger.hasPendingWork(migrations.map { migration -> migration.name })) return

    connection.inImmediateTransaction {
      MigrationLedger.ensureNameKeyed(this)
      val appliedNames = MigrationLedger.appliedNames(this)
      migrations
        .filterNot { migration -> migration.name in appliedNames }
        .forEach { migration ->
          migration.apply(this)
          MigrationLedger.record(this, migration)
        }
    }
  }

  private fun requireDeterministicMigrations(migrations: List<DatabaseMigration>) {
    val versions = migrations.map { migration -> migration.version }
    val names = migrations.map { migration -> migration.name }

    require(versions == versions.sorted()) { "Database migrations must be ordered by version." }
    require(versions.toSet().size == versions.size) { "Database migration versions must be unique." }
    require(names.toSet().size == names.size) { "Database migration names must be unique." }
  }
}

private fun persistGoalPlanningRepairEvidence(connection: Connection) {
  connection.createStatement().use { statement ->
    listOf("goal_shared_preplans", "goal_subtask_plans").forEach { table ->
      val hasEvidenceColumn = statement.executeQuery(
        "SELECT 1 FROM pragma_table_info('$table') WHERE name = 'repair_evidence_json'",
      ).use { rows -> rows.next() }
      if (!hasEvidenceColumn) {
        statement.execute("ALTER TABLE $table ADD COLUMN repair_evidence_json TEXT")
      }
    }
  }
}

private fun persistLegacyGoalPlanningRepairEvidence(connection: Connection) {
  connection.createStatement().use { statement ->
    listOf("preplan_repair_evidence_json", "plan_repair_evidence_json").forEach { column ->
      val hasEvidenceColumn = statement.executeQuery(
        "SELECT 1 FROM pragma_table_info('goal_planning_preparations') WHERE name = '$column'",
      ).use { rows -> rows.next() }
      if (!hasEvidenceColumn) {
        statement.execute("ALTER TABLE goal_planning_preparations ADD COLUMN $column TEXT")
      }
    }
  }
}

private fun addGoalRunnerControls(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS goal_runner_controls (
        parent_workflow_id TEXT PRIMARY KEY,
        review_policy_json TEXT,
        out_of_band_acceptances_json TEXT NOT NULL DEFAULT '[]',
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
    )
  }
}

private fun addGoalRunnerControlState(connection: Connection) {
  connection.createStatement().use { statement ->
    val hasControlState = statement.executeQuery(
      "SELECT 1 FROM pragma_table_info('goal_runner_controls') WHERE name = 'control_state_json'",
    ).use { rows -> rows.next() }
    if (!hasControlState) {
      statement.execute("ALTER TABLE goal_runner_controls ADD COLUMN control_state_json TEXT")
    }
  }
}

private fun addDelegatedReviewLifecycleProjection(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute(
      """
      CREATE TABLE IF NOT EXISTS review_delegated_lifecycle (
        review_id TEXT PRIMARY KEY,
        packet_digest TEXT NOT NULL,
        contract_version TEXT NOT NULL CHECK (contract_version = '0.1'),
        bounded_payload_json TEXT NOT NULL,
        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )
      """.trimIndent(),
    )
    statement.execute(
      """
      CREATE INDEX IF NOT EXISTS idx_review_delegated_lifecycle_review
        ON review_delegated_lifecycle(review_id, updated_at)
      """.trimIndent(),
    )
  }
}

private fun addReviewRunLaneAttribution(connection: Connection) {
  DatabaseReviewLedgerSchema.reviewRunLaneStatements.forEach { sql ->
    connection.createStatement().use { statement -> statement.execute(sql) }
  }
  DatabaseReviewColumnMigrations.ensureFindingLaneColumns(connection)
  DatabaseReviewColumnMigrations.ensureReviewRunLaneDispositionColumns(connection)
}

// The unaddressed_findings key columns go through ensureColumn (which also runs unconditionally on
// every startup) rather than being appended to an already-applied CREATE body, which would be a
// silent no-op for every existing store.
private fun addReviewFindingOutcomeKey(connection: Connection) {
  DatabaseReviewColumnMigrations.ensureReviewFindingOutcomeKeyColumns(connection)
  DatabaseReviewLedgerSchema.reviewFindingOutcomeStatements.forEach { sql ->
    connection.createStatement().use { statement -> statement.execute(sql) }
  }
  DatabaseReviewColumnMigrations.ensureReviewFindingOutcomeColumns(connection)
}

private fun rekeyProducerOutputEvidenceByAgent(connection: Connection) {
  if (producerOutputEvidencePrimaryKeyIncludesAgentId(connection)) return
  connection.createStatement().use {
    // SQLite cannot widen a PRIMARY KEY in place, so the table is rebuilt. agent_id was already
    // NOT NULL on every row written by the current runtime, so no backfill is required.
    it.execute(
      "ALTER TABLE producer_output_evidence RENAME TO producer_output_evidence_pre_agent",
    )
    it.execute(
      """
      CREATE TABLE IF NOT EXISTS producer_output_evidence (
        workflow_id TEXT NOT NULL, phase_id TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 0 CHECK (generation >= 0),
        attempt INTEGER NOT NULL CHECK (attempt > 0),
        agent_id TEXT NOT NULL, model TEXT NOT NULL, recorded_at TEXT NOT NULL,
        byte_size INTEGER NOT NULL CHECK (byte_size >= 0), sha256 TEXT NOT NULL, payload BLOB,
        PRIMARY KEY (workflow_id, phase_id, generation, attempt, agent_id)
      )
      """.trimIndent(),
    )
    it.execute(
      """
      INSERT INTO producer_output_evidence (
        workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at,
        byte_size, sha256, payload
      )
      SELECT workflow_id, phase_id, generation, attempt, agent_id, model, recorded_at,
             byte_size, sha256, payload
      FROM producer_output_evidence_pre_agent
      """.trimIndent(),
    )
    it.execute("DROP TABLE producer_output_evidence_pre_agent")
  }
}

private fun producerOutputEvidencePrimaryKeyIncludesAgentId(connection: Connection): Boolean {
  val ddl = connection.createStatement().use { statement ->
    statement.executeQuery(
      "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'producer_output_evidence'",
    ).use { rows ->
      if (!rows.next()) return false
      rows.getString("sql").orEmpty()
    }
  }
  return Regex(
    """PRIMARY\s+KEY\s*\([^)]*\bagent_id\b[^)]*\)""",
    RegexOption.IGNORE_CASE,
  ).containsMatchIn(ddl)
}

private fun dropDelegatedReviewLifecycleTables(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("DROP INDEX IF EXISTS idx_review_delegated_lifecycle_review")
    statement.execute("DROP INDEX IF EXISTS idx_review_lifecycle_events_review")
    statement.execute("DROP TABLE IF EXISTS review_delegated_lifecycle")
    statement.execute("DROP TABLE IF EXISTS review_lifecycle_events")
  }
}

internal class DatabaseMigration(
  val version: Int,
  val name: String,
  private val operation: (Connection) -> Unit,
) {
  fun apply(connection: Connection) {
    operation(connection)
  }
}
