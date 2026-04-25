package skillbill.infrastructure.http

import skillbill.contracts.JsonSupport
import skillbill.contracts.telemetry.TelemetryProxyBatchEvent
import skillbill.contracts.telemetry.TelemetryProxyBatchPayload
import skillbill.ports.persistence.TelemetryOutboxRecord
import skillbill.telemetry.TelemetrySettings

fun telemetryProxyBatchPayload(
  settings: TelemetrySettings,
  rows: List<TelemetryOutboxRecord>,
): TelemetryProxyBatchPayload = TelemetryProxyBatchPayload(
  batch =
  rows.map { row ->
    TelemetryProxyBatchEvent(
      event = row.eventName,
      distinctId = settings.installId,
      properties = telemetryProperties(row.payloadJson, settings.installId),
      timestamp = row.createdAt,
    )
  },
)

private fun telemetryProperties(payloadJson: String, installId: String): MutableMap<String, Any?> = (
  JsonSupport.parseObjectOrNull(payloadJson)?.let {
    JsonSupport.anyToStringAnyMap(JsonSupport.jsonElementToValue(it))
  } ?: emptyMap()
  ).toMutableMap().apply {
  this["install_id"] = installId
  this["\$process_person_profile"] = false
}
