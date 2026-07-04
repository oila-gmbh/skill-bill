package skillbill.desktop.feature.skillbill.ui

fun chooseRepoDirectory(initialPath: String): String? =
  chooseDirectory(initialPath, title = "Open Skill Bill Repository")

expect fun chooseDirectory(initialPath: String, title: String): String?
