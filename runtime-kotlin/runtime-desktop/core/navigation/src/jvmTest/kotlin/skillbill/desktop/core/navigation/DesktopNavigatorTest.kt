package skillbill.desktop.core.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNavigatorTest {
  @Test
  fun `navigate pushes typed routes and goBack pops within stack`() {
    val navigator = DesktopNavigator()

    navigator.navigate(SkillBillSourceRoute("skill-one"))

    assertEquals(SkillBillSourceRoute("skill-one"), navigator.state.value.currentRoute)
    assertEquals(listOf(SkillBillHomeRoute, SkillBillSourceRoute("skill-one")), navigator.state.value.backStack)
    assertTrue(navigator.goBack())
    assertEquals(SkillBillHomeRoute, navigator.state.value.currentRoute)
  }

  @Test
  fun `root route cannot be popped`() {
    val navigator = DesktopNavigator()

    assertFalse(navigator.goBack())
    assertEquals(listOf(SkillBillHomeRoute), navigator.state.value.backStack)
  }

  @Test
  fun `replaceRoot clears previous stack`() {
    val navigator = DesktopNavigator()
    navigator.navigate(SkillBillSourceRoute("skill-one"))

    navigator.replaceRoot(SkillBillSourceRoute("skill-two"))

    assertEquals(listOf(SkillBillSourceRoute("skill-two")), navigator.state.value.backStack)
  }

  @Test
  fun `navigating to current route does not duplicate back stack entry`() {
    val navigator = DesktopNavigator()
    navigator.navigate(SkillBillSourceRoute("skill-one"))

    navigator.navigate(SkillBillSourceRoute("skill-one"))

    assertEquals(listOf(SkillBillHomeRoute, SkillBillSourceRoute("skill-one")), navigator.state.value.backStack)
  }
}
