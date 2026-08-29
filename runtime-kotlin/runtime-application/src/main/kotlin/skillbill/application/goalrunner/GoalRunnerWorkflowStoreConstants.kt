package skillbill.application.goalrunner

import java.time.Duration

internal const val STALENESS_EVIDENCE_WINDOW_MINUTES: Long = 30
internal val STALENESS_EVIDENCE_WINDOW: Duration = Duration.ofMinutes(STALENESS_EVIDENCE_WINDOW_MINUTES)
internal const val GOAL_REVIEW_POLICY_ARTIFACT_KEY = "goal_review_policy"
internal const val GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY = "goal_out_of_band_acceptances"

internal const val GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY =
  "goal_continuation_outcome_displacement"
