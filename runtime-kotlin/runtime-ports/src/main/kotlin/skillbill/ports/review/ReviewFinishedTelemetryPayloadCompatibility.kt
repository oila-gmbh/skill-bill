package skillbill.ports.review

import skillbill.contracts.JsonPayloadContract
import skillbill.review.model.ReviewFinishedTelemetry
import skillbill.ports.telemetry.model.toReviewFinishedTelemetryPayload as toTelemetryPayload

fun ReviewFinishedTelemetry.toReviewFinishedTelemetryPayload(): JsonPayloadContract = toTelemetryPayload()
