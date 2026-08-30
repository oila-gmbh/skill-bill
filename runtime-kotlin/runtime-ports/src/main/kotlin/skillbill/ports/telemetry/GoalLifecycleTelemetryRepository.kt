package skillbill.ports.telemetry

import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord

interface GoalLifecycleTelemetryRepository {
  fun goalStarted(record: GoalStartedRecord, level: String)

  fun goalSubtaskFinished(record: GoalSubtaskFinishedRecord, level: String)

  fun goalFinished(record: GoalFinishedRecord, level: String)

  fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String)
}
