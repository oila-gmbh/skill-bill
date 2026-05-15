package skillbill.desktop

import skillbill.desktop.core.database.SKILL_BILL_DATABASE_PATH_PROPERTY
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmApplicationComponentTest {
  @Test
  fun `application component creates user and screen component graph`() {
    val databasePath = Files.createTempDirectory("skillbill-desktop-app-di").resolve("skill-bill.db").toString()

    withSystemProperty(SKILL_BILL_DATABASE_PATH_PROPERTY, databasePath) {
      val component = createJvmApplicationComponent()
      val userComponent = component.desktopUserComponentManager.createComponent()
      val screenComponent = userComponent.screenComponentFactory.create()

      assertNotNull(screenComponent.viewModel)
      assertNotNull(screenComponent.browserLauncher)
      assertEquals("No source selected", screenComponent.viewModel.state().editor.title)

      component.databaseProvider.clearCachedInstances()
    }
  }

  private fun withSystemProperty(name: String, value: String, block: () -> Unit) {
    val originalValue = System.getProperty(name)
    System.setProperty(name, value)
    try {
      block()
    } finally {
      if (originalValue == null) {
        System.clearProperty(name)
      } else {
        System.setProperty(name, originalValue)
      }
    }
  }
}
