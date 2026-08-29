package skillbill.application.work

import skillbill.application.idestatus.model.IdeStatusFreshness
import java.time.Duration
import java.time.Instant

/**
 * SKILL-148 Subtask 1: freshness classification from observation time versus
 * authoritative updated_at.
 *
 * Boundaries (locked by fixtures):
 * - fresh: updated_at is present and age is within [FRESH_WINDOW]
 * - stale: updated_at is present and age exceeds [FRESH_WINDOW]
 * - unknown: updated_at is absent/unparseable, or observation precedes updated_at
 *   (clock skew / impossible age)
 */
object IdeStatusFreshnessClassifier {
  private const val FRESH_WINDOW_MINUTES = 30L
  val FRESH_WINDOW: Duration = Duration.ofMinutes(FRESH_WINDOW_MINUTES)

  fun classify(updatedAt: Instant?, observedAt: Instant): IdeStatusFreshness {
    if (updatedAt == null) return IdeStatusFreshness.UNKNOWN
    val age = Duration.between(updatedAt, observedAt)
    if (age.isNegative) return IdeStatusFreshness.UNKNOWN
    return if (age <= FRESH_WINDOW) IdeStatusFreshness.FRESH else IdeStatusFreshness.STALE
  }
}
