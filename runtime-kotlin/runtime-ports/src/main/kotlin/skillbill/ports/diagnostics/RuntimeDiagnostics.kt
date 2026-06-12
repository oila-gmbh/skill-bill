package skillbill.ports.diagnostics

interface RuntimeDiagnostics {
  fun warning(message: String, error: Throwable? = null)

  fun error(message: String, error: Throwable? = null)
}

object NoopRuntimeDiagnostics : RuntimeDiagnostics {
  override fun warning(message: String, error: Throwable?) = Unit

  override fun error(message: String, error: Throwable?) = Unit
}
