package skillbill.desktop.core.datastore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalDesktopPreferenceStore : DesktopPreferenceStore {
  private val preferencesPath: Path = defaultPreferencesPath()
  private val properties = loadProperties()
  private val repoPathState = MutableStateFlow(properties.getProperty(KEY_RECENT_REPO_PATH))

  override val recentRepoPath: StateFlow<String?>
    get() = repoPathState

  override fun rememberRepoPath(repoPath: String) {
    val normalized = repoPath.trim()
    if (normalized.isEmpty()) {
      clearRecentRepoPath()
      return
    }

    properties.setProperty(KEY_RECENT_REPO_PATH, normalized)
    repoPathState.value = normalized
    saveProperties()
  }

  override fun clearRecentRepoPath() {
    properties.remove(KEY_RECENT_REPO_PATH)
    repoPathState.value = null
    saveProperties()
  }

  private fun loadProperties(): Properties {
    val loaded = Properties()
    if (Files.isRegularFile(preferencesPath)) {
      Files.newInputStream(preferencesPath).use(loaded::load)
    }
    return loaded
  }

  private fun saveProperties() {
    preferencesPath.parent?.let(Files::createDirectories)
    Files.newOutputStream(preferencesPath).use { output ->
      properties.store(output, "SkillBill desktop preferences")
    }
  }

  private companion object {
    const val KEY_RECENT_REPO_PATH = "recentRepoPath"
    const val PREFERENCES_PATH_PROPERTY = "skillbill.desktop.preferences.path"

    fun defaultPreferencesPath(): Path = System.getProperty(PREFERENCES_PATH_PROPERTY)?.let(Path::of)
      ?: Path.of(System.getProperty("user.home"), ".skill-bill", "desktop.properties")
  }
}
