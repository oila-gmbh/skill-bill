package skillbill.desktop.core.data.repository

import kotlinx.coroutines.runBlocking
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.database.DesktopDatabaseProvider
import skillbill.desktop.core.database.RecentRepoEntity
import skillbill.desktop.core.domain.service.RecentRepoRepository
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(UserScope::class)
@ContributesBinding(UserScope::class)
class RoomRecentRepoRepository(private val databaseProvider: DesktopDatabaseProvider) : RecentRepoRepository {
  override fun recentRepoPath(): String? = runBlocking {
    databaseProvider.provideDatabase().recentRepoDao().latest()?.repoPath
  }

  override fun rememberRepoPath(repoPath: String) {
    val normalized = repoPath.trim()
    if (normalized.isEmpty()) {
      clearRecentRepoPath()
      return
    }

    runBlocking {
      databaseProvider.provideDatabase().recentRepoDao().upsert(
        RecentRepoEntity(
          repoPath = normalized,
          openedAtEpochMillis = System.currentTimeMillis(),
          openedOrder = System.nanoTime(),
        ),
      )
    }
  }

  override fun clearRecentRepoPath() {
    runBlocking {
      databaseProvider.provideDatabase().recentRepoDao().clear()
    }
  }
}
