package skillbill.db.core

import skillbill.db.telemetry.TelemetryOutboxLastErrorMigration
import skillbill.db.workflow.FeatureTaskPhaseSettlementsMigration
import skillbill.db.workflow.FeatureTaskRuntimeAuditGenerationMigration

internal val databaseMigrationsLate: List<DatabaseMigration> =
  listOf(
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
                FOREIGN KEY(parent_goal_workflow_id)
                REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
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
    DatabaseMigration(
      version = 30,
      name = "add-review-run-stage-state",
      operation = DatabaseReviewColumnMigrations::ensureReviewStageStateTables,
    ),
    DatabaseMigration(
      version = 31,
      name = "add-review-run-pass-claims",
      operation = DatabaseReviewColumnMigrations::ensureReviewStageStateTables,
    ),
    DatabaseMigration(
      version = 32,
      name = "allow-goal-planning-phase-output-0-4",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputV04,
    ),
    DatabaseMigration(
      version = 33,
      name = "allow-goal-planning-phase-output-0-5",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputV05,
    ),
    DatabaseMigration(
      version = 34,
      name = "allow-goal-planning-phase-output-0-6",
      operation = ::rebuildGoalPlanningPlansForPhaseOutputV06,
    ),
    DatabaseMigration(
      version = 35,
      name = "add-feature-task-phase-settlements",
      operation = FeatureTaskPhaseSettlementsMigration::apply,
    ),
  )
