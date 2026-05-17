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
  private val firstRunState = MutableStateFlow(loadFirstRunPreferences())

  override val recentRepoPath: StateFlow<String?>
    get() = repoPathState

  override val firstRunPreferences: StateFlow<DesktopFirstRunPreferences>
    get() = firstRunState

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

  override fun saveFirstRunPreferences(preferences: DesktopFirstRunPreferences) {
    persistFirstRunPreferences(preferences)
  }

  override fun markFirstRunCompleted(preferences: DesktopFirstRunPreferences) {
    persistFirstRunPreferences(preferences.copy(completed = true))
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

  private fun loadFirstRunPreferences(): DesktopFirstRunPreferences = DesktopFirstRunPreferences(
    completed = properties.getProperty(KEY_FIRST_RUN_COMPLETED)?.toBooleanStrictOrNull() ?: false,
    selectedAgentIds = properties.getProperty(KEY_FIRST_RUN_AGENTS).toSetProperty(),
    selectedPlatformSlugs = properties.getProperty(KEY_FIRST_RUN_PLATFORMS).toSetProperty(),
    telemetryLevelId = properties.getProperty(KEY_FIRST_RUN_TELEMETRY, "anonymous"),
    registerMcp = properties.getProperty(KEY_FIRST_RUN_MCP)?.toBooleanStrictOrNull() ?: true,
  )

  private fun persistFirstRunPreferences(preferences: DesktopFirstRunPreferences) {
    properties.setProperty(KEY_FIRST_RUN_COMPLETED, preferences.completed.toString())
    properties.setProperty(KEY_FIRST_RUN_AGENTS, preferences.selectedAgentIds.toPropertyValue())
    properties.setProperty(KEY_FIRST_RUN_PLATFORMS, preferences.selectedPlatformSlugs.toPropertyValue())
    properties.setProperty(KEY_FIRST_RUN_TELEMETRY, preferences.telemetryLevelId)
    properties.setProperty(KEY_FIRST_RUN_MCP, preferences.registerMcp.toString())
    firstRunState.value = preferences
    saveProperties()
  }

  private companion object {
    const val KEY_RECENT_REPO_PATH = "recentRepoPath"
    const val KEY_FIRST_RUN_COMPLETED = "firstRun.completed"
    const val KEY_FIRST_RUN_AGENTS = "firstRun.agents"
    const val KEY_FIRST_RUN_PLATFORMS = "firstRun.platforms"
    const val KEY_FIRST_RUN_TELEMETRY = "firstRun.telemetry"
    const val KEY_FIRST_RUN_MCP = "firstRun.mcp"
    const val PREFERENCES_PATH_PROPERTY = "skillbill.desktop.preferences.path"

    fun defaultPreferencesPath(): Path = System.getProperty(PREFERENCES_PATH_PROPERTY)?.let(Path::of)
      ?: Path.of(System.getProperty("user.home"), ".skill-bill", "desktop.properties")
  }
}

private fun String?.toSetProperty(): Set<String> = this
  ?.split(',')
  ?.map(String::trim)
  ?.filter(String::isNotEmpty)
  ?.toSet()
  .orEmpty()

private fun Set<String>.toPropertyValue(): String = sorted().joinToString(",")
