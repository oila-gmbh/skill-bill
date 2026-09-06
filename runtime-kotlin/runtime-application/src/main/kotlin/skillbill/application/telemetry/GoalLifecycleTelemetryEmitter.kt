package skillbill.application.telemetry

import skillbill.application.telemetry.model.GoalFinishedRequest
import skillbill.application.telemetry.model.GoalIssueFinishedRequest
import skillbill.application.telemetry.model.GoalStartedRequest
import skillbill.application.telemetry.model.GoalSubtaskFinishedRequest

interface GoalLifecycleTelemetryEmitter {
  fun goalStarted(request: GoalStartedRequest)

  fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest)

  fun goalFinished(request: GoalFinishedRequest)

  fun goalIssueFinished(request: GoalIssueFinishedRequest)

  companion object {
    val NONE: GoalLifecycleTelemetryEmitter = object : GoalLifecycleTelemetryEmitter {
      override fun goalStarted(request: GoalStartedRequest) = Unit

      override fun goalSubtaskFinished(request: GoalSubtaskFinishedRequest) = Unit

      override fun goalFinished(request: GoalFinishedRequest) = Unit

      override fun goalIssueFinished(request: GoalIssueFinishedRequest) = Unit
    }
  }
}
