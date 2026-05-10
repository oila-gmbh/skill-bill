package skillbill.desktop.core.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import java.io.File

const val SKILL_BILL_DATABASE_FILE_NAME = "skill-bill.db"

fun createSkillBillDatabaseBuilder(
  databaseFileName: String = SKILL_BILL_DATABASE_FILE_NAME,
  path: String = defaultDatabasePath(databaseFileName),
): RoomDatabase.Builder<SkillBillDatabase> = Room.databaseBuilder<SkillBillDatabase>(
  name = path,
  factory = SkillBillDatabaseConstructor::initialize,
).setDriver(JdbcSqliteDriver())

fun defaultDatabasePath(databaseFileName: String): String {
  System.getProperty(SKILL_BILL_DATABASE_PATH_PROPERTY)?.let { return it }
  val appDir = File(System.getProperty("user.home"), ".skill-bill-desktop")
  appDir.mkdirs()
  return File(appDir, databaseFileName).absolutePath
}

const val SKILL_BILL_DATABASE_PATH_PROPERTY = "skillbill.desktop.database.path"
