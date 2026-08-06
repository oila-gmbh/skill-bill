package skillbill.db.core

import skillbill.review.EXECUTION_MODE_DELEGATED
import skillbill.review.UNRESOLVED_ATTRIBUTION
import skillbill.review.canonicalPackSkillNames
import skillbill.review.canonicalPlatformSlugs
import skillbill.review.resolveCanonicalRoutedSkill
import skillbill.review.resolveCanonicalScope
import skillbill.review.resolveCanonicalStack
import java.sql.Connection

/**
 * SKILL-136 subtask 4 AC-004/AC-005/AC-006: backfills the canonical review-run attribution columns.
 *
 * The canonical backfill is a one-shot migration run through the numbered ledger rather than on every
 * open. It is unambiguous-only: rows whose retained raw text does not resolve keep the explicit
 * 'unresolved' marker, the raw columns are never written, and a canonical value already computed at
 * ingestion is never overwritten (only columns still holding the marker are touched). Rows whose
 * recomputed values equal the stored ones are skipped, so re-running writes nothing.
 */
internal object ReviewAttributionBackfillMigration {
  private const val BIND_ROUTED_SKILL = 1
  private const val BIND_STACK = 2
  private const val BIND_SCOPE = 3
  private const val BIND_SCOPE_DETAIL = 4
  private const val BIND_REVIEW_RUN_ID = 5

  // The raw attribution columns predate this feature but are absent from the oldest stores. There is
  // nothing to canonicalize without them, so the migration is a no-op rather than a failed open.
  private val requiredRawColumns = setOf("routed_skill", "detected_stack", "detected_scope")

  fun apply(connection: Connection) {
    if (!DatabaseColumnMigrations.reviewRunsTableExists(connection)) return
    if (!DatabaseColumnMigrations.reviewRunColumnNames(connection).containsAll(requiredRawColumns)) return
    DatabaseColumnMigrations.ensureReviewRunColumns(connection)
    backfillCanonicals(connection)
  }

  // execution_mode is only inferred from the run's own evidence: recorded specialist reviews prove a
  // delegated run. Everything else is explicitly unresolved rather than defaulted to inline.
  fun backfillExecutionModes(connection: Connection) {
    connection.createStatement().use { statement ->
      statement.execute(
        """
        UPDATE review_runs
        SET execution_mode = '$EXECUTION_MODE_DELEGATED'
        WHERE (execution_mode IS NULL OR execution_mode = '')
          AND specialist_reviews IS NOT NULL AND specialist_reviews != ''
        """.trimIndent(),
      )
      statement.execute(
        """
        UPDATE review_runs
        SET execution_mode = '$UNRESOLVED_ATTRIBUTION'
        WHERE execution_mode IS NULL OR execution_mode = ''
        """.trimIndent(),
      )
    }
  }

  private fun backfillCanonicals(connection: Connection) {
    val pending = pendingRows(connection)
    if (pending.isEmpty()) return
    connection.prepareStatement(
      """
      UPDATE review_runs
      SET routed_skill_canonical = ?,
          detected_stack_canonical = ?,
          detected_scope_canonical = ?,
          detected_scope_detail = ?
      WHERE review_run_id = ?
      """.trimIndent(),
    ).use { statement ->
      var batched = 0
      pending.forEach { row ->
        val resolved = row.resolve()
        if (resolved == row.stored) return@forEach
        statement.setString(BIND_ROUTED_SKILL, resolved.routedSkill)
        statement.setString(BIND_STACK, resolved.stack)
        statement.setString(BIND_SCOPE, resolved.scope)
        statement.setString(BIND_SCOPE_DETAIL, resolved.scopeDetail)
        statement.setString(BIND_REVIEW_RUN_ID, row.reviewRunId)
        statement.addBatch()
        batched += 1
      }
      if (batched > 0) statement.executeBatch()
    }
  }

  private fun pendingRows(connection: Connection): List<PendingRow> = connection.prepareStatement(
    """
      SELECT review_run_id, routed_skill, detected_stack, detected_scope,
             routed_skill_canonical, detected_stack_canonical, detected_scope_canonical,
             detected_scope_detail
      FROM review_runs
      WHERE routed_skill_canonical = '$UNRESOLVED_ATTRIBUTION'
         OR detected_stack_canonical = '$UNRESOLVED_ATTRIBUTION'
         OR detected_scope_canonical = '$UNRESOLVED_ATTRIBUTION'
    """.trimIndent(),
  ).use { statement ->
    statement.executeQuery().use { rows ->
      buildList {
        while (rows.next()) {
          add(
            PendingRow(
              reviewRunId = rows.getString("review_run_id"),
              raw = RawAttribution(
                routedSkill = rows.getString("routed_skill"),
                stack = rows.getString("detected_stack"),
                scope = rows.getString("detected_scope"),
              ),
              stored = CanonicalAttributionColumns(
                routedSkill = rows.getString("routed_skill_canonical"),
                stack = rows.getString("detected_stack_canonical"),
                scope = rows.getString("detected_scope_canonical"),
                scopeDetail = rows.getString("detected_scope_detail"),
              ),
            ),
          )
        }
      }
    }
  }

  private class RawAttribution(val routedSkill: String?, val stack: String?, val scope: String?)

  private data class CanonicalAttributionColumns(
    val routedSkill: String?,
    val stack: String?,
    val scope: String?,
    val scopeDetail: String?,
  )

  private class PendingRow(
    val reviewRunId: String,
    val raw: RawAttribution,
    val stored: CanonicalAttributionColumns,
  ) {
    fun resolve(): CanonicalAttributionColumns {
      val scope = resolveCanonicalScope(raw.scope)
      val scopeWasUnresolved = stored.scope == UNRESOLVED_ATTRIBUTION
      return CanonicalAttributionColumns(
        routedSkill = stored.routedSkill.takeUnless { it == UNRESOLVED_ATTRIBUTION }
          ?: resolveCanonicalRoutedSkill(raw.routedSkill, canonicalPackSkillNames).canonical,
        stack = stored.stack.takeUnless { it == UNRESOLVED_ATTRIBUTION }
          ?: resolveCanonicalStack(raw.stack, canonicalPlatformSlugs).canonical,
        scope = if (scopeWasUnresolved) scope.canonical else stored.scope,
        scopeDetail = if (scopeWasUnresolved) scope.detail else stored.scopeDetail,
      )
    }
  }
}
