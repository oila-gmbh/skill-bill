package skillbill.desktop.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(
  entities = [RecentRepoEntity::class],
  version = 1,
  exportSchema = true,
)
@ConstructedBy(SkillBillDatabaseConstructor::class)
abstract class SkillBillDatabase : RoomDatabase() {
  abstract fun recentRepoDao(): RecentRepoDao
}

@Suppress("KotlinNoActualForExpect")
expect object SkillBillDatabaseConstructor : RoomDatabaseConstructor<SkillBillDatabase> {
  override fun initialize(): SkillBillDatabase
}

fun createSkillBillDatabase(builder: RoomDatabase.Builder<SkillBillDatabase>): SkillBillDatabase = builder.build()
