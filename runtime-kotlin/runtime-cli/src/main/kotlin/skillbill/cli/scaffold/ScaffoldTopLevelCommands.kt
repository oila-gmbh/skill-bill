package skillbill.cli.scaffold

import com.github.ajalt.clikt.core.CliktCommand
import me.tatarka.inject.annotations.Inject

@Inject
class ScaffoldTopLevelCommands(
  authoringRead: ScaffoldAuthoringReadCliSubcommands,
  authoringWrite: ScaffoldAuthoringWriteCliSubcommands,
  newCommands: ScaffoldNewCliSubcommands,
) {
  val commands: List<CliktCommand> =
    listOf(
      authoringRead.list,
      authoringRead.show,
      authoringRead.explain,
      authoringRead.validate,
      authoringWrite.upgrade,
      authoringWrite.render,
      authoringWrite.edit,
      authoringWrite.fill,
      newCommands.newSkill,
      newCommands.newAlias,
      newCommands.createAndFill,
      newCommands.newAddon,
    )
}
