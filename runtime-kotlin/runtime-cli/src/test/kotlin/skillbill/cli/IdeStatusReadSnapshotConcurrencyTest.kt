package skillbill.cli

import skillbill.application.model.IdeStatusProblemCode
import skillbill.application.model.IdeStatusRequest
import skillbill.application.model.IdeStatusResult
import skillbill.application.work.IdeStatusProjector
import skillbill.application.work.IdeStatusService
import skillbill.db.core.DatabaseRuntime
import skillbill.di.RuntimeComponent
import skillbill.di.create
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.model.RuntimeContext
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.persistence.GoalRunnerControlRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.WorkListRepository
import skillbill.ports.persistence.WorkflowStateRepository
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity
import skillbill.ports.persistence.model.FeatureTaskRouteScope
import skillbill.ports.persistence.model.FeatureTaskWorkflowMode
import skillbill.ports.persistence.model.WorkItem
import skillbill.ports.persistence.model.WorkflowStateRecord
import skillbill.ports.system.CheckedOutBranchSource
import skillbill.workflow.NoopIdeStatusValidator
import skillbill.workflow.WorkflowSnapshotValidator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKILL-168 subtask 2, AC-003: a writer commit landing anywhere inside `IdeStatusService`'s candidate
 * collection must not hide a live, correctly-bound, durably running goal. The interleave is driven by
 * an instrumented `UnitOfWork` that fires the commit after the Nth collection statement, and the
 * assertion is made against the mechanism — every interleave point — rather than one statement pair.
 */
class IdeStatusReadSnapshotConcurrencyTest {
  private val observedAt: Instant = Instant.parse("2026-08-06T12:00:00Z")

  @Test
  fun `a writer commit landing anywhere during collection never hides a live running goal`() {
    (1..INTERLEAVE_POINTS).forEach { interleaveAfterCall ->
      val fixture = fixture("ide-status-snapshot-$interleaveAfterCall")

      val result = fixture.status(interleaveAfterCall)

      assertTrue(
        fixture.interleaved,
        "Interleave point $interleaveAfterCall never fired; the collection issued fewer statements.",
      )
      assertNull(
        result.snapshot.problem?.code,
        "Interleave point $interleaveAfterCall reported ${result.snapshot.problem?.code} for a live goal.",
      )
      assertEquals(GOAL_WORKFLOW_ID, result.snapshot.workflowId)
      assertEquals(0, result.exitCode)
    }
  }

  @Test
  fun `the interleaved mutation is outcome-changing when it lands before the snapshot opens`() {
    // Without this control the snapshot test would pass even if the mutation were inert.
    val fixture = fixture("ide-status-snapshot-control")
    fixture.clearGoalBinding()

    val result = fixture.status(interleaveAfterCall = null)

    assertEquals(IdeStatusProblemCode.NO_MATCHING_WORK, result.snapshot.problem?.code)
    assertEquals(0, result.exitCode)
  }

  private fun fixture(prefix: String): SnapshotFixture {
    val home = Files.createTempDirectory("$prefix-home")
    val repoRoot = Files.createTempDirectory("$prefix-repo")
    Files.createDirectory(repoRoot.resolve(".git"))
    Files.writeString(repoRoot.resolve(".git").resolve("HEAD"), "ref: refs/heads/feat/$ISSUE_KEY-snapshot\n")
    val dbPath = home.resolve("metrics.db")
    val database = SQLiteDatabaseSessionFactory(EnvironmentContext(userHome = home))
    val identity = "$REPOSITORY_IDENTITY_PREFIX${repoRoot.toRealPath()}"
    seed(dbPath, database, identity)
    return SnapshotFixture(home, repoRoot, dbPath, database, observedAt)
  }

  private fun seed(dbPath: Path, database: SQLiteDatabaseSessionFactory, repositoryIdentity: String) {
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      connection.prepareStatement(
        """
        INSERT INTO goal_issue_progress (
          parent_workflow_id, issue_key, first_started_at, status, state_entered_at, state_entered_at_estimated
        ) VALUES (?, ?, ?, 'running', ?, 0)
        """.trimIndent(),
      ).use { statement ->
        statement.setString(1, GOAL_WORKFLOW_ID)
        statement.setString(2, ISSUE_KEY)
        statement.setString(3, "2026-08-06T08:00:00Z")
        statement.setString(4, "2026-08-06T11:00:00Z")
        statement.executeUpdate()
      }
    }
    database.transaction(dbPath.toString()) { unitOfWork ->
      // A goal child bound elsewhere makes an unbound goal resolve as "belongs to another repository",
      // so a torn read of the goal binding drops the candidate instead of silently passing.
      unitOfWork.workflowStates.saveFeatureTaskRuntimeWorkflow(foreignChildWorkflow())
      unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(foreignChildIdentity())
      unitOfWork.goalRunnerControls.persistControlState(
        GOAL_WORKFLOW_ID,
        GoalRunnerControlState(repositoryIdentity = repositoryIdentity),
      )
    }
  }

  private fun foreignChildWorkflow(): WorkflowStateRecord = WorkflowStateRecord(
    workflowId = FOREIGN_CHILD_WORKFLOW_ID,
    sessionId = "ftr-$FOREIGN_CHILD_WORKFLOW_ID",
    workflowName = "bill-feature-task",
    contractVersion = "1.0",
    workflowStatus = "running",
    currentStepId = "implement",
    stepsJson = "[]",
    artifactsJson = "{}",
    startedAt = "2026-08-06T09:00:00Z",
    updatedAt = "2026-08-06T09:00:00Z",
    finishedAt = null,
    issueKey = ISSUE_KEY,
    mode = FeatureTaskWorkflowMode.RUNTIME,
  )

  private fun foreignChildIdentity(): FeatureTaskExecutionIdentity = FeatureTaskExecutionIdentity(
    workflowId = FOREIGN_CHILD_WORKFLOW_ID,
    normalizedIssueKey = ISSUE_KEY,
    repositoryIdentity = "${REPOSITORY_IDENTITY_PREFIX}/other-repo",
    governedSpecPath = "spec.md",
    mode = FeatureTaskWorkflowMode.RUNTIME,
    routeScope = FeatureTaskRouteScope.GOAL_CHILD,
  )

  private companion object {
    const val ISSUE_KEY = "SKILL-999"
    const val GOAL_WORKFLOW_ID = "goal-snapshot"
    const val FOREIGN_CHILD_WORKFLOW_ID = "wfl-foreign-child"

    // Mirrors goalRepositoryIdentity, which is internal to runtime-application.
    const val REPOSITORY_IDENTITY_PREFIX = "repo-root-realpath-v1:"

    // Candidate collection issues more statements than this; covering the first N interleave points
    // asserts the mechanism rather than the one torn pair that produced the observed report.
    const val INTERLEAVE_POINTS = 5
  }
}

private class SnapshotFixture(
  private val home: Path,
  private val repoRoot: Path,
  private val dbPath: Path,
  private val database: SQLiteDatabaseSessionFactory,
  private val observedAt: Instant,
) {
  var interleaved: Boolean = false
    private set

  fun clearGoalBinding() {
    database.transaction(dbPath.toString()) { unitOfWork ->
      unitOfWork.goalRunnerControls.persistControlState("goal-snapshot", GoalRunnerControlState())
    }
  }

  fun status(interleaveAfterCall: Int?): IdeStatusResult {
    val instrumented = InterleavingDatabase(database, interleaveAfterCall) {
      interleaved = true
      // A separate connection commits mid-collection, exactly as the goal runtime does.
      clearGoalBinding()
    }
    return service(instrumented).status(
      IdeStatusRequest(repoRoot = repoRoot.toString(), dbOverride = dbPath.toString(), observedAt = observedAt),
    )
  }

  private fun service(database: DatabaseSessionFactory): IdeStatusService {
    val component = RuntimeComponent::class.create(RuntimeContext(environment = emptyMap(), userHome = home))
    return IdeStatusService(
      database = database,
      projector = IdeStatusProjector(
        workflowSnapshotValidator = NoopSnapshotValidator,
        goalRunnerStatusService = component.goalRunnerStatusService,
        featureTaskRuntimeStatusService = component.featureTaskRuntimeStatusService,
      ),
      ideStatusValidator = NoopIdeStatusValidator,
      branchSource = CheckedOutBranchSource { "feat/SKILL-999-snapshot" },
      clock = Clock.fixed(observedAt, ZoneOffset.UTC),
    )
  }
}

private object NoopSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(snapshot: Map<String, Any?>, slug: String) = Unit
}

/**
 * Delegates every session to the real SQLite factory and counts the collection statements issued inside
 * one read block, firing [onInterleave] once after the configured statement so the writer commit lands
 * at a chosen point mid-collection.
 */
private class InterleavingDatabase(
  private val delegate: DatabaseSessionFactory,
  private val interleaveAfterCall: Int?,
  private val onInterleave: () -> Unit,
) : DatabaseSessionFactory by delegate {
  override fun <T> read(dbOverride: String?, block: (UnitOfWork) -> T): T {
    var calls = 0
    val trigger: () -> Unit = {
      calls += 1
      if (calls == interleaveAfterCall) onInterleave()
    }
    return delegate.read(dbOverride) { unitOfWork -> block(InterleavingUnitOfWork(unitOfWork, trigger)) }
  }
}

private class InterleavingUnitOfWork(
  private val delegate: UnitOfWork,
  private val trigger: () -> Unit,
) : UnitOfWork by delegate {
  override val workList: WorkListRepository = CountingWorkList(delegate.workList, trigger)
  override val workflowStates: WorkflowStateRepository = CountingWorkflowStates(delegate.workflowStates, trigger)
  override val goalRunnerControls: GoalRunnerControlRepository =
    CountingGoalRunnerControls(delegate.goalRunnerControls, trigger)
}

private class CountingWorkList(
  private val delegate: WorkListRepository,
  private val trigger: () -> Unit,
) : WorkListRepository {
  override fun list(limit: Int?): List<WorkItem> = delegate.list(limit).also { trigger() }
}

private class CountingWorkflowStates(
  private val delegate: WorkflowStateRepository,
  private val trigger: () -> Unit,
) : WorkflowStateRepository by delegate {
  override fun getFeatureTaskExecutionIdentity(workflowId: String): FeatureTaskExecutionIdentity? =
    delegate.getFeatureTaskExecutionIdentity(workflowId).also { trigger() }

  override fun findGoalChildFeatureTaskCandidates(normalizedIssueKey: String, repositoryIdentity: String) =
    delegate.findGoalChildFeatureTaskCandidates(normalizedIssueKey, repositoryIdentity).also { trigger() }

  override fun countGoalChildIdentities(normalizedIssueKey: String): Int =
    delegate.countGoalChildIdentities(normalizedIssueKey).also { trigger() }
}

private class CountingGoalRunnerControls(
  private val delegate: GoalRunnerControlRepository,
  private val trigger: () -> Unit,
) : GoalRunnerControlRepository by delegate {
  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    delegate.controlState(parentWorkflowId).also { trigger() }
}
