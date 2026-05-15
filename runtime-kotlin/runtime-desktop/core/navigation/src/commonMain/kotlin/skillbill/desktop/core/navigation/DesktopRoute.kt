package skillbill.desktop.core.navigation

sealed interface DesktopRoute {
  val routeId: String
}

data object SkillBillHomeRoute : DesktopRoute {
  override val routeId: String = "skill-bill-home"
}

data class SkillBillSourceRoute(val sourceId: String) : DesktopRoute {
  override val routeId: String = "skill-bill-source:$sourceId"
}
