package skillbill.cli

internal val learningsCliCommand: CliCommandNode =
  commandGroup(
    name = "learnings",
    children =
    buildList {
      addAll(learningsQueryCliCommands)
      addAll(learningsMutationCliCommands)
    },
  )
