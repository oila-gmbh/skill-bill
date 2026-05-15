package skillbill.desktop.core.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "recent_repo")
data class RecentRepoEntity(
  @PrimaryKey val repoPath: String,
  val openedAtEpochMillis: Long,
  val openedOrder: Long,
)
