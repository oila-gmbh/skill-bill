package skillbill.workflow.taskruntime

import skillbill.error.InvalidWorkflowStateSchemaError
import skillbill.review.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.toArtifactMap
import skillbill.workflow.model.appendBoundedHistoryBySequence
import skillbill.workflow.taskruntime.model.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.featureTaskRuntimeRunInvariantsFromArtifactMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimePersistenceModelsTest {
  @Test
  fun `per-phase record round trips through its artifact map`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "implement",
      status = "completed",
      attemptCount = 2,
      startedAt = "2026-06-02T10:00:00Z",
      finishedAt = "2026-06-02T10:05:00Z",
      durationMillis = 300_000,
      resolvedAgentId = "agent-implement-1",
      outputArtifact = """{"contract_version":"0.1"}""",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
    assertEquals(record, decoded)
  }

  @Test
  fun `resolved-branch round trips through its artifact map when created`() {
    val resolved = FeatureTaskRuntimeResolvedBranch(
      branch = "feat/SKILL-65.1-runtime-feature-task-parity",
      baseBranch = "main",
      created = true,
    )
    val decoded = FeatureTaskRuntimeResolvedBranch.fromArtifactMap(resolved.toArtifactMap())
    assertEquals(resolved, decoded)
    assertTrue(decoded.created)
    assertEquals("main", decoded.baseBranch)
  }

  @Test
  fun `reused resolved-branch omits base branch and defaults created false`() {
    val resolved = FeatureTaskRuntimeResolvedBranch(branch = "feat/pre-created-branch")
    val map = resolved.toArtifactMap()
    assertNull(map["base_branch"])
    assertEquals(false, map["created"])
    assertEquals(resolved, FeatureTaskRuntimeResolvedBranch.fromArtifactMap(map))
  }

  @Test
  fun `resolved-branch decode loud-fails on a blank branch`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeResolvedBranch.fromArtifactMap(mapOf("branch" to ""))
    }
  }

  @Test
  fun `running per-phase record omits finish and duration and output`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "plan",
      status = "running",
      attemptCount = 1,
      startedAt = "2026-06-02T10:00:00Z",
      resolvedAgentId = "agent-plan-1",
    )
    val map = record.toArtifactMap()
    assertNull(map["finished_at"])
    assertNull(map["duration_millis"])
    assertNull(map["output_artifact"])
    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(map))
  }

  @Test
  fun `blocked per-phase record round trips with blocked reason and distinct first started at`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "review",
      status = "blocked",
      attemptCount = 3,
      startedAt = "2026-06-02T10:10:00Z",
      firstStartedAt = "2026-06-02T10:00:00Z",
      resolvedAgentId = "agent-review-1",
      blockedReason = "exhausted the bounded fix loop",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(record.toArtifactMap())
    assertEquals(record, decoded)
    assertEquals("2026-06-02T10:00:00Z", decoded.firstStartedAt)
    assertEquals("exhausted the bounded fix loop", decoded.blockedReason)
  }

  @Test
  fun `per-phase record decode falls back first started at to started at when absent`() {
    val legacy = mapOf(
      "phase_id" to "plan",
      "status" to "running",
      "attempt_count" to 1,
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(legacy)
    assertEquals("2026-06-02T10:00:00Z", decoded.firstStartedAt)
    assertNull(decoded.blockedReason)
  }

  @Test
  fun `per-phase record decode loud-fails on missing required field`() {
    val malformed = mapOf(
      "phase_id" to "plan",
      "status" to "completed",
      // attempt_count missing
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `per-phase record decode loud-fails on wrong-typed attempt count`() {
    val malformed = mapOf(
      "phase_id" to "plan",
      "status" to "completed",
      "attempt_count" to "not-an-int",
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry round trips through its artifact map for every action`() {
    FeatureTaskRuntimePhaseLedgerAction.entries.forEachIndexed { index, action ->
      val entry = FeatureTaskRuntimePhaseLedgerEntry(
        action = action,
        sequenceNumber = index,
        timestamp = "2026-06-02T10:0$index:00Z",
        phaseId = "review",
        attemptCount = 1,
        resolvedAgentId = "agent-review-1",
        fixLoopIteration = if (action == FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION) 2 else null,
        blockedReason = if (action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED) "gate failed" else null,
      )
      assertEquals(entry, FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(entry.toArtifactMap()))
    }
  }

  @Test
  fun `ledger action wire values cover the required event set`() {
    assertEquals(
      listOf("start", "resume", "retry", "fix_loop_iteration", "loop_edge", "blocked", "complete"),
      FeatureTaskRuntimePhaseLedgerAction.entries.map { it.wireValue },
    )
  }

  @Test
  fun `per-phase record round trips loop_id and edge_iteration additively`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "implement",
      status = "running",
      attemptCount = 2,
      startedAt = "2026-06-02T10:00:00Z",
      resolvedAgentId = "agent-implement-1",
      loopId = "review-fix",
      edgeIteration = 2,
    )
    val map = record.toArtifactMap()
    assertEquals("review-fix", map["loop_id"])
    assertEquals(2, map["edge_iteration"])
    assertEquals(record, FeatureTaskRuntimePhaseRecord.fromArtifactMap(map))
  }

  @Test
  fun `per-phase record omits loop_id and edge_iteration when absent and old maps decode unchanged`() {
    val record = FeatureTaskRuntimePhaseRecord(
      phaseId = "plan",
      status = "running",
      attemptCount = 1,
      startedAt = "2026-06-02T10:00:00Z",
      resolvedAgentId = "agent-plan-1",
    )
    val map = record.toArtifactMap()
    assertNull(map["loop_id"])
    assertNull(map["edge_iteration"])
    // An old map predating the loop fields decodes with null loop context.
    val legacy = mapOf(
      "phase_id" to "plan",
      "status" to "running",
      "attempt_count" to 1,
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-plan-1",
    )
    val decoded = FeatureTaskRuntimePhaseRecord.fromArtifactMap(legacy)
    assertNull(decoded.loopId)
    assertNull(decoded.edgeIteration)
  }

  @Test
  fun `per-phase record decode loud-fails on edge_iteration below one`() {
    val malformed = mapOf(
      "phase_id" to "implement",
      "status" to "running",
      "attempt_count" to 1,
      "started_at" to "2026-06-02T10:00:00Z",
      "resolved_agent_id" to "agent-implement-1",
      "loop_id" to "review-fix",
      "edge_iteration" to 0,
    )
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePhaseRecord.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry round trips loop_id and edge_iteration additively`() {
    val entry = FeatureTaskRuntimePhaseLedgerEntry(
      action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
      sequenceNumber = 7,
      timestamp = "2026-06-02T10:07:00Z",
      phaseId = "implement",
      attemptCount = 1,
      loopId = "review-fix",
      edgeIteration = 3,
    )
    val map = entry.toArtifactMap()
    assertEquals("review-fix", map["loop_id"])
    assertEquals(3, map["edge_iteration"])
    assertEquals(entry, FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(map))
    // An old ledger map without the loop fields decodes unchanged.
    val legacy = mapOf(
      "action" to "retry",
      "sequence_number" to 0,
      "timestamp" to "2026-06-02T10:00:00Z",
      "phase_id" to "review",
      "attempt_count" to 1,
    )
    val decoded = FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(legacy)
    assertNull(decoded.loopId)
    assertNull(decoded.edgeIteration)
  }

  @Test
  fun `unknown ledger action loud-fails with a typed schema error`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerAction.fromWire("teleport")
    }
  }

  @Test
  fun `run-invariants decode loud-fails with typed schema error on unknown feature size`() {
    val malformed = mapOf(
      "spec_reference" to ".feature-specs/SKILL-65/spec.md",
      "feature_size" to "HUGE",
      "acceptance_criteria" to listOf("AC-1"),
      "mandates_and_overrides" to emptyList<String>(),
      "code_review_mode" to "auto",
    )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeRunInvariantsFromArtifactMap(malformed)
    }
  }

  @Test
  fun `run-invariants persist the selected code-review mode strictly`() {
    val invariants = FeatureTaskRuntimeRunInvariants(
      specReference = ".feature-specs/SKILL-119/spec.md",
      acceptanceCriteria = listOf("AC-1"),
      mandatesAndOverrides = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.DELEGATED,
    )
    val map = invariants.toArtifactMap()

    assertEquals("delegated", map["code_review_mode"])
    assertEquals(invariants, featureTaskRuntimeRunInvariantsFromArtifactMap(map))
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeRunInvariantsFromArtifactMap(map + ("code_review_mode" to "DELEGATED"))
    }
  }

  @Test
  fun `goal-continuation outcome round trips agent attribution through its artifact map`() {
    val outcome = FeatureTaskRuntimeGoalContinuationOutcome(
      issueKey = "SKILL-89",
      subtaskId = 4,
      status = "complete",
      workflowId = "wf-4",
      commitSha = "abc123",
      lastResumableStep = "commit_push",
      finalizingAgentId = "claude",
      participatingAgentIds = listOf("codex", "claude"),
    )
    val map = outcome.toArtifactMap()
    assertEquals("claude", map["finalizing_agent_id"])
    assertEquals(listOf("codex", "claude"), map["participating_agent_ids"])
    assertEquals(outcome, FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(map))
  }

  @Test
  fun `legacy goal-continuation outcome without agent fields decodes to null and empty`() {
    val legacy = mapOf(
      "issue_key" to "SKILL-89",
      "subtask_id" to 2,
      "status" to "complete",
      "workflow_id" to "wf-2",
      "last_resumable_step" to "commit_push",
    )
    val decoded = FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(legacy)
    assertNull(decoded.finalizingAgentId)
    assertTrue(decoded.participatingAgentIds.isEmpty())
  }

  @Test
  fun `goal-continuation outcome omits finalizing agent when null but always emits the participants list`() {
    val outcome = FeatureTaskRuntimeGoalContinuationOutcome(
      issueKey = "SKILL-89",
      subtaskId = 1,
      status = "complete",
      workflowId = "wf-1",
      lastResumableStep = "commit_push",
    )
    val map = outcome.toArtifactMap()
    assertNull(map["finalizing_agent_id"])
    assertEquals(emptyList<String>(), map["participating_agent_ids"])
  }

  @Test
  fun `goal-continuation outcome decode loud-fails on a non-string participant element`() {
    val malformed = mapOf(
      "issue_key" to "SKILL-89",
      "subtask_id" to 3,
      "status" to "complete",
      "workflow_id" to "wf-3",
      "last_resumable_step" to "commit_push",
      "participating_agent_ids" to listOf("codex", 7),
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `goal-continuation outcome decode loud-fails on a non-list participants value`() {
    val malformed = mapOf(
      "issue_key" to "SKILL-89",
      "subtask_id" to 3,
      "status" to "complete",
      "workflow_id" to "wf-3",
      "last_resumable_step" to "commit_push",
      "participating_agent_ids" to "codex",
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimeGoalContinuationOutcome.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `ledger entry decode loud-fails on missing timestamp`() {
    val malformed = mapOf(
      "action" to "start",
      "sequence_number" to 0,
      // timestamp missing
      "phase_id" to "plan",
      "attempt_count" to 1,
    )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      FeatureTaskRuntimePhaseLedgerEntry.fromArtifactMap(malformed)
    }
  }

  @Test
  fun `append-only ledger keeps monotonic sequence order and prunes oldest beyond the limit`() {
    var ledger = emptyList<Map<String, Any?>>()
    (0 until FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT + 3).forEach { index ->
      val entry = FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = index,
        timestamp = "2026-06-02T10:00:00Z",
        phaseId = "implement",
        attemptCount = 1,
      )
      ledger = appendBoundedHistoryBySequence(ledger, entry.toArtifactMap(), FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)
    }
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT, ledger.size)
    val sequences = ledger.map { (it["sequence_number"] as Number).toInt() }
    assertEquals(sequences.sorted(), sequences)
    assertEquals(3, sequences.first())
    assertEquals(FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT + 2, sequences.last())
  }
}
