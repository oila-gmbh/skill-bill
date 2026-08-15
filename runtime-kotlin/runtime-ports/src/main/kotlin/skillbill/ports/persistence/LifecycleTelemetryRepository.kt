package skillbill.ports.persistence

import skillbill.review.model.ReviewStageDegradationMeasurement
import skillbill.telemetry.model.FeatureTaskRuntimeFinishedRecord
import skillbill.telemetry.model.FeatureTaskRuntimeStartedRecord
import skillbill.telemetry.model.FeatureVerifyFinishedRecord
import skillbill.telemetry.model.FeatureVerifyStartedRecord
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.telemetry.model.GoalSubtaskFinishedRecord
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import skillbill.telemetry.model.QualityCheckFinishedRecord
import skillbill.telemetry.model.QualityCheckStartedRecord
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeDiagnosticDegradationMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeRejectionMeasurement
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeSharedEvidenceMeasurement

@Suppress("TooManyFunctions")
interface LifecycleTelemetryRepository {
  fun featureTaskRuntimeProjectionMeasurement(record: FeatureTaskRuntimeProjectionMeasurement) = Unit

  fun featureTaskRuntimeSharedEvidence(record: FeatureTaskRuntimeSharedEvidenceMeasurement) = Unit

  fun featureTaskRuntimeRejection(record: FeatureTaskRuntimeRejectionMeasurement) = Unit

  fun featureTaskRuntimeDiagnosticDegradation(record: FeatureTaskRuntimeDiagnosticDegradationMeasurement) = Unit

  fun reviewStageDegradation(record: ReviewStageDegradationMeasurement) = Unit

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

  fun goalIssueFinished(record: GoalIssueFinishedRecord, level: String)
}
