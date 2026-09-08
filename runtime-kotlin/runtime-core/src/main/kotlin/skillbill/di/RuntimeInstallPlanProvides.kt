package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileExternalAddonSourceConfigStore
import skillbill.infrastructure.fs.FileSystemExternalAddonOverlay
import skillbill.infrastructure.fs.FileSystemInstallApplyExecution
import skillbill.infrastructure.fs.FileSystemInstallPlanningFacts
import skillbill.infrastructure.fs.FileSystemInstallPlatformSkillMaterialization
import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.infrastructure.fs.FileSystemInstallStagingIntent
import skillbill.infrastructure.fs.FileSystemUninstallFileSystemGateway
import skillbill.infrastructure.fs.InstallPlanWireValidatorAdapter
import skillbill.install.model.InstallPlanWireValidator
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.skillremove.SkillRemoveFileSystem
import skillbill.ports.system.UninstallPathsPort
import skillbill.skillremove.FileSystemSkillRemoveFileSystem

internal interface RuntimeInstallPlanProvides {
  @Provides @JvmSynthetic
  fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts): InstallPlanningFactsPort = adapter

  @Provides @JvmSynthetic
  fun installPlatformSkillMaterializationPort(
    adapter: FileSystemInstallPlatformSkillMaterialization,
  ): InstallPlatformSkillMaterializationPort = adapter

  @Provides @JvmSynthetic
  fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent): InstallStagingIntentPort = adapter

  @Provides @JvmSynthetic
  fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution): InstallApplyExecutionPort = adapter

  @Provides @JvmSynthetic
  fun installSelectionPersistencePort(adapter: FileSystemInstallSelectionPersistence): InstallSelectionPersistencePort =
    adapter

  @Provides @JvmSynthetic
  fun installPlanWireValidator(adapter: InstallPlanWireValidatorAdapter): InstallPlanWireValidator = adapter

  @Provides @JvmSynthetic
  fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay): ExternalAddonOverlayPort = adapter

  @Provides @JvmSynthetic
  fun externalAddonSourceConfigPort(store: FileExternalAddonSourceConfigStore): ExternalAddonSourceConfigPort = store

  @Provides @JvmSynthetic
  fun uninstallPathsPort(gateway: FileSystemUninstallFileSystemGateway): UninstallPathsPort = gateway

  @Provides @JvmSynthetic
  fun skillRemoveFileSystem(fileSystem: FileSystemSkillRemoveFileSystem): SkillRemoveFileSystem = fileSystem
}
