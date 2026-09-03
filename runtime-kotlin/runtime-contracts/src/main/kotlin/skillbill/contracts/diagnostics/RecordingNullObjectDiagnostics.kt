package skillbill.contracts.diagnostics

object RecordingNullObjectDiagnostics {
  @Volatile
  private var warningSink: (String, Throwable?) -> Unit = { _, _ -> }

  fun bind(warningSink: (String, Throwable?) -> Unit) {
    this.warningSink = warningSink
  }

  fun recordSwallow(nullObject: String, operation: String, error: Throwable? = null) {
    warningSink("Discarded call on recording null object '$nullObject': $operation.", error)
  }

  fun resetBindingForTests() {
    warningSink = { _, _ -> }
  }
}
