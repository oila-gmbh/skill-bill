package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.error.FeatureTaskRuntimeSubtaskCommitReconciliationError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FeatureTaskRuntimeReviewInputFailureTest {
  @Test
  fun `review input database failure blocks with typed refusal and retains its cause`() {
    val failure = IllegalStateException("database unavailable")
    val database = object : DatabaseSessionFactory by RuntimeFakeDatabaseSessionFactory(
      InMemoryRuntimeWorkflowRepository(),
    ) {
      override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T = throw failure
    }
    val records = mutableListOf<String>()
    val diagnostics = object : RuntimeDiagnostics {
      override fun warning(message: String, error: Throwable?) {
        records += message
      }
      override fun error(message: String, error: Throwable?) {
        records += message
      }
    }
    val recorder = FeatureTaskRuntimeGoalContinuationRecorder(
      database,
      testWorkflowSnapshotValidator,
      diagnostics,
      Clock.systemUTC(),
    )
    val result = assertFailsWith<FeatureTaskRuntimeSubtaskCommitReconciliationError> {
      recorder.buildGoalReviewInput("wftr-test", NoopWorkflowGitOperations, Path.of("unused"))
    }
    assertSame(failure, result.cause)
    assertEquals(1, records.size)
    assertContains(records.single(), "record_kind=refusal")
    assertContains(result.message.orEmpty(), "database unavailable")
  }
}
