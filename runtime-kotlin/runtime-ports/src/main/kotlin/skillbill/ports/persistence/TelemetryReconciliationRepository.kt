package skillbill.ports.persistence

import skillbill.ports.persistence.model.TelemetryReconciliationRequest
import skillbill.ports.persistence.model.TelemetryReconciliationResult

interface TelemetryReconciliationRepository {
  fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult

  fun reconcileStaleSessions(level: String): TelemetryReconciliationResult =
    reconcileStaleSessions(TelemetryReconciliationRequest(level = level))
}
