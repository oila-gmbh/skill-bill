package skillbill.ports.idestatus

object NoopIdeStatusValidator : IdeStatusValidator {
  override fun validate(snapshot: Map<String, Any?>, sourceLabel: String) {
  }
}
