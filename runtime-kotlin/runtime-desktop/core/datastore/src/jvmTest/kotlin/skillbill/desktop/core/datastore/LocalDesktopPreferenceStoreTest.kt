package skillbill.desktop.core.datastore

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `first run completion persists without rewriting install choices`() {
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
      assertEquals(emptySet(), reloaded.selectedAgentIds)
      assertEquals(emptySet(), reloaded.selectedPlatformSlugs)
      assertEquals("anonymous", reloaded.telemetryLevelId)
      assertTrue(reloaded.registerMcp)
    }
  }

  @Test
  fun `first run completion removes legacy install choices while preserving desktop state`() {
    withTemporaryStorePath { preferencesPath ->
      Files.createDirectories(preferencesPath.parent)
      Files.writeString(
        preferencesPath,
        """
        |recentRepoPath=/repo
        |firstRun.completed=false
        |firstRun.agents=codex,claude
        |firstRun.platforms=kotlin
        |firstRun.telemetry=full
        |firstRun.mcp=false
        |
        """.trimMargin(),
      )
      val store = LocalDesktopPreferenceStore()

      store.markFirstRunCompleted(
        DesktopFirstRunPreferences(
          selectedAgentIds = setOf("codex"),
          selectedPlatformSlugs = setOf("kotlin"),
          telemetryLevelId = "off",
          registerMcp = false,
        ),
      )

      val persisted = Files.readString(preferencesPath)
      assertTrue(persisted.contains("recentRepoPath=/repo"))
      assertTrue(persisted.contains("firstRun.completed=true"))
      assertFalse(persisted.contains("firstRun.agents"))
      assertFalse(persisted.contains("firstRun.platforms"))
      assertFalse(persisted.contains("firstRun.telemetry"))
      assertFalse(persisted.contains("firstRun.mcp"))

      assertTrue(store.firstRunPreferences.value.completed)
      assertEquals(emptySet(), store.firstRunPreferences.value.selectedAgentIds)
      assertEquals(emptySet(), store.firstRunPreferences.value.selectedPlatformSlugs)
      assertEquals("anonymous", store.firstRunPreferences.value.telemetryLevelId)
      assertTrue(store.firstRunPreferences.value.registerMcp)

      val reloaded = LocalDesktopPreferenceStore()
      assertEquals("/repo", reloaded.recentRepoPath.value)
      assertTrue(reloaded.firstRunPreferences.value.completed)
      assertEquals(emptySet(), reloaded.firstRunPreferences.value.selectedAgentIds)
      assertEquals(emptySet(), reloaded.firstRunPreferences.value.selectedPlatformSlugs)
      assertEquals("anonymous", reloaded.firstRunPreferences.value.telemetryLevelId)
      assertTrue(reloaded.firstRunPreferences.value.registerMcp)
    }
  }

  @Test
  fun `legacy first run install choices still load from desktop properties`() {
    withTemporaryStorePath { preferencesPath ->
      Files.createDirectories(preferencesPath.parent)
      Files.writeString(
        preferencesPath,
        """
        |firstRun.completed=true
        |firstRun.agents=codex,claude
        |firstRun.platforms=kotlin
        |firstRun.telemetry=full
        |firstRun.mcp=false
        |
        """.trimMargin(),
      )

      val loaded = LocalDesktopPreferenceStore().firstRunPreferences.value

      assertTrue(loaded.completed)
      assertEquals(setOf("claude", "codex"), loaded.selectedAgentIds)
      assertEquals(setOf("kotlin"), loaded.selectedPlatformSlugs)
      assertEquals("full", loaded.telemetryLevelId)
      assertFalse(loaded.registerMcp)
    }
  }

  private fun withTemporaryStorePath(block: (Path) -> Unit) {
    val originalPath = System.getProperty(PREFERENCES_PATH_PROPERTY)
    val preferencesPath = Files.createTempDirectory("skillbill-desktop-test").resolve("desktop.properties")
    System.setProperty(PREFERENCES_PATH_PROPERTY, preferencesPath.toString())
    try {
      block(preferencesPath)
    } finally {
      if (originalPath == null) {
        System.clearProperty(PREFERENCES_PATH_PROPERTY)
      } else {
        System.setProperty(PREFERENCES_PATH_PROPERTY, originalPath)
      }
    }
  }

  private fun withTemporaryStore(block: () -> Unit) {
    withTemporaryStorePath { block() }
  }

  private companion object {
    const val PREFERENCES_PATH_PROPERTY = "skillbill.desktop.preferences.path"
  }
}
