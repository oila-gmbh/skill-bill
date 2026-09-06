package skillbill.application.featuretask
import skillbill.application.featuretask.model.FeatureTaskRuntimeGoalContinuationContext
import skillbill.application.featuretask.model.FeatureTaskRuntimeRunRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.review.context.model.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.IssueKey
import skillbill.workflow.decomposition.model.SubtaskId
import skillbill.workflow.engine.model.SessionId
import skillbill.workflow.engine.model.WorkflowId
import skillbill.workflow.goal.model.ValidationDepth
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FeatureTaskRuntimeGoalContinuationPolicyTest {
  private val baseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList())

  @Test
  fun `absent durable validation depth adopts supplied depth without conflict`() {
    assertNull(
      goalContinuationConflict(
        request = request(goalContinuation = continuation(validationDepth = ValidationDepth.FULL)),
        durable = durable(validationDepth = null),
        baseline = baseline,
      ),
    )
  }

  @Test
  fun `matching durable and supplied validation depth proceeds`() {
    assertNull(
      goalContinuationConflict(
        request = request(goalContinuation = continuation(validationDepth = ValidationDepth.DEFAULT)),
        durable = durable(validationDepth = ValidationDepth.FULL),
        baseline = baseline,
      ),
    )
    assertEquals(ValidationDepth.FULL, ValidationDepth.DEFAULT)
  }

  @Test
  fun `FeatureTaskRuntimeGoalContinuationContext defaults validationDepth to full`() {
    assertEquals(
      ValidationDepth.FULL,
      FeatureTaskRuntimeGoalContinuationContext(
        parentIssueKey = "SKILL-173",
        subtaskId = SubtaskId(1),
        goalBranch = "feat/SKILL-173",
        suppressPr = true,
        reviewBaseline = baseline,
      ).validationDepth,
    )
  }

  private fun request(
    goalContinuation: FeatureTaskRuntimeGoalContinuationContext? = null,
  ): FeatureTaskRuntimeRunRequest = FeatureTaskRuntimeRunRequest(
    issueKey = IssueKey("SKILL-173"),
    workflowId = WorkflowId("wfl-child"),
    sessionId = SessionId("ftr-child"),
    runInvariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-173/spec.md",
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    ),
    invokedAgentId = "claude",
    repoRoot = Path.of("/tmp/skillbill-validation-depth"),
    goalContinuation = goalContinuation,
  )

  private fun continuation(validationDepth: ValidationDepth): FeatureTaskRuntimeGoalContinuationContext =
    FeatureTaskRuntimeGoalContinuationContext(
      parentIssueKey = "SKILL-173",
      subtaskId = SubtaskId(1),
      goalBranch = "feat/SKILL-173",
      suppressPr = true,
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = validationDepth,
      reviewBaseline = baseline,
    )

  private fun durable(validationDepth: ValidationDepth?): FeatureTaskRuntimeGoalContinuationArtifact =
    FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = IssueKey("SKILL-173"),
      subtaskId = SubtaskId(1),
      suppressPr = true,
      goalBranch = "feat/SKILL-173",
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = validationDepth,
    )
}
