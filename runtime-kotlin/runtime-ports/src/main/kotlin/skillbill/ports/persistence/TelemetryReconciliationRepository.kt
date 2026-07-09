package skillbill.ports.persistence

import skillbill.ports.persistence.model.TelemetryReconciliationResult
import skillbill.ports.persistence.model.TelemetryReconciliationRequest

interface TelemetryReconciliationRepository {
  fun reconcileStaleSessions(level: String): TelemetryReconciliationResult

  fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult =
    reconcileStaleSessions(request.level)
}
