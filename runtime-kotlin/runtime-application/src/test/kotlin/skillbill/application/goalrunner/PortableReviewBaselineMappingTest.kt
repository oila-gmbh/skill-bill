package skillbill.application.goalrunner

import skillbill.agentaddon.model.AgentAddonSelection
import skillbill.application.InMemoryGoalManifestStore
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.RecordingOutcomeStore
import skillbill.application.TestRepositoryEnclosingRoot
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementRequest
import skillbill.application.goalrunner.model.GoalChildOrphanReplacementResult
import skillbill.application.goalrunner.model.GoalRunnerRunRequest
import skillbill.application.goalrunner.model.GoalRunnerSubtaskLaunchBoundariesPort
import skillbill.application.goalrunner.model.GoalRunnerWedgeClass
import skillbill.application.goalrunner.model.PortableReviewBaselineValidation
import skillbill.application.goalrunner.model.PortableReviewBaselineValidationRequest
import skillbill.application.goalrunner.planning.model.GoalPlanningSweepOutcome
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.model.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.error.InvalidPortableReviewBaselineSchemaError
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.persistence.PortableReviewBaselinePersistence
import skillbill.ports.goalrunner.persistence.model.GoalRunnerChildRepairDiagnoseRequest
import skillbill.ports.goalrunner.persistence.model.PortableReviewBaselineRepairContext
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerChildWorkflowSetup
import skillbill.ports.goalrunner.runner.model.GoalRunnerManifestState
import skillbill.ports.goalrunner.runner.model.GoalRunnerOrphanChildReplacementWrite
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.goal.model.PortableReviewBaseline
import skillbill.workflow.goal.model.PortableReviewBaselineBlockedReason
import skillbill.workflow.goal.model.PortableReviewBaselineCodec
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PortableReviewBaselineMappingTest {
  private val reviewBaseSha = "a".repeat(40)
  private val headSha = "b".repeat(40)
  private val issueKey = "SKILL-234"
  private val goalBranch = "feat/$issueKey-portable"
  private val repositoryIdentity = "repo-root-realpath-v1:/tmp/repo"

  @Test
  fun `round trip preserves baseline fields and digest`() {
    val baseline = GoalSubtaskReviewBaseline(reviewBaseSha, listOf("notes.tmp"), listOf("src/Foo.kt"))
    val artifact = PortableReviewBaselineMapping.fromReviewBaseline(
      workflowId = "wftr-test",
      repositoryIdentity = repositoryIdentity,
      goalBranch = goalBranch,
      reviewBaseline = baseline,
    )
    val encoded = PortableReviewBaselineCodec.encode(artifact)
    val decoded = PortableReviewBaselineCodec.decode(encoded)
    assertEquals(artifact, decoded)
    assertEquals(baseline, PortableReviewBaselineMapping.toReviewBaseline(decoded))
  }

  @Test
  fun `digest mismatch is rejected`() {
    val encoded = PortableReviewBaselineCodec.encode(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    ).toMutableMap()
    encoded["integrity_digest"] = "0".repeat(64)
    assertFailsWith<InvalidPortableReviewBaselineSchemaError> {
      PortableReviewBaselineCodec.decode(encoded)
    }
  }

  @Test
  fun `cross-platform unsafe baseline paths are rejected`() {
    val encoded = PortableReviewBaselineCodec.encode(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    ).toMutableMap()
    encoded["baseline_untracked_paths"] = listOf("""C:\\work\\baseline.tmp""", "src/./Generated.kt")
    encoded["integrity_digest"] = PortableReviewBaselineCodec.digest(
      encoded.filterKeys { it != "integrity_digest" },
    )

    assertFailsWith<InvalidPortableReviewBaselineSchemaError> {
      PortableReviewBaselineCodec.decode(encoded)
    }
  }

  @Test
  fun `unsafe baseline path is rejected`() {
    val body = PortableReviewBaselineCodec.encode(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, listOf("../escape")),
      ),
    )
    assertFailsWith<InvalidPortableReviewBaselineSchemaError> {
      PortableReviewBaselineCodec.decode(body)
    }
  }

  @Test
  fun `validator blocks workflow identity mismatch`() {
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-stale",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val validation = PortableReviewBaselineValidator.validateArtifactIntegrity(
      validationRequest(persistence, expectedWorkflowId = "wftr-current"),
    )
    val blocked = assertIs<PortableReviewBaselineValidation.Blocked>(validation)
    assertEquals(PortableReviewBaselineBlockedReason.ARTIFACT_MALFORMED, blocked.reason)
  }

  @Test
  fun `validator blocks repository mismatch`() {
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = "repo-root-realpath-v1:/other-repo",
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val validation = PortableReviewBaselineValidator.validateArtifactIntegrity(
      validationRequest(persistence),
    )
    val blocked = assertIs<PortableReviewBaselineValidation.Blocked>(validation)
    assertEquals(PortableReviewBaselineBlockedReason.REPOSITORY_MISMATCH, blocked.reason)
  }

  @Test
  fun `validator blocks branch mismatch`() {
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = "feat/SKILL-234-other",
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val validation = PortableReviewBaselineValidator.validateArtifactIntegrity(
      validationRequest(persistence),
    )
    val blocked = assertIs<PortableReviewBaselineValidation.Blocked>(validation)
    assertEquals(PortableReviewBaselineBlockedReason.BRANCH_MISMATCH, blocked.reason)
  }

  @Test
  fun `validator blocks implementation evidence`() {
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val validation = PortableReviewBaselineValidator.validateStoredArtifact(
      validationRequest(
        persistence,
        subtask = orphanSubtask().copy(commitSha = "c".repeat(40)),
      ),
    )
    val blocked = assertIs<PortableReviewBaselineValidation.Blocked>(validation)
    assertEquals(PortableReviewBaselineBlockedReason.IMPLEMENTATION_EVIDENCE, blocked.reason)
  }

  @Test
  fun `validator blocks branch evidence after the portable baseline`() {
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val validation = PortableReviewBaselineValidator.validateStoredArtifact(
      validationRequest(
        persistence,
        subtask = orphanSubtask(),
      ),
    )

    val blocked = assertIs<PortableReviewBaselineValidation.Blocked>(validation)
    assertEquals(PortableReviewBaselineBlockedReason.BRANCH_EVIDENCE, blocked.reason)
  }

  @Test
  fun `validator accepts a prepared plan at the portable baseline`() {
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-test",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val validation = PortableReviewBaselineValidator.validateStoredArtifact(
      validationRequest(
        persistence,
        subtask = orphanSubtask().copy(lastResumableStep = "plan"),
        gitOperations = preImplementationGit(),
      ),
    )

    assertIs<PortableReviewBaselineValidation.Valid>(validation)
  }

  @Test
  fun `missing artifact still blocks a child with implementation evidence`() {
    val store = InMemoryGoalManifestStore(
      manifestState().manifest.copy(
        subtasks = listOf(orphanSubtask().copy(lastResumableStep = "implement")),
      ),
    )
    val outcomes = RecordingOutcomeStore()
    val prepare = GoalRunnerSubtaskLaunchPrepare(
      launchBoundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
        override val manifestStore = store
        override val outcomeStore = outcomes
        override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
        override val gitOperations = captureBaselineGit()
      },
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = InMemoryPortablePersistence(null),
    )
    val request = GoalRunnerRunRequest(
      issueKey = issueKey,
      repoRoot = Path.of("/tmp/repo"),
      invokedAgentId = "cursor",
    )
    val state = requireNotNull(store.loadByIssueKey(issueKey, null, request.repoRoot))

    val result = prepare.goalReviewBaseline(state, 1, request)

    assertFalse(result.ok)
    assertContains(result.error, PortableReviewBaselineBlockedReason.ARTIFACT_MISSING.wireValue)
  }

  @Test
  fun `missing portable artifact is diagnosed as invalid portable review baseline`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskRuntimeWorkflow(
      portableDiagnosisRecord("wftr-test", continuationArtifact()),
    )
    val diagnosis = GoalRunnerChildRepairWedgeDiagnosis(
      gitOperations = reachableGit(),
      portableReviewBaselinePersistence = InMemoryPortablePersistence(null),
    ).diagnose(
      GoalRunnerChildRepairDiagnoseRequest(
        workflowStates = workflows,
        workflowId = "wftr-test",
        issueKey = issueKey,
        subtaskId = 1,
        repoRoot = Path.of("/tmp/repo"),
        portableContext = portableContext(),
      ),
    )
    val wedge = diagnosis.wedges.single()
    assertEquals(GoalRunnerWedgeClass.INVALID_PORTABLE_REVIEW_BASELINE, wedge.wedgeClass)
    assertEquals(PortableReviewBaselineBlockedReason.ARTIFACT_MISSING.wireValue, wedge.currentValue)
    assertFalse(diagnosis.passedChecks.contains(PASSED_REVIEW_BASE))
  }

  @Test
  fun `malformed portable artifact keeps its typed blocked reason`() {
    val store = InMemoryGoalManifestStore(manifestState().manifest)
    val prepare = GoalRunnerSubtaskLaunchPrepare(
      launchBoundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
        override val manifestStore = store
        override val outcomeStore = RecordingOutcomeStore()
        override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
        override val gitOperations = preImplementationGit()
      },
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = ThrowingPortablePersistence("invalid yaml shape"),
    )
    val result = prepare.goalReviewBaseline(
      requireNotNull(store.loadByIssueKey(issueKey, null, Path.of("/tmp/repo"))),
      1,
      goalRunnerRequest(),
    )

    assertFalse(result.ok)
    assertContains(result.error, PortableReviewBaselineBlockedReason.ARTIFACT_MALFORMED.wireValue)
  }

  @Test
  fun `digest-invalid portable artifact keeps its typed blocked reason`() {
    val store = InMemoryGoalManifestStore(manifestState().manifest)
    val prepare = GoalRunnerSubtaskLaunchPrepare(
      launchBoundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
        override val manifestStore = store
        override val outcomeStore = RecordingOutcomeStore()
        override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
        override val gitOperations = preImplementationGit()
      },
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = ThrowingPortablePersistence("integrity_digest does not match"),
    )
    val result = prepare.goalReviewBaseline(
      requireNotNull(store.loadByIssueKey(issueKey, null, Path.of("/tmp/repo"))),
      1,
      goalRunnerRequest(),
    )

    assertFalse(result.ok)
    assertContains(result.error, PortableReviewBaselineBlockedReason.DIGEST_MISMATCH.wireValue)
  }

  @Test
  fun `valid portable artifact rehydrates missing local child state`() {
    val captures = mutableListOf<Unit>()
    val store = InMemoryGoalManifestStore(manifestState().manifest)
    val persistence = InMemoryPortablePersistence(
      PortableReviewBaselineMapping.fromReviewBaseline(
        workflowId = "wftr-source",
        repositoryIdentity = repositoryIdentity,
        goalBranch = goalBranch,
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
      ),
    )
    val prepare = GoalRunnerSubtaskLaunchPrepare(
      launchBoundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
        override val manifestStore = store
        override val outcomeStore = RecordingOutcomeStore()
        override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
        override val gitOperations = captureBaselineGit(captures, reviewBaseSha)
      },
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = persistence,
    )

    val result = prepare.goalReviewBaseline(
      requireNotNull(store.loadByIssueKey(issueKey, null, Path.of("/tmp/repo"))),
      1,
      goalRunnerRequest(),
    )

    assertTrue(result.ok)
    assertEquals(GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()), result.baseline)
    assertTrue(captures.isEmpty())
  }

  @Test
  fun `existing local review state is reused without reading portable state`() {
    val store = InMemoryGoalManifestStore(manifestState().manifest)
    val outcomes = RecordingOutcomeStore().apply { seedReviewState("wftr-source") }
    val prepare = GoalRunnerSubtaskLaunchPrepare(
      launchBoundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
        override val manifestStore = store
        override val outcomeStore = outcomes
        override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
        override val gitOperations = preImplementationGit()
      },
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = ThrowingPortablePersistence("portable state must not be read"),
    )

    val result = prepare.goalReviewBaseline(
      requireNotNull(store.loadByIssueKey(issueKey, null, Path.of("/tmp/repo"))),
      1,
      goalRunnerRequest(),
    )

    assertTrue(result.ok)
    assertEquals("0".repeat(40), result.baseline?.reviewBaseSha)
  }

  @Test
  fun `missing portable artifact blocks missing local child without recapturing`() {
    val captures = mutableListOf<Unit>()
    val store = InMemoryGoalManifestStore(
      manifestState().manifest.copy(
        subtasks = listOf(orphanSubtask().copy(lastResumableStep = "plan")),
      ),
    )
    val prepare = GoalRunnerSubtaskLaunchPrepare(
      launchBoundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
        override val manifestStore = store
        override val outcomeStore = RecordingOutcomeStore()
        override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
        override val gitOperations = captureBaselineGit(captures, reviewBaseSha)
      },
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = InMemoryPortablePersistence(null),
    )

    val result = prepare.goalReviewBaseline(
      requireNotNull(store.loadByIssueKey(issueKey, null, Path.of("/tmp/repo"))),
      1,
      goalRunnerRequest(),
    )

    assertFalse(result.ok)
    assertContains(result.error, PortableReviewBaselineBlockedReason.ARTIFACT_MISSING.wireValue)
    assertTrue(captures.isEmpty())
    assertTrue(store.newChildWorkflowSetups.isEmpty())
  }

  @Test
  fun `orphan replacement writes baseline before manifest transaction`() {
    val events = mutableListOf<String>()
    val store = InMemoryGoalManifestStore(
      manifest = manifestState().manifest,
      projectionSaved = { events += "manifest" },
    )
    val persistence = RecordingPortablePersistence(events)
    val boundaries = object : GoalRunnerSubtaskLaunchBoundariesPort {
      override val manifestStore = store
      override val outcomeStore = RecordingOutcomeStore()
      override val subtaskLauncher = GoalRunnerSubtaskLauncher { error("unused") }
      override val gitOperations = captureBaselineGit()
    }
    val prepared = GoalRunnerCrossMachineChildRecovery(
      launchBoundaries = boundaries,
      repositoryEnclosingRootPort = TestRepositoryEnclosingRoot,
      portableReviewBaselinePersistence = persistence,
    ).persist(
      GoalRunnerCrossMachineChildRecovery.PersistArgs(
        state = requireNotNull(store.loadByIssueKey(issueKey, null, Path.of("/tmp/repo"))),
        subtaskId = 1,
        request = goalRunnerRequest(),
        reviewBaseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
        planning = planningForRecovery(),
        governedSpecPath = ".feature-specs/$issueKey/spec_subtask_1.md",
        sourceWorkflowId = "wftr-source",
      ),
    )

    assertEquals(listOf("baseline", "manifest"), events)
    assertEquals(prepared.openWithAssignedId, persistence.writes.single().workflowId)
  }

  @Test
  fun `orphan manifest replacement retires source child and stores audit`() {
    val manifestStore = RecordingOrphanManifestStore()
    val replacement = GoalChildOrphanReplacement.replaceOrphan(
      GoalChildOrphanReplacementRequest(
        state = manifestState(),
        subtaskId = 1,
        repoRoot = Path.of("/tmp/repo"),
        repositoryIdentity = repositoryIdentity,
        gitOperations = captureBaselineGit(),
        codeReviewMode = CodeReviewExecutionMode.DEFAULT,
      ),
    )
    val replaced = assertIs<GoalChildOrphanReplacementResult.Replaced>(replacement)
    manifestStore.replaceOrphanChildWorkflow(
      GoalRunnerOrphanChildReplacementWrite(
        state = replaced.state,
        subtaskId = 1,
        sourceWorkflowId = replaced.sourceWorkflowId,
        setup = GoalChildOrphanReplacement.childWorkflowSetup(
          GoalChildOrphanReplacementRequest(
            state = manifestState(),
            subtaskId = 1,
            repoRoot = Path.of("/tmp/repo"),
            repositoryIdentity = repositoryIdentity,
            gitOperations = captureBaselineGit(),
            codeReviewMode = CodeReviewExecutionMode.DEFAULT,
          ),
          replaced,
          governedSpecPath = ".feature-specs/$issueKey/spec_subtask_1.md",
          reviewPolicy = GoalRunnerReviewPolicy(
            codeReviewMode = CodeReviewExecutionMode.DEFAULT,
            agentAddonSelection = AgentAddonSelection(),
          ),
        ),
        auditEntry = replaced.auditEntry,
      ),
    )
    assertEquals("wftr-source", manifestStore.retiredSourceWorkflowId)
    assertEquals("orphan_replacement", manifestStore.storedAudit?.get("recovery_reason"))
    assertEquals(replaced.replacementWorkflowId, manifestStore.storedSetup?.workflowId)
  }

  private fun validationRequest(
    persistence: PortableReviewBaselinePersistence,
    expectedWorkflowId: String = "wftr-test",
    subtask: DecompositionSubtask? = orphanSubtask(),
    gitOperations: WorkflowGitOperations = reachableGit(),
  ) = PortableReviewBaselineValidationRequest(
    persistence = persistence,
    repoRoot = Path.of("/tmp/repo"),
    manifest = testManifest(),
    subtaskId = 1,
    expectedWorkflowId = expectedWorkflowId,
    expectedRepositoryIdentity = repositoryIdentity,
    expectedBranch = goalBranch,
    gitOperations = gitOperations,
    subtask = subtask,
  )

  private fun goalRunnerRequest() = GoalRunnerRunRequest(
    issueKey = issueKey,
    repoRoot = Path.of("/tmp/repo"),
    invokedAgentId = "cursor",
  )

  private fun planningForRecovery() = GoalPlanningSweepOutcome.PreparedAll(
    identity = GoalPlanningIdentity(
      parentGoalWorkflowId = "wfl-parent",
      normalizedIssueKey = issueKey,
      repositoryIdentity = repositoryIdentity,
    ),
    provenance = GoalPlanningContractProvenance(
      parentSpecHash = "parent",
      decompositionManifestHash = "manifest",
      planningContractId = "planning",
    ),
    descriptors = listOf(
      GovernedGoalSubtaskDescriptor(
        subtaskId = 1,
        manifestOrder = 0,
        governedSubSpecPath = ".feature-specs/$issueKey/spec_subtask_1.md",
        subSpecHash = "subtask",
      ),
    ),
  )

  private fun testManifest() = DecompositionManifest(
    contractVersion = "0.5",
    issueKey = issueKey,
    featureName = "portable",
    parentSpecPath = ".feature-specs/$issueKey/spec.md",
    status = "in_progress",
    baseBranch = "main",
    featureBranch = goalBranch,
    currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
    subtasks = listOf(orphanSubtask()),
  )

  private fun orphanSubtask() = DecompositionSubtask(
    id = 1,
    name = "subtask-1",
    specPath = ".feature-specs/$issueKey/spec_subtask_1.md",
    status = "in_progress",
    workflowId = "wftr-test",
    lastResumableStep = "create_branch",
  )

  private fun manifestState() = GoalRunnerManifestState(
    parentWorkflowId = "wfl-parent",
    dbPath = "/tmp/goal.db",
    manifest = testManifest().copy(
      subtasks = listOf(
        orphanSubtask().copy(workflowId = "wftr-source"),
      ),
    ),
    controlState = GoalRunnerControlState(),
    repoRoot = Path.of("/tmp/repo"),
  )

  private fun portableContext() = PortableReviewBaselineRepairContext(
    manifest = testManifest(),
    repositoryIdentity = repositoryIdentity,
    subtaskId = 1,
    workflowId = "wftr-test",
  )

  private fun continuationArtifact() = FeatureTaskRuntimeGoalContinuationArtifact(
    issueKey = issueKey,
    subtaskId = 1,
    suppressPr = true,
    goalBranch = goalBranch,
    parentWorkflowId = "wfl-parent",
    codeReviewMode = CodeReviewExecutionMode.DEFAULT,
    validationDepth = ValidationDepth.FULL,
    qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.VALIDATE,
  ).toArtifactMap()

  private fun preImplementationGit(): WorkflowGitOperations = reachableGit(reviewBaseSha)

  private fun reachableGit(headShaValue: String = headSha): WorkflowGitOperations =
    object : WorkflowGitOperations by NoopWorkflowGitOperations {
      override fun currentBranch(repoRoot: Path): WorkflowGitOperationResult =
        WorkflowGitOperationResult(status = "ok", value = goalBranch)

      override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
        WorkflowGitOperationResult(status = "ok", value = headShaValue)

      override fun resolveCommit(repoRoot: Path, revision: String): WorkflowGitOperationResult =
        WorkflowGitOperationResult(status = "ok", value = revision)

      override fun isCommitAncestor(
        repoRoot: Path,
        ancestorSha: String,
        descendantSha: String,
      ): WorkflowGitOperationResult = WorkflowGitOperationResult(status = "ok", value = "true")
    }

  private fun portableDiagnosisRecord(workflowId: String, continuation: Map<String, Any?>) =
    WorkflowEngine(testWorkflowSnapshotValidator).let { engine ->
      val definition = WorkflowFamily.TASK_RUNTIME.definition
      val opened = engine.openRecord(definition, workflowId, "fis-portable", "preplan")
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          workflowStatus = "running",
          currentStepId = "implement",
          stepUpdates = null,
          artifactsPatch = mapOf(FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuation),
          sessionId = "ftr-portable",
        ),
      ).toRecord()
    }

  private fun captureBaselineGit(
    captures: MutableList<Unit>? = null,
    headShaValue: String = headSha,
  ): WorkflowGitOperations = object :
    WorkflowGitOperations by reachableGit(headShaValue),
    GoalSubtaskReviewGitOperationsProvider {
    override val goalSubtaskReviewOperations: GoalSubtaskReviewGitOperations =
      object : GoalSubtaskReviewGitOperations {
        override fun captureBaseline(repoRoot: Path, expectedBranch: String): GoalSubtaskReviewBaselineResult {
          captures?.add(Unit)
          return GoalSubtaskReviewBaselineResult(
            status = "ok",
            baseline = GoalSubtaskReviewBaseline(reviewBaseSha, emptyList()),
          )
        }

        override fun buildInput(repoRoot: Path, baseline: GoalSubtaskReviewBaseline, expectedBranch: String) =
          throw UnsupportedOperationException()

        override fun recoverBaseline(
          repoRoot: Path,
          request: GoalSubtaskReviewBaselineRecoveryRequest,
          expectedBranch: String,
        ) = throw UnsupportedOperationException()
      }
  }

  private class InMemoryPortablePersistence(
    private val artifact: PortableReviewBaseline?,
  ) : PortableReviewBaselinePersistence {
    override fun read(path: Path): PortableReviewBaseline? = artifact

    override fun writeAtomically(path: Path, artifact: PortableReviewBaseline) = Unit
  }

  private class ThrowingPortablePersistence(
    private val message: String,
  ) : PortableReviewBaselinePersistence {
    override fun read(path: Path): PortableReviewBaseline? = throw InvalidPortableReviewBaselineSchemaError(message)

    override fun writeAtomically(path: Path, artifact: PortableReviewBaseline) = Unit
  }

  private class RecordingPortablePersistence(
    private val events: MutableList<String>,
  ) : PortableReviewBaselinePersistence {
    val writes = mutableListOf<PortableReviewBaseline>()

    override fun read(path: Path): PortableReviewBaseline? = null

    override fun writeAtomically(path: Path, artifact: PortableReviewBaseline) {
      events += "baseline"
      writes += artifact
    }
  }

  private class RecordingOrphanManifestStore {
    var retiredSourceWorkflowId: String? = null
    var storedAudit: Map<String, Any?>? = null
    var storedSetup: GoalRunnerChildWorkflowSetup? = null

    fun replaceOrphanChildWorkflow(write: GoalRunnerOrphanChildReplacementWrite): GoalRunnerManifestState {
      retiredSourceWorkflowId = write.sourceWorkflowId
      storedAudit = write.auditEntry.toArtifactMap()
      storedSetup = write.setup
      return write.state
    }
  }
}
