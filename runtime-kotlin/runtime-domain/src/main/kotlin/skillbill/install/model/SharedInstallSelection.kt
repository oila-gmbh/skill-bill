package skillbill.install.model

data class SharedInstallSelection(
  val selectedAgents: Set<InstallAgent>,
  val platformPackSelection: PlatformPackSelection,
  val telemetryLevel: InstallTelemetryLevel,
  val mcpRegistrationChoice: McpRegistrationChoice,
)
