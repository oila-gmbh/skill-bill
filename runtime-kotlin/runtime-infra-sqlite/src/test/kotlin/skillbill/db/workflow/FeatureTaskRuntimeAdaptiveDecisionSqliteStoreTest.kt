package skillbill.db.workflow

import org.junit.jupiter.api.Test
import skillbill.db.core.DatabaseRuntime
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveDecisionRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeAdaptiveReviewPolicy
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeComplexitySignals
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityCategory
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityDisposition
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFocusedQualityOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairBatch
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityRepairItem
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSizingPolicyResolver
import java.nio.file.Files
import kotlin.test.assertEquals

class FeatureTaskRuntimeAdaptiveDecisionSqliteStoreTest {
  @Test
  fun `retry keeps one stable repair batch and preserves adaptive decision`() {
    val db = DatabaseRuntime.ensureDatabase(Files.createTempDirectory("adaptive-store").resolve("runtime.db"))
    db.use {
      val store = FeatureTaskRuntimeAdaptiveDecisionSqliteStore(it)
      val signals = FeatureTaskRuntimeComplexitySignals(3, 2, 4, 4, true, false, true, true, 1, 18)
      val sizing = FeatureTaskRuntimeSizingPolicyResolver.resolve(signals)
      val batch = FeatureTaskRuntimeQualityRepairBatch(
        "decision-1:semantic-1:1",
        "semantic-1",
        1,
        listOf(
          FeatureTaskRuntimeQualityRepairItem(
            "repair-1",
            "kotlin:compilation",
            FeatureTaskRuntimeFocusedQualityCategory.COMPILATION,
            "Compilation failed in an owned module.",
          ),
        ),
      )
      val record = FeatureTaskRuntimeAdaptiveDecisionRecord(
        "decision-1",
        sizing,
        null,
        FeatureTaskRuntimeAdaptiveReviewPolicy.resolve(sizing, signals, CodeReviewExecutionMode.INLINE),
        FeatureTaskRuntimeFocusedQualityOutcome(
          FeatureTaskRuntimeFocusedQualityDisposition.REPAIR_REQUIRED,
          null,
          batch,
        ),
      )

      store.persistAndAdvance(record, "implement")
      store.persistAndAdvance(record, "implement")

      assertEquals(record, store.read(record.decisionId))
      val count = it.createStatement().use { statement ->
        val query = "SELECT COUNT(*) AS count FROM feature_task_runtime_quality_repair_batches"
        statement.executeQuery(query).use { rows ->
          rows.next()
          rows.getInt("count")
        }
      }
      assertEquals(1, count)
    }
  }
}
