package skillbill.telemetry.model

enum class TelemetrySyncStatus(val wireValue: String) {
  FAILED("failed"),
  DISABLED("disabled"),
  UNCONFIGURED("unconfigured"),
  NOOP("noop"),
  SYNCED("synced"),
  ;

  companion object {
    fun fromWire(value: String): TelemetrySyncStatus? = entries.firstOrNull { it.wireValue == value }
  }
}
