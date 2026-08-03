package skillbill.review.context

/**
 * Raised wherever a persisted or explicit `delegated` code-review selection resolves. The external
 * delegated review subsystem was removed, and no caller may silently degrade to the inline lane:
 * an operator who asked for delegated depth must learn that depth no longer exists.
 */
class DelegatedReviewModeRemovedException(selectionSource: String) : IllegalArgumentException(
  "External delegated code review was removed by SKILL-159, but '$selectionSource' selected it. " +
    "Use 'code-review:inline' (or --code-review-mode inline/auto) for the inline parent lane, and " +
    "'parallel-review:<agent>' for a second review lane on the surviving fan-out path.",
)
