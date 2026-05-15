package skillbill.desktop.core.navigation

data class DesktopNavigationState(
  val backStack: List<DesktopRoute> = listOf(SkillBillHomeRoute),
) {
  init {
    require(backStack.isNotEmpty()) { "Desktop navigation back stack must not be empty." }
  }

  val currentRoute: DesktopRoute
    get() = backStack.last()

  val canGoBack: Boolean
    get() = backStack.size > 1
}
