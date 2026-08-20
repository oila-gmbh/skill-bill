package skillbill.application.featuretask

import skillbill.application.InMemoryRuntimeWorkflowRepository
import skillbill.application.RuntimeFakeDatabaseSessionFactory
import skillbill.application.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.model.FeatureTaskRuntimePreparation
import skillbill.application.model.FeatureTaskRuntimeRunRequest
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.WorkflowFamily
import skillbill.application.workflow.toRecord
import skillbill.ports.workflow.model.GoalSubtaskReviewBaseline
import skillbill.workflow.WorkflowEngine
import skillbill.workflow.model.CodeReviewExecutionMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowUpdateInput
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY
import skillbill.workflow.taskruntime.model.GoalSubtaskReviewState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeGoalContinuationAdoptionPersistenceTest {
  private val workflowId = "wftr-skill176-adopt-1"
  private val baselineSha = "a".repeat(40)

  @Test
  fun `resume from durable map missing validation_depth adopts supplied depth and records evidence`() {
    val harness = seedHarness(
      continuationMap = preContractContinuationMap(includeValidationDepth = null),
    )

    val prepared = assertIs<FeatureTaskRuntimePreparation.Prepared>(
      harness.preparation.prepare(resumeRequest(ValidationDepth.FULL)),
    )

    assertEquals(ValidationDepth.FULL, prepared.request.goalContinuation?.validationDepth)
    val artifacts = harness.repository.taskRuntimeArtifacts(workflowId)

    @Suppress("UNCHECKED_CAST")
    val continuation = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] as Map<String, Any?>
    assertEquals("full", continuation["validation_depth"])
    @Suppress("UNCHECKED_CAST")
    val adoption = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY] as Map<String, Any?>
    assertEquals("validation_depth", adoption["field"])
    assertEquals("full", adoption["adopted_value"])
    assertTrue(
      (adoption["reason"] as String).contains("predated the validation_depth contract"),
      "adoption evidence must record why the heal happened",
    )
  }

  @Test
  fun `resume with equal recorded validation_depth proceeds without adoption evidence`() {
    val harness = seedHarness(
      continuationMap = preContractContinuationMap(includeValidationDepth = "full"),
    )

    val prepared = assertIs<FeatureTaskRuntimePreparation.Prepared>(
      harness.preparation.prepare(resumeRequest(ValidationDepth.FULL)),
    )

    assertEquals(ValidationDepth.FULL, prepared.request.goalContinuation?.validationDepth)
    val artifacts = harness.repository.taskRuntimeArtifacts(workflowId)
    assertNull(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY])
    @Suppress("UNCHECKED_CAST")
    val continuation = artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY] as Map<String, Any?>
    assertEquals("full", continuation["validation_depth"])
  }

  private fun preContractContinuationMap(includeValidationDepth: String?): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "issue_key" to "SKILL-176",
      "subtask_id" to 1,
      "suppress_pr" to true,
      "goal_branch" to "feat/SKILL-176",
      "parent_workflow_id" to "wfl-parent",
      "code_review_mode" to "inline",
    ).apply {
      includeValidationDepth?.let { put("validation_depth", it) }
    }

  private fun resumeRequest(depth: ValidationDepth): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
    issueKey = "SKILL-176",
    workflowId = workflowId,
    sessionId = "fis-176",
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-176/spec.md",
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ),
    invokedAgentId = "claude",
    repoRoot = Path.of("/tmp/skillbill-skill-176"),
    goalContinuation = FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = "SKILL-176",
      subtaskId = 1,
      goalBranch = "feat/SKILL-176",
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = depth,
      reviewBaseline = GoalSubtaskReviewBaseline(baselineSha, emptyList()),
    ),
  )

  private fun seedHarness(continuationMap: Map<String, Any?>): AdoptionHarness {
    val repository = InMemoryRuntimeWorkflowRepository()
    val engine = WorkflowEngine(testWorkflowSnapshotValidator)
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
    val seeded = engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        workflowStatus = "running",
        currentStepId = "preplan",
        stepUpdates = null,
        artifactsPatch = mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuationMap,
          GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to GoalSubtaskReviewState.initial(
            reviewBaseSha = baselineSha,
            baselineUntrackedPaths = emptyList(),
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ).toArtifactMap(),
        ),
        sessionId = "fis-176",
      ),
    ).toRecord()
    repository.saveFeatureTaskRuntimeWorkflow(seeded)
    val database = RuntimeFakeDatabaseSessionFactory(repository)
    val recorder = FeatureTaskRuntimePhaseRecorder(
      database,
      testWorkflowSnapshotValidator,
      AcceptingFeatureTaskRuntimeHandoffEnvelopeValidator,
      AcceptingFeatureTaskRuntimeHandoffFoundationValidator,
    )
    val continuationRecorder =
      FeatureTaskRuntimeGoalContinuationRecorder(database, testWorkflowSnapshotValidator)
    val runInvariantsStore =
      FeatureTaskRuntimeRunInvariantsStore(database, testWorkflowSnapshotValidator)
    return AdoptionHarness(
      repository = repository,
      preparation = FeatureTaskRuntimeRunPreparation(recorder, continuationRecorder, runInvariantsStore),
    )
  }

  private data class AdoptionHarness(
    val repository: InMemoryRuntimeWorkflowRepository,
    val preparation: FeatureTaskRuntimeRunPreparation,
  )
}
