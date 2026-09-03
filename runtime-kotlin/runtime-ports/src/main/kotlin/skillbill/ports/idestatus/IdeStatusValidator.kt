package skillbill.ports.idestatus

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics

interface IdeStatusValidator {
  @OpenBoundaryMap("IDE status snapshot wire map at the schema-validation seam")
  fun validate(snapshot: Map<String, Any?>, sourceLabel: String)
}

object NoopIdeStatusValidator : IdeStatusValidator {
  override fun validate(snapshot: Map<String, Any?>, sourceLabel: String) {
    RecordingNullObjectDiagnostics.recordSwallow("NoopIdeStatusValidator", "validate(sourceLabel=$sourceLabel)")
  }
}
