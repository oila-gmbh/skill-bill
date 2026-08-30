package skillbill.cli.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.cli.install.InstallLinkClaudeAgentsCommand
import skillbill.cli.install.InstallLinkCodexAgentsCommand
import skillbill.cli.install.InstallLinkCursorAgentsCommand
import skillbill.cli.install.InstallLinkJunieAgentsCommand
import skillbill.cli.install.InstallUnlinkClaudeAgentsCommand
import skillbill.cli.install.InstallUnlinkCodexAgentsCommand
import skillbill.cli.install.InstallUnlinkCursorAgentsCommand
import skillbill.cli.install.InstallUnlinkJunieAgentsCommand

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
