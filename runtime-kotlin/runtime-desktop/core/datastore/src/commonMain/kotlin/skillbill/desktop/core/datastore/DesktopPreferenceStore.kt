package skillbill.desktop.core.datastore

import kotlinx.coroutines.flow.StateFlow

data class DesktopFirstRunPreferences(
  val completed: Boolean = false,
  val selectedAgentIds: Set<String> = emptySet(),
  val selectedPlatformSlugs: Set<String> = emptySet(),
  val telemetryLevelId: String = "anonymous",
  val registerMcp: Boolean = true,
)

interface DesktopPreferenceStore {
  val recentRepoPath: StateFlow<String?>
  val firstRunPreferences: StateFlow<DesktopFirstRunPreferences>

  fun rememberRepoPath(repoPath: String)
  fun clearRecentRepoPath()
  fun saveFirstRunPreferences(preferences: DesktopFirstRunPreferences)
  fun markFirstRunCompleted(preferences: DesktopFirstRunPreferences)
}
