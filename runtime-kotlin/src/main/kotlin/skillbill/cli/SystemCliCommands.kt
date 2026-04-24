package skillbill.cli

import me.tatarka.inject.annotations.Inject
import skillbill.app.SystemService

@Inject
class VersionCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("version", "Show the installed skill-bill version.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.version(), format)
  }
}

@Inject
class DoctorCliCommand(
  private val service: SystemService,
  private val state: CliRunState,
) : DocumentedCliCommand("doctor", "Check skill-bill installation health.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.doctor(state.dbOverride), format)
  }
}
