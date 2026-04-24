package skillbill.cli

internal val telemetryCliCommand: CliCommandNode =
  commandGroup(
    name = "telemetry",
    children =
    buildList {
      addAll(telemetryLocalCliCommands)
      addAll(telemetryRemoteCliCommands)
    },
  )
