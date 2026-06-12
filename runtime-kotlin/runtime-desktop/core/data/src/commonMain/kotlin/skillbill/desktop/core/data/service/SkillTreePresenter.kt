package skillbill.desktop.core.data.service

object SkillTreePresenter {
  fun displayLabelForSkill(skillName: String, platform: String, family: String, area: String): String {
    if (platform.isBlank()) {
      return skillName.removePrefix("bill-")
    }
    if (area.isNotBlank()) {
      return area
    }
    if (family.isNotBlank()) {
      return family
    }
    return skillName
      .removePrefix("bill-$platform-")
      .removePrefix("bill-")
  }

  fun nativeAgentOwnerGroupPath(relativePath: String): List<String> {
    val parts = relativePath.split('/')
    if (parts.size >= 4 && parts[0] == "skills") {
      return listOf(parts[1].removePrefix("bill-"))
    }
    if (parts.size >= 5 && parts[0] == "platform-packs") {
      val platform = parts[1]
      val family = parts[2]
      val skillName = parts[3]
      val baselineName = "bill-$platform-$family"
      val areaPrefix = "$baselineName-"
      val area = skillName.removePrefix(areaPrefix).takeIf { skillName.startsWith(areaPrefix) }.orEmpty()
      return listOf(platform, displayLabelForSkill(skillName, platform, family, area))
    }
    return listOf("unowned")
  }

  fun displayLabelForNativeAgent(
    agentName: String,
    relativePath: String,
    groupPath: List<String> = nativeAgentOwnerGroupPath(relativePath),
  ): String {
    val platform = platformFromRelativePath(relativePath)
    var label = agentName.removePrefix("bill-")
    if (platform.isNotBlank()) {
      label = label.removeDisplayPrefix("$platform-")
    }
    groupPath.forEach { groupLabel ->
      label = label.removeDisplayPrefix("$groupLabel-")
    }
    return label
  }

  private fun String.removeDisplayPrefix(prefix: String): String =
    if (startsWith(prefix) && length > prefix.length) removePrefix(prefix) else this

  private fun platformFromRelativePath(relativePath: String): String {
    val parts = relativePath.split('/')
    return if (parts.size >= 2 && parts[0] == "platform-packs") parts[1] else ""
  }
}
