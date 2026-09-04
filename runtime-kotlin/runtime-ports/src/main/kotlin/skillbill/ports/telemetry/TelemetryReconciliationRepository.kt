package skillbill.ports.telemetry

import skillbill.ports.telemetry.model.TelemetryReconciliationRequest
import skillbill.ports.telemetry.model.TelemetryReconciliationResult

interface TelemetryReconciliationRepository {
  fun reconcileStaleSessions(request: TelemetryReconciliationRequest): TelemetryReconciliationResult
}
