package skillbill.desktop.core.database

interface DesktopDatabaseProvider {
  fun provideDatabase(): SkillBillDatabase
  fun clearCachedInstances()
  fun deleteDatabaseFile()
}
