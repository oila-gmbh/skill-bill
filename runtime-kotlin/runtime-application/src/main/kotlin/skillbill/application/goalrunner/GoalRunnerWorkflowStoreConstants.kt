package skillbill.application.goalrunner

import java.time.Duration

const val STALENESS_EVIDENCE_WINDOW_MINUTES: Long = 30
val STALENESS_EVIDENCE_WINDOW: Duration = Duration.ofMinutes(STALENESS_EVIDENCE_WINDOW_MINUTES)
const val GOAL_REVIEW_POLICY_ARTIFACT_KEY = "goal_review_policy"
const val GOAL_OUT_OF_BAND_ACCEPTANCE_ARTIFACT_KEY = "goal_out_of_band_acceptances"

const val GOAL_CONTINUATION_OUTCOME_DISPLACEMENT_ARTIFACT_KEY =
  "goal_continuation_outcome_displacement"
