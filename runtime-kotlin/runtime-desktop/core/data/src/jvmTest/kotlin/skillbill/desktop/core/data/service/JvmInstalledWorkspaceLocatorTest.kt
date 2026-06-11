package skillbill.desktop.core.data.service

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmInstalledWorkspaceLocatorTest {
  @Test
  fun `skills directory marks workspace available`() {
    val home = Files.createTempDirectory("skillbill-workspace-skills")
    Files.createDirectories(home.resolve(".skill-bill/skills"))
    val locator = JvmInstalledWorkspaceLocator().apply {
      homeProvider = { home }
    }

    val result = locator.locate()

    assertTrue(result.availability)
    assertEquals(expectedPath(home), result.path)
  }

  @Test
  fun `platform packs directory marks workspace available`() {
    val home = Files.createTempDirectory("skillbill-workspace-packs")
    Files.createDirectories(home.resolve(".skill-bill/platform-packs"))
    val locator = JvmInstalledWorkspaceLocator().apply {
      homeProvider = { home }
    }

    val result = locator.locate()

    assertTrue(result.availability)
    assertEquals(expectedPath(home), result.path)
  }

  @Test
  fun `a regular file named skills does not mark workspace available`() {
    val home = Files.createTempDirectory("skillbill-workspace-file")
    Files.createDirectories(home.resolve(".skill-bill"))
    Files.createFile(home.resolve(".skill-bill/skills"))
    val locator = JvmInstalledWorkspaceLocator().apply {
      homeProvider = { home }
    }

    assertFalse(locator.locate().availability)
  }

  @Test
  fun `missing skill-bill directory is unavailable without throwing`() {
    val home = Files.createTempDirectory("skillbill-workspace-missing")
    val locator = JvmInstalledWorkspaceLocator().apply {
      homeProvider = { home }
    }

    val result = locator.locate()

    assertFalse(result.availability)
    assertEquals(expectedPath(home), result.path)
  }

  @Test
  fun `empty skill-bill directory is unavailable without throwing`() {
    val home = Files.createTempDirectory("skillbill-workspace-empty")
    Files.createDirectories(home.resolve(".skill-bill"))
    val locator = JvmInstalledWorkspaceLocator().apply {
      homeProvider = { home }
    }

    val result = locator.locate()

    assertFalse(result.availability)
    assertEquals(expectedPath(home), result.path)
  }

  private fun expectedPath(home: java.nio.file.Path): String =
    home.resolve(".skill-bill").toAbsolutePath().normalize().toString()
}
