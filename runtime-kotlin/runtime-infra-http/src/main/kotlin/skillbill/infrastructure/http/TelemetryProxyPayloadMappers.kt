package skillbill.infrastructure.http

import skillbill.contracts.JsonCodec
import skillbill.contracts.telemetry.TelemetryProxyBatchEvent
import skillbill.contracts.telemetry.TelemetryProxyBatchPayload
import skillbill.ports.telemetry.model.TelemetryOutboxRecord
import skillbill.telemetry.model.TelemetrySettings

fun telemetryProxyBatchPayload(
  settings: TelemetrySettings,
  rows: List<TelemetryOutboxRecord>,
): TelemetryProxyBatchPayload = TelemetryProxyBatchPayload(
  batch =
  rows.map { row ->
    TelemetryProxyBatchEvent(
      event = row.eventName,
      distinctId = settings.installId,
      properties = telemetryProperties(row.payloadJson, settings.installId, row.skillBillVersion),
      timestamp = row.createdAt,
    )
  },
)

private fun telemetryProperties(
  payloadJson: String,
  installId: String,
  skillBillVersion: String?,
): MutableMap<String, Any?> = (
  JsonCodec.parseObjectOrNull(payloadJson)?.let {
    JsonCodec.anyToStringAnyMap(JsonCodec.jsonElementToValue(it))
  } ?: emptyMap()
  ).toMutableMap().apply {
  this["install_id"] = installId
  this["\$process_person_profile"] = false
  // A row enqueued before release attribution existed carries no version; the property is omitted
  // rather than sent as a sentinel, and the row still uploads.
  if (!skillBillVersion.isNullOrBlank()) {
    this["skill_bill_version"] = skillBillVersion
  }
}
