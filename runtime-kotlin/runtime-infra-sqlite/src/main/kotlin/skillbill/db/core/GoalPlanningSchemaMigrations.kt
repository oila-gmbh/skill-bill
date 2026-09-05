package skillbill.db.core

import skillbill.error.InvalidGoalPlanningPreparationSchemaError
import java.sql.Connection

/**
 * Goal-planning table rebuilds, kept out of the migration registry so that registry stays a readable
 * version-ordered list instead of a container for every migration's DDL.
 *
 * The rebuilds recreate the same two tables and differ only in which `phase_output_contract_version`
 * values the rebuilt table accepts and which renamed table the rows come from, so the DDL is shared and
 * those two differences are the parameters.
 */
internal fun rebuildGoalPlanningPlansForPhaseOutputVersion2(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("ALTER TABLE goal_subtask_plans RENAME TO $LEGACY_PLANS")
    statement.execute("ALTER TABLE goal_shared_preplans RENAME TO $LEGACY_PREPLANS")
    statement.execute(connection.sharedPreplansDdl(LEGACY_PREPLANS, "IN ('0.1', '0.2')"))
    statement.execute(connection.subtaskPlansDdl(LEGACY_PLANS, "IN ('0.1', '0.2')"))
    // Carry existing planning rows across the widened constraint. Their recorded
    // phase-output provenance stays truthful; the read seam decides whether a legacy
    // stamp is still usable, so discarding in-flight goal planning is never automatic.
    statement.execute(
      "INSERT INTO goal_shared_preplans SELECT * FROM $LEGACY_PREPLANS",
    )
    statement.execute(
      "INSERT INTO goal_subtask_plans SELECT * FROM $LEGACY_PLANS",
    )
    // Drop before recreating the index: SQLite carries an index across a table rename, so
    // the renamed table still owns the old index name until it is gone.
    statement.execute("DROP TABLE $LEGACY_PLANS")
    statement.execute("DROP TABLE $LEGACY_PREPLANS")
    statement.execute(ORDERED_SUBTASK_PLANS_INDEX)
  }
}

internal fun requireGoalPlanningPhaseOutputVersion2(connection: Connection) {
  connection.createStatement().use { statement ->
    val incompatibleTable = listOf("goal_shared_preplans", "goal_subtask_plans").firstOrNull { table ->
      statement.executeQuery(
        "SELECT 1 FROM $table WHERE phase_output_contract_version != '0.2' LIMIT 1",
      ).use { rows -> rows.next() }
    }
    if (incompatibleTable != null) {
      throw InvalidGoalPlanningPreparationSchemaError(
        sourceLabel = incompatibleTable,
        fieldPath = "phase_output_contract_version",
        reason = "migration requires compatible phase-output provenance '0.2'",
      )
    }

    statement.execute("ALTER TABLE goal_subtask_plans RENAME TO $STRICT_LEGACY_PLANS")
    statement.execute("ALTER TABLE goal_shared_preplans RENAME TO $STRICT_LEGACY_PREPLANS")
    statement.execute(connection.sharedPreplansDdl(STRICT_LEGACY_PREPLANS, "= '0.2'"))
    statement.execute(connection.subtaskPlansDdl(STRICT_LEGACY_PLANS, "= '0.2'"))
    statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM $STRICT_LEGACY_PREPLANS")
    statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM $STRICT_LEGACY_PLANS")
    statement.execute("DROP TABLE $STRICT_LEGACY_PLANS")
    statement.execute("DROP TABLE $STRICT_LEGACY_PREPLANS")
    statement.execute(ORDERED_SUBTASK_PLANS_INDEX)
  }
}

internal fun rebuildGoalPlanningPlansForPhaseOutputVersion4(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("ALTER TABLE goal_subtask_plans RENAME TO $LEGACY_PLANS_PRE_0_4")
    statement.execute("ALTER TABLE goal_shared_preplans RENAME TO $LEGACY_PREPLANS_PRE_0_4")
    statement.execute(connection.sharedPreplansDdl(LEGACY_PREPLANS_PRE_0_4, PHASE_OUTPUT_CHECK_THROUGH_0_4))
    statement.execute(connection.subtaskPlansDdl(LEGACY_PLANS_PRE_0_4, PHASE_OUTPUT_CHECK_THROUGH_0_4))
    statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM $LEGACY_PREPLANS_PRE_0_4")
    statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM $LEGACY_PLANS_PRE_0_4")
    statement.execute("DROP TABLE $LEGACY_PLANS_PRE_0_4")
    statement.execute("DROP TABLE $LEGACY_PREPLANS_PRE_0_4")
    statement.execute(ORDERED_SUBTASK_PLANS_INDEX)
  }
}

internal fun rebuildGoalPlanningPlansForPhaseOutputVersion5(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("ALTER TABLE goal_subtask_plans RENAME TO $LEGACY_PLANS_PRE_0_5")
    statement.execute("ALTER TABLE goal_shared_preplans RENAME TO $LEGACY_PREPLANS_PRE_0_5")
    statement.execute(connection.sharedPreplansDdl(LEGACY_PREPLANS_PRE_0_5, PHASE_OUTPUT_CHECK_THROUGH_0_5))
    statement.execute(connection.subtaskPlansDdl(LEGACY_PLANS_PRE_0_5, PHASE_OUTPUT_CHECK_THROUGH_0_5))
    statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM $LEGACY_PREPLANS_PRE_0_5")
    statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM $LEGACY_PLANS_PRE_0_5")
    statement.execute("DROP TABLE $LEGACY_PLANS_PRE_0_5")
    statement.execute("DROP TABLE $LEGACY_PREPLANS_PRE_0_5")
    statement.execute(ORDERED_SUBTASK_PLANS_INDEX)
  }
}

internal fun rebuildGoalPlanningPlansForPhaseOutputVersion6(connection: Connection) {
  connection.createStatement().use { statement ->
    statement.execute("ALTER TABLE goal_subtask_plans RENAME TO $LEGACY_PLANS_PRE_0_6")
    statement.execute("ALTER TABLE goal_shared_preplans RENAME TO $LEGACY_PREPLANS_PRE_0_6")
    statement.execute(connection.sharedPreplansDdl(LEGACY_PREPLANS_PRE_0_6, PHASE_OUTPUT_CHECK_THROUGH_0_6))
    statement.execute(connection.subtaskPlansDdl(LEGACY_PLANS_PRE_0_6, PHASE_OUTPUT_CHECK_THROUGH_0_6))
    statement.execute("INSERT INTO goal_shared_preplans SELECT * FROM $LEGACY_PREPLANS_PRE_0_6")
    statement.execute("INSERT INTO goal_subtask_plans SELECT * FROM $LEGACY_PLANS_PRE_0_6")
    statement.execute("DROP TABLE $LEGACY_PLANS_PRE_0_6")
    statement.execute("DROP TABLE $LEGACY_PREPLANS_PRE_0_6")
    statement.execute(ORDERED_SUBTASK_PLANS_INDEX)
  }
}

private const val PHASE_OUTPUT_CHECK_THROUGH_0_4 = "IN ('0.2', '0.3', '0.4')"

private const val PHASE_OUTPUT_CHECK_THROUGH_0_5 = "IN ('0.2', '0.3', '0.4', '0.5')"

private const val PHASE_OUTPUT_CHECK_THROUGH_0_6 = "IN ('0.2', '0.3', '0.4', '0.5', '0.6')"

private const val LEGACY_PREPLANS_PRE_0_4 = "goal_shared_preplans_pre_0_4"

private const val LEGACY_PLANS_PRE_0_4 = "goal_subtask_plans_pre_0_4"

private const val LEGACY_PREPLANS_PRE_0_5 = "goal_shared_preplans_pre_0_5"

private const val LEGACY_PLANS_PRE_0_5 = "goal_subtask_plans_pre_0_5"

private const val LEGACY_PREPLANS_PRE_0_6 = "goal_shared_preplans_pre_0_6"

private const val LEGACY_PLANS_PRE_0_6 = "goal_subtask_plans_pre_0_6"

private const val LEGACY_PREPLANS = "goal_shared_preplans_pre_0_2"

private const val LEGACY_PLANS = "goal_subtask_plans_pre_0_2"

private const val STRICT_LEGACY_PREPLANS = "goal_shared_preplans_pre_strict_0_2"

private const val STRICT_LEGACY_PLANS = "goal_subtask_plans_pre_strict_0_2"

private const val ORDERED_SUBTASK_PLANS_INDEX =
  "CREATE INDEX IF NOT EXISTS idx_goal_subtask_plans_ordered " +
    "ON goal_subtask_plans(parent_goal_workflow_id, manifest_order)"

private fun Connection.sharedPreplansDdl(legacyTable: String, phaseOutputCheck: String): String = """
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
    phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version $phaseOutputCheck),
    payload_sha256 TEXT NOT NULL,
    preplan_payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${optionalRepairEvidenceColumn(legacyTable)},
    UNIQUE(normalized_issue_key, repository_identity)
  )
""".trimIndent()

private fun Connection.subtaskPlansDdl(legacyTable: String, phaseOutputCheck: String): String = """
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
    phase_output_contract_version TEXT NOT NULL CHECK (phase_output_contract_version $phaseOutputCheck),
    payload_sha256 TEXT NOT NULL,
    plan_payload_json TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP${optionalRepairEvidenceColumn(legacyTable)},
    PRIMARY KEY(parent_goal_workflow_id, subtask_id),
    UNIQUE(parent_goal_workflow_id, governed_sub_spec_path),
    UNIQUE(parent_goal_workflow_id, manifest_order),
    FOREIGN KEY(parent_goal_workflow_id) REFERENCES goal_shared_preplans(parent_goal_workflow_id) ON DELETE CASCADE
  )
""".trimIndent()

/**
 * Widens a rebuilt planning table with `repair_evidence_json` only when the table being replaced already
 * carried it, so a rebuild never silently drops recorded repair evidence and never invents the column on
 * databases that predate it.
 */
internal fun Connection.optionalRepairEvidenceColumn(table: String): String {
  val hasRepairEvidence = prepareStatement(
    "SELECT 1 FROM pragma_table_info(?) WHERE name = 'repair_evidence_json'",
  ).use { statement ->
    statement.setString(1, table)
    statement.executeQuery().use { rows -> rows.next() }
  }
  return if (hasRepairEvidence) ",\n                repair_evidence_json TEXT" else ""
}
