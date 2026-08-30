package skillbill.ports.telemetry

import skillbill.telemetry.model.PrDescriptionGeneratedRecord

interface PrDescriptionLifecycleTelemetryRepository {
  fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String)
}
