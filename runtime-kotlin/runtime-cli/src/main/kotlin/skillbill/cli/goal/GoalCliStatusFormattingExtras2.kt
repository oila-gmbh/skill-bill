package skillbill.cli.goal

internal fun String.goalCliToken(): String = replace("\\", "\\\\")
  .replace("\t", "\\t")
  .replace(" ", "\\s")
