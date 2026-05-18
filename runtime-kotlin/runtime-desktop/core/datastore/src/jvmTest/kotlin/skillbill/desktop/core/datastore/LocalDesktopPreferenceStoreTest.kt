package skillbill.desktop.core.datastore

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDesktopPreferenceStoreTest {
  @Test
  fun `remembering blank path clears recent repo preference`() {
    withTemporaryStore {
      val store = LocalDesktopPreferenceStore()

      store.rememberRepoPath("/repo")
      store.rememberRepoPath(" ")

      assertNull(store.recentRepoPath.value)
    }
  }

  @Test
  fun `remembering repo path trims user input`() {
    withTemporaryStore {
      val store = LocalDesktopPreferenceStore()

      store.rememberRepoPath(" /repo ")

      assertEquals("/repo", store.recentRepoPath.value)
    }
  }

  @Test
  fun `remembering repo path supports filename only preference path`() {
    val originalPath = System.getProperty(PREFERENCES_PATH_PROPERTY)
    val originalUserDir = System.getProperty("user.dir")
    val workingDirectory = Files.createTempDirectory("skillbill-desktop-flat-test")
    System.setProperty(PREFERENCES_PATH_PROPERTY, "desktop.properties")
    System.setProperty("user.dir", workingDirectory.toString())
    try {
      val store = LocalDesktopPreferenceStore()

      store.rememberRepoPath("/repo")

      assertEquals("/repo", store.recentRepoPath.value)
    } finally {
      Files.deleteIfExists(Path.of("desktop.properties"))
      if (originalPath == null) {
        System.clearProperty(PREFERENCES_PATH_PROPERTY)
      } else {
        System.setProperty(PREFERENCES_PATH_PROPERTY, originalPath)
      }
      System.setProperty("user.dir", originalUserDir)
    }
  }

  @Test
  fun `first run preferences persist to desktop properties`() {
    withTemporaryStore {
      val store = LocalDesktopPreferenceStore()

      store.markFirstRunCompleted(
        DesktopFirstRunPreferences(
          selectedAgentIds = setOf("codex", "claude"),
          selectedPlatformSlugs = setOf("kotlin"),
          telemetryLevelId = "full",
          registerMcp = false,
        ),
      )

      val reloaded = LocalDesktopPreferenceStore().firstRunPreferences.value
      assertTrue(reloaded.completed)
      assertEquals(setOf("claude", "codex"), reloaded.selectedAgentIds)
      assertEquals(setOf("kotlin"), reloaded.selectedPlatformSlugs)
      assertEquals("full", reloaded.telemetryLevelId)
      assertTrue(reloaded.registerMcp)
    }
  }

  private fun withTemporaryStore(block: () -> Unit) {
    val originalPath = System.getProperty(PREFERENCES_PATH_PROPERTY)
    val preferencesPath = Files.createTempDirectory("skillbill-desktop-test").resolve("desktop.properties")
    System.setProperty(PREFERENCES_PATH_PROPERTY, preferencesPath.toString())
    try {
      block()
    } finally {
      if (originalPath == null) {
        System.clearProperty(PREFERENCES_PATH_PROPERTY)
      } else {
        System.setProperty(PREFERENCES_PATH_PROPERTY, originalPath)
      }
    }
  }

  private companion object {
    const val PREFERENCES_PATH_PROPERTY = "skillbill.desktop.preferences.path"
  }
}
