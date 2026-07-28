package skillbill.ports.persistence.model

sealed interface LegacyReconciliation {
  data class Imported(val recordCount: Int) : LegacyReconciliation
  data object AlreadyImported : LegacyReconciliation
  data class Quarantined(val reasonCode: String) : LegacyReconciliation
}
