package skillbill.ports.persistence

import skillbill.telemetry.model.FeatureImplementFinishedRecord
import skillbill.telemetry.model.FeatureImplementStartedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord

// SKILL-66 Subtask 2: the lifecycle telemetry contract spans the
// implement/verify/quality/pr families plus the goal family; the cohesive
// write surface legitimately exceeds the per-type function budget.
@Suppress("TooManyFunctions")
interface LifecycleTelemetryRepository {
  fun featureImplementStarted(record: FeatureImplementStartedRecord, level: String)

  fun featureImplementFinished(record: FeatureImplementFinishedRecord, level: String)

  fun featureTaskRuntimeStarted(record: FeatureTaskRuntimeStartedRecord, level: String)

  fun featureTaskRuntimeFinished(record: FeatureTaskRuntimeFinishedRecord, level: String)

  fun qualityCheckStarted(record: QualityCheckStartedRecord, level: String)

  fun qualityCheckFinished(record: QualityCheckFinishedRecord, level: String)

  fun featureVerifyStarted(record: FeatureVerifyStartedRecord, level: String)

  fun featureVerifyFinished(record: FeatureVerifyFinishedRecord, level: String)

  fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String)

  fun goalStarted(record: GoalStartedRecord, level: String)

  fun goalSubtaskFinished(record: GoalSubtaskFinishedRecord, level: String)

  fun goalFinished(record: GoalFinishedRecord, level: String)
}
