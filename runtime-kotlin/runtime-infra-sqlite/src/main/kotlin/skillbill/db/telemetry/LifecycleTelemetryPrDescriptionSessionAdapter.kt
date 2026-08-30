package skillbill.db.telemetry

import skillbill.ports.telemetry.PrDescriptionLifecycleTelemetryRepository
import skillbill.telemetry.model.PrDescriptionGeneratedRecord
import java.sql.Connection

internal class LifecycleTelemetryPrDescriptionSessionAdapter(
  private val connection: Connection,
) : PrDescriptionLifecycleTelemetryRepository {
  override fun prDescriptionGenerated(record: PrDescriptionGeneratedRecord, level: String) {
    enqueueTelemetry(connection, "skillbill_pr_description_generated", prDescriptionPayload(record, level))
  }
}
