package skillbill.desktop.core.datastore

import kotlinx.coroutines.flow.StateFlow

interface DesktopPreferenceStore {
  val recentRepoPath: StateFlow<String?>

  fun rememberRepoPath(repoPath: String)
  fun clearRecentRepoPath()
}
