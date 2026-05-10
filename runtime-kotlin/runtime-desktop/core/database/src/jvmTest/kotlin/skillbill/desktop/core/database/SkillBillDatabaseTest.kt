package skillbill.desktop.core.database

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkillBillDatabaseTest {
  @Test
  fun `recent repo dao upserts and reads latest repo`() = runTest {
    val databasePath = Files.createTempDirectory("skill-bill-room3").resolve("skill-bill.db").toString()
    val database = createSkillBillDatabase(createSkillBillDatabaseBuilder(path = databasePath))

    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/older", openedAtEpochMillis = 100, openedOrder = 1))
    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/newer", openedAtEpochMillis = 200, openedOrder = 2))

    assertEquals("/newer", database.recentRepoDao().latest()?.repoPath)
    assertEquals(listOf("/newer", "/older"), database.recentRepoDao().all().map(RecentRepoEntity::repoPath))

    database.close()
  }

  @Test
  fun `recent repo dao clears persisted repo rows`() = runTest {
    val databasePath = Files.createTempDirectory("skill-bill-room3-clear").resolve("skill-bill.db").toString()
    val database = createSkillBillDatabase(createSkillBillDatabaseBuilder(path = databasePath))

    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/repo", openedAtEpochMillis = 100, openedOrder = 1))
    database.recentRepoDao().clear()

    assertNull(database.recentRepoDao().latest())

    database.close()
  }

  @Test
  fun `recent repo dao upsert updates existing repo timestamp and order`() = runTest {
    val databasePath = Files.createTempDirectory("skill-bill-room3-upsert").resolve("skill-bill.db").toString()
    val database = createSkillBillDatabase(createSkillBillDatabaseBuilder(path = databasePath))

    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/repo", openedAtEpochMillis = 100, openedOrder = 1))
    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/repo", openedAtEpochMillis = 300, openedOrder = 3))

    assertEquals(listOf("/repo"), database.recentRepoDao().all().map(RecentRepoEntity::repoPath))
    assertEquals(300, database.recentRepoDao().latest()?.openedAtEpochMillis)
    assertEquals(3, database.recentRepoDao().latest()?.openedOrder)

    database.close()
  }

  @Test
  fun `recent repo dao breaks timestamp ties by write order`() = runTest {
    val databasePath = Files.createTempDirectory("skill-bill-room3-order").resolve("skill-bill.db").toString()
    val database = createSkillBillDatabase(createSkillBillDatabaseBuilder(path = databasePath))

    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/first", openedAtEpochMillis = 100, openedOrder = 1))
    database.recentRepoDao().upsert(RecentRepoEntity(repoPath = "/second", openedAtEpochMillis = 100, openedOrder = 2))

    assertEquals("/second", database.recentRepoDao().latest()?.repoPath)

    database.close()
  }
}
