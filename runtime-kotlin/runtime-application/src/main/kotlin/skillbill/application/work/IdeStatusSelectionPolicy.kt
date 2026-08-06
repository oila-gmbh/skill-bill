package skillbill.application.work

import skillbill.application.model.IdeStatusCandidate
import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusSelectionTier

/**
 * SKILL-148 Subtask 1: deterministic multi-work precedence for IDE status.
 *
 * Precedence (documented adjacent to the selector per AC-005):
 * 1. Candidates are already filtered to one canonical repository identity.
 * 2. Rank lifecycle tiers: active > paused > blocked > failed > recently_terminal > idle.
 * 3. Within a tier, never prefer a child prose/runtime/verify projection over an
 *    authoritative feature-goal projection for the same issue key.
 * 4. Remaining ties break by more recent authoritative updated_at, then stable
 *    workflow_id lexicographic order.
 *
 * Selection never synthesizes started_at clocks from updated_at.
 */
object IdeStatusSelectionPolicy {
  fun select(candidates: List<IdeStatusCandidate>): IdeStatusCandidate? {
    if (candidates.isEmpty()) return null
    return candidates.sortedWith(comparator).first()
  }

  private val comparator: Comparator<IdeStatusCandidate> =
    compareBy<IdeStatusCandidate> { it.selectionTier.rank }
      .thenBy { if (it.isGoalAuthoritative) 0 else 1 }
      .thenByDescending { it.updatedAt }
      .thenBy { it.workflowId }

  fun selectionTier(lifecycle: IdeStatusLifecycleState): IdeStatusSelectionTier = when (lifecycle) {
    IdeStatusLifecycleState.ACTIVE -> IdeStatusSelectionTier.ACTIVE
    IdeStatusLifecycleState.PAUSED -> IdeStatusSelectionTier.PAUSED
    IdeStatusLifecycleState.BLOCKED -> IdeStatusSelectionTier.BLOCKED
    IdeStatusLifecycleState.FAILED -> IdeStatusSelectionTier.FAILED
    IdeStatusLifecycleState.TERMINAL -> IdeStatusSelectionTier.RECENTLY_TERMINAL
    IdeStatusLifecycleState.IDLE -> IdeStatusSelectionTier.IDLE
  }

  fun lifecycleFromDurableState(currentState: String): IdeStatusLifecycleState? = when (currentState) {
    "running", "pending" -> IdeStatusLifecycleState.ACTIVE
    "paused" -> IdeStatusLifecycleState.PAUSED
    "blocked" -> IdeStatusLifecycleState.BLOCKED
    "failed" -> IdeStatusLifecycleState.FAILED
    "completed", "abandoned", "complete", "skipped" -> IdeStatusLifecycleState.TERMINAL
    else -> null
  }
}
