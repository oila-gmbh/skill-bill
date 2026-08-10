package skillbill.ports.persistence

import skillbill.goalrunner.model.GoalPlanningStatusSnapshot
import skillbill.ports.persistence.model.GoalPlanningIdentity
import skillbill.ports.persistence.model.GoalPlanningPreparationRecord
import skillbill.ports.persistence.model.GoalPlanningPreparationStatus
import skillbill.ports.persistence.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.persistence.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.persistence.model.SharedGoalPreplanCheckpoint

@Suppress("TooManyFunctions") // single cohesive boundary: shared preplan, subtask plans, and planning status
interface NormalizedGoalPlanningPreparationRepository {
  fun boundedStatus(
    parentGoalWorkflowId: String,
    orderedSubtaskIds: List<Int>,
    blockedSubtaskId: Int? = null,
    blockedReason: String? = null,
  ): GoalPlanningStatusSnapshot = GoalPlanningStatusSnapshot(
    if (blockedReason == null) {
      skillbill.goalrunner.model.GoalPlanningStatusState.NOT_STARTED
    } else {
      skillbill.goalrunner.model.GoalPlanningStatusState.BLOCKED
    },
    false,
    0,
    orderedSubtaskIds.size,
    blockedSubtaskId ?: orderedSubtaskIds.firstOrNull(),
    blockedReason ?: "Goal planning has not started.",
  )
  fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint): Unit =
    error("Shared goal preplan checkpointing is not implemented by this repository.")

  /**
   * Atomically replaces the exact shared preplan observed by the caller. A concurrent writer changing the
   * stored payload after the caller's gate decision must fail the compare-and-replace instead of being deleted.
   */
  fun replaceSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint, expectedPayloadSha256: String): Unit =
    error("Shared goal preplan replacement is not implemented by this repository.")

  /**
   * Provenance-only shared-preplan refresh: UPDATEs shared provenance to [provenance] while keeping
   * `payload_sha256` / `preplan_payload` bytes unchanged, and re-stamps every retained sibling plan row's
   * provenance in the same transaction. Never DELETEs the shared row.
   */
  fun advanceSharedPreplanProvenance(
    identity: GoalPlanningIdentity,
    expectedPayloadSha256: String,
    provenance: GoalPlanningContractProvenance,
  ): Unit = error("Shared goal preplan provenance advance is not implemented by this repository.")

  /**
   * Single cascade seam for automatic shared-preplan refresh when the heading set changes (and the ST3
   * ownership hook). Until terminal exclusion lands, discards every sibling plan row for the parent.
   * Returns the discarded subtask ids in prepared order.
   */
  fun cascadeSiblingPlansAfterSharedPreplanRefresh(parentGoalWorkflowId: String): List<Int> =
    error("Shared-preplan refresh plan cascade is not implemented by this repository.")

  fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint?

  fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): Unit =
    error("Goal subtask plan checkpointing is not implemented by this repository.")

  /**
   * Overwrites a stored subtask plan the producer projection gate rejects, so a regeneration lands instead of
   * wedging on the immutable guard. Provenance parity with the governing shared preplan is still enforced.
   * The caller decides regenerability from a prior read, so this is not atomic against a concurrent writer;
   * only the single-writer goal planning sweep may call it.
   */
  fun replaceSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint): Unit =
    error("Goal subtask plan replacement is not implemented by this repository.")

  /**
   * Deletes exactly one stored subtask plan row. Returns the deleted row count (0 or 1) so callers can
   * distinguish discard from a no-op. Does not touch the shared preplan or sibling plans.
   */
  fun deleteSubtaskPlan(parentGoalWorkflowId: String, subtaskId: Int): Int =
    error("Goal subtask plan deletion is not implemented by this repository.")

  /**
   * Digest-conditional shared-preplan delete. Removes the shared row only when [expectedPayloadSha256]
   * still matches the stored payload; refuses with zero mutation on mismatch. Subtask plan rows for the
   * same parent cascade via the FK. Does not replace or reuse [replaceSharedPreplan].
   */
  fun deleteSharedPreplan(identity: GoalPlanningIdentity, expectedPayloadSha256: String): Int =
    error("Shared goal preplan deletion is not implemented by this repository.")

  fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = emptyList()

  fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = false

  /** Payload digest of the stored shared preplan, or null when absent. */
  fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = null

  fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ): GoalSubtaskPlanCheckpoint?

  fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): List<GoalSubtaskPlanCheckpoint>

  fun preparedPlanCount(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).size

  fun firstMissingPlan(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ): Int? {
    val prepared = listSubtaskPlansOrdered(expectedIdentity, orderedDescriptors).mapTo(mutableSetOf()) { it.subtaskId }
    return orderedDescriptors.firstOrNull { it.subtaskId !in prepared }?.subtaskId
  }
}

interface LegacyGoalPlanningPreparationRepository {
  fun markPrepared(record: GoalPlanningPreparationRecord)

  fun findByGoalAndSubtask(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationRecord?

  fun listPreparedByGoalOrdered(parentGoalWorkflowId: String): List<GoalPlanningPreparationRecord>

  fun preparedCount(parentGoalWorkflowId: String): Int

  fun firstMissingOrIncompleteSubtask(parentGoalWorkflowId: String, orderedSubtaskIds: List<Int>): Int?

  fun preparedStatus(parentGoalWorkflowId: String, subtaskId: Int): GoalPlanningPreparationStatus?

  fun deleteByGoal(parentGoalWorkflowId: String): Int
}

interface GoalPlanningPreparationRepository :
  NormalizedGoalPlanningPreparationRepository,
  LegacyGoalPlanningPreparationRepository
