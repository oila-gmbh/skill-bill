package skillbill.ports.persistence

import skillbill.ports.persistence.model.TelemetryReconciliationResult

interface TelemetryReconciliationRepository {
  fun reconcileStaleSessions(level: String): TelemetryReconciliationResult
}
