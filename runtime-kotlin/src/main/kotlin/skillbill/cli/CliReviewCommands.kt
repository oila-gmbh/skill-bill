package skillbill.cli

internal val reviewCliCommands: List<CliCommandNode> =
  buildList {
    addAll(reviewImportFeedbackCliCommands)
    add(triageCliCommand)
    addAll(reviewStatsCliCommands)
  }
