package skillbill.cli.scaffold

import me.tatarka.inject.annotations.Inject

@Inject
class ScaffoldAuthoringReadCliSubcommands(
  val list: ListSkillsCommand,
  val show: ShowSkillCommand,
  val explain: ExplainSkillCommand,
  val validate: ValidateSkillCommand,
)

@Inject
class ScaffoldAuthoringWriteCliSubcommands(
  val upgrade: UpgradeSkillsCommand,
  val render: RenderSkillsCommand,
  val edit: EditSkillCommand,
  val fill: FillSkillCommand,
)

@Inject
class ScaffoldNewCliSubcommands(
  val newSkill: NewSkillCommand,
  val newAlias: NewCommand,
  val createAndFill: CreateAndFillCommand,
  val newAddon: NewAddonCommand,
)
