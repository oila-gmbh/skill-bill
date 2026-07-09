package skillbill.application

import skillbill.application.decomposition.intentFor
import skillbill.application.decomposition.statusFromUpdate
import skillbill.application.decomposition.withRuntimeFields
import skillbill.application.model.DecompositionManifestRuntimeUpdate
import skillbill.workflow.model.CurrentSubtaskIntent
import skillbill.workflow.model.DecompositionManifest
import skillbill.workflow.model.DecompositionSubtask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// SKILL-68 AC4/AC5: the completing commit SHA that advances a suppress_pr subtask is sourced from
// commit_push_result.commit_sha (preferred) or the recovered goal_continuation_outcome.commit_sha.
class DecompositionManifestRuntimeStateSupportTest {
  @Test
  fun `subtask advances to complete when the commit sha lives only in the recovered goal continuation outcome`() {
    val update = commitPushUpdate(
      goalContinuationOutcome = mapOf(
        "issue_key" to "SKILL-68",
        "subtask_id" to 5,
        "status" to "complete",
        "commit_sha" to "recovered-sha",
        "last_resumable_step" to "commit_push",
      ),
    )

    assertEquals("complete", statusFromUpdate(update))
    assertEquals(CurrentSubtaskIntent(subtaskId = 0, action = "complete"), intentFor(5, statusFromUpdate(update)))
  }

  @Test
  fun `subtask advances to complete on the unchanged commit push result happy path`() {
    val update = commitPushUpdate(commitPushResult = mapOf("commit_sha" to "commit-sha"))

    assertEquals("complete", statusFromUpdate(update))
    assertEquals(CurrentSubtaskIntent(subtaskId = 0, action = "complete"), intentFor(5, statusFromUpdate(update)))
  }

  @Test
  fun `conflicting commit push result and goal continuation outcome shas fail loudly`() {
    val update = commitPushUpdate(
      commitPushResult = mapOf("commit_sha" to "sha-a"),
      goalContinuationOutcome = mapOf(
        "issue_key" to "SKILL-68",
        "subtask_id" to 5,
        "status" to "complete",
        "commit_sha" to "sha-b",
      ),
    )

    val error = assertFailsWith<IllegalStateException> { statusFromUpdate(update) }
    assertEquals(true, error.message?.contains("sha-a"))
    assertEquals(true, error.message?.contains("sha-b"))
  }

  @Test
  fun `blocked subtask preserves prefixed artifact reason`() {
    val updated = baseSubtask().withRuntimeFields(
      manifest = baseManifest(),
      update = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-5",
        workflowStatus = "blocked",
        currentStepId = "validate",
        artifactsPatch = mapOf("blocked_reason" to "validation: schema gate failed"),
      ),
      status = "blocked",
    )

    assertEquals("validation: schema gate failed", updated.blockedReason)
  }

  @Test
  fun `blocked subtask prefixes artifact reason when missing category`() {
    val updated = baseSubtask().withRuntimeFields(
      manifest = baseManifest(),
      update = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-5",
        workflowStatus = "blocked",
        currentStepId = "review",
        artifactsPatch = mapOf("blocked_reason" to "review failed"),
      ),
      status = "blocked",
    )

    assertEquals("runtime: review failed", updated.blockedReason)
  }

  @Test
  fun `blocked suppress pr commit failure receives git category prefix`() {
    val updated = baseSubtask().withRuntimeFields(
      manifest = baseManifest(),
      update = commitPushUpdate(),
      status = "blocked",
    )

    assertEquals(
      "git: Goal-continuation commit_push completed without commit_push_result.commit_sha.",
      updated.blockedReason,
    )
  }

  @Test
  fun `blocked workflow step fallback receives runtime category prefix`() {
    val updated = baseSubtask().withRuntimeFields(
      manifest = baseManifest(),
      update = DecompositionManifestRuntimeUpdate(
        workflowId = "wfl-subtask-5",
        workflowStatus = "blocked",
        currentStepId = "audit",
      ),
      status = "blocked",
    )

    assertEquals("runtime: Workflow step 'audit' is blocked.", updated.blockedReason)
  }

  private fun commitPushUpdate(
    commitPushResult: Map<String, Any?>? = null,
    goalContinuationOutcome: Map<String, Any?>? = null,
  ): DecompositionManifestRuntimeUpdate = DecompositionManifestRuntimeUpdate(
    workflowId = "wfl-subtask-5",
    workflowStatus = "running",
    currentStepId = "commit_push",
    stepUpdates = listOf(mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1)),
    artifactsPatch = buildMap {
      put("goal_continuation", mapOf("issue_key" to "SKILL-68", "subtask_id" to 5, "suppress_pr" to true))
      commitPushResult?.let { put("commit_push_result", it) }
      goalContinuationOutcome?.let { put("goal_continuation_outcome", it) }
    },
  )

  private fun baseManifest(): DecompositionManifest = DecompositionManifest(
    issueKey = "SKILL-68",
    featureName = "feature",
    parentSpecPath = ".feature-specs/SKILL-68/spec.md",
    baseBranch = "main",
    featureBranch = "feature/SKILL-68",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 5, action = "start"),
    subtasks = listOf(baseSubtask()),
  )

  private fun baseSubtask(): DecompositionSubtask = DecompositionSubtask(
    id = 5,
    name = "subtask",
    specPath = ".feature-specs/SKILL-68/subtask.md",
  )
}
