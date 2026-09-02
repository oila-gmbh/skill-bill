package skillbill.application.telemetry

import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest

interface GoalLifecycleTelemetryEmitter {
  fun goalStarted(request: GoalStartedRequest, dbOverride: String?)

  fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?)

  fun goalFinished(request: GoalFinishedRequest, dbOverride: String?)

  fun goalIssueFinished(request: GoalIssueFinishedRequest, dbOverride: String?)

  companion object {
    val NONE: GoalLifecycleTelemetryEmitter = object : GoalLifecycleTelemetryEmitter {
      override fun goalStarted(request: GoalStartedRequest, dbOverride: String?) = Unit

      override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest, dbOverride: String?) = Unit

      override fun goalFinished(request: GoalFinishedRequest, dbOverride: String?) = Unit

      override fun goalIssueFinished(request: GoalIssueFinishedRequest, dbOverride: String?) = Unit
    }
  }
}
