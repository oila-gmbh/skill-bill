package skillbill.application.work

import skillbill.application.model.IdeStatusCandidate
import skillbill.application.model.IdeStatusFreshness
import skillbill.application.model.IdeStatusLifecycleState
import skillbill.application.model.IdeStatusSelectionTier
import java.time.Duration
import java.time.Instant

/**
 * SKILL-148 Subtask 1: deterministic multi-work precedence for IDE status.
 *
 * Precedence (documented adjacent to the selector per AC-005):
 * 1. Candidates are already filtered to one canonical repository identity.
 * 2. Non-live work past its retention ceiling is dropped entirely (see [retainedAt]).
 * 3. Work with a fresh authoritative update ranks ahead of stale work (see [freshnessKey]).
 * 4. Rank lifecycle tiers: active > paused > blocked > failed > recently_terminal > idle.
 * 5. Within a tier, never prefer a child prose/runtime/verify projection over an
 *    authoritative feature-goal projection for the same issue key.
 * 6. Remaining ties break by more recent authoritative updated_at, then stable
 *    workflow_id lexicographic order.
 *
 * Selection never synthesizes started_at clocks from updated_at.
 */
object IdeStatusSelectionPolicy {
  private const val LIVE_RETENTION_HOURS = 24L
  private const val BLOCKED_RETENTION_HOURS = 24L
  private const val SETTLED_RETENTION_HOURS = 6L

  /**
   * A running or paused workflow can legitimately stay quiet across a long phase, so
   * live work ages out only well past any plausible quiet period — dropping a genuine
   * run mid-flight would be worse than showing a crashed one.
   */
  val LIVE_RETENTION: Duration = Duration.ofHours(LIVE_RETENTION_HOURS)

  /**
   * Blocked work is waiting on the user, not finished. Aging it out on the settled
   * ceiling would hide the one state the surface exists to prompt about, so it keeps
   * the live ceiling; only genuine abandonment drops it.
   */
  val BLOCKED_RETENTION: Duration = Duration.ofHours(BLOCKED_RETENTION_HOURS)

  /**
   * Failed and terminal work is a past event, not ongoing work, so it ages out well
   * before live work does. It must still outlive [IdeStatusFreshnessClassifier.FRESH_WINDOW]:
   * a ceiling equal to the fresh window makes retention and freshness exact complements,
   * and a settled outcome could then never be reported as stale.
   */
  val SETTLED_RETENTION: Duration = Duration.ofHours(SETTLED_RETENTION_HOURS)

  fun select(candidates: List<IdeStatusCandidate>, observedAt: Instant): IdeStatusCandidate? {
    val retained = candidates.filter { retainedAt(it, observedAt) }
    if (retained.isEmpty()) return null
    return retained.sortedWith(comparator(observedAt)).first()
  }

  /**
   * A durable lifecycle is a claim, not proof: a goal that finished or was killed is not always
   * transitioned, so rows keep claiming `running` for the whole retention window and a finished
   * goal outranks the run that is actually moving. Work with a fresh authoritative update is
   * therefore ranked ahead of work without one, and tier precedence decides between candidates
   * that are equally fresh. A goal's update already folds in its newest same-repo child write,
   * so a genuine run stays ranked as live through every phase its child writes through.
   */
  private fun freshnessKey(candidate: IdeStatusCandidate, observedAt: Instant): Int =
    if (IdeStatusFreshnessClassifier.classify(candidate.updatedAt, observedAt) == IdeStatusFreshness.STALE) 1 else 0

  /**
   * The IDE surface reports work the runtime is currently reporting on, not a ledger of
   * every unresolved row. Each tier ages out relative to its authoritative updated_at;
   * past the ceiling the candidate is dropped and the repository reads as idle, so a
   * settled or abandoned workflow cannot occupy the widget indefinitely.
   */
  fun retainedAt(candidate: IdeStatusCandidate, observedAt: Instant): Boolean {
    val ceiling = when (candidate.selectionTier) {
      IdeStatusSelectionTier.ACTIVE, IdeStatusSelectionTier.PAUSED -> LIVE_RETENTION
      IdeStatusSelectionTier.BLOCKED -> BLOCKED_RETENTION
      IdeStatusSelectionTier.FAILED,
      IdeStatusSelectionTier.RECENTLY_TERMINAL,
      -> SETTLED_RETENTION
      IdeStatusSelectionTier.IDLE -> return true
    }
    val age = Duration.between(candidate.updatedAt, observedAt)
    // Clock skew (observation before update) is never grounds for dropping work.
    return age.isNegative || age <= ceiling
  }

  private fun comparator(observedAt: Instant): Comparator<IdeStatusCandidate> =
    compareBy<IdeStatusCandidate> { freshnessKey(it, observedAt) }
      .thenBy { it.selectionTier.rank }
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
