package skillbill.desktop.core.database

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LocalDesktopDatabaseProvider : DesktopDatabaseProvider {
  private val lock = Any()
  private var database: SkillBillDatabase? = null

  override fun provideDatabase(): SkillBillDatabase = synchronized(lock) {
    database ?: createSkillBillDatabase(createSkillBillDatabaseBuilder()).also { database = it }
  }

  override fun clearCachedInstances() {
    synchronized(lock) {
      database?.close()
      database = null
    }
  }

  override fun deleteDatabaseFile() {
    clearCachedInstances()
    File(defaultDatabasePath(SKILL_BILL_DATABASE_FILE_NAME)).delete()
  }
}
