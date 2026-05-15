package skillbill.desktop.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface RecentRepoDao {
  @Query("SELECT * FROM recent_repo ORDER BY openedAtEpochMillis DESC, openedOrder DESC LIMIT 1")
  suspend fun latest(): RecentRepoEntity?

  @Query("SELECT * FROM recent_repo ORDER BY openedAtEpochMillis DESC, openedOrder DESC")
  suspend fun all(): List<RecentRepoEntity>

  @Transaction
  suspend fun upsert(entity: RecentRepoEntity) {
    if (insert(entity) == INSERT_CONFLICT) {
      update(entity.repoPath, entity.openedAtEpochMillis, entity.openedOrder)
    }
  }

  @Query("DELETE FROM recent_repo")
  suspend fun clear()

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insert(entity: RecentRepoEntity): Long

  @Query(
    """
      UPDATE recent_repo
      SET openedAtEpochMillis = :openedAtEpochMillis,
        openedOrder = :openedOrder
      WHERE repoPath = :repoPath
    """,
  )
  suspend fun update(repoPath: String, openedAtEpochMillis: Long, openedOrder: Long)

  private companion object {
    const val INSERT_CONFLICT = -1L
  }
}
