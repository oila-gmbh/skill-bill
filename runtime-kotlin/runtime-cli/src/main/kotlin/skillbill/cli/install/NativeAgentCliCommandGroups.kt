package skillbill.cli.install

import me.tatarka.inject.annotations.Inject

@Inject
class NativeAgentClaudeCliCommands(
  val link: InstallLinkClaudeAgentsCommand,
  val unlink: InstallUnlinkClaudeAgentsCommand,
)

@Inject
class NativeAgentCodexCliCommands(
  val link: InstallLinkCodexAgentsCommand,
  val unlink: InstallUnlinkCodexAgentsCommand,
)

@Inject
class NativeAgentJunieCliCommands(
  val link: InstallLinkJunieAgentsCommand,
  val unlink: InstallUnlinkJunieAgentsCommand,
)

@Inject
class NativeAgentCursorCliCommands(
  val link: InstallLinkCursorAgentsCommand,
  val unlink: InstallUnlinkCursorAgentsCommand,
)
