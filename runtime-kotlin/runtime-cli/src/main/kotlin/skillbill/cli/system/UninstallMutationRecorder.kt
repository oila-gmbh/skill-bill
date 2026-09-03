package skillbill.cli.system

import skillbill.ports.diagnostics.RuntimeDiagnostics

/**
 * Single failure policy for every `uninstall` mutation. A mutation that could not be applied is a
 * degradation: it is recorded through [RuntimeDiagnostics] and it fails the command's outcome, so a
 * partial uninstall can never report success with the failure buried in a payload field.
 */
internal class UninstallMutationRecorder(private val diagnostics: RuntimeDiagnostics) {
  private val failures = mutableListOf<String>()

  fun recordFailure(description: String, error: Throwable) {
    diagnostics.error("uninstall mutation failed: $description", error)
    failures += "$description: ${error.message.orEmpty()}"
  }

  fun failureMessages(): List<String> = failures.toList()

  fun failed(): Boolean = failures.isNotEmpty()
}
