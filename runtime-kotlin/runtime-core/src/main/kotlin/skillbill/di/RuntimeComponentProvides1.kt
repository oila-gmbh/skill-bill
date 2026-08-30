package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.application.telemetry.TelemetryLevelMutationService
import skillbill.infrastructure.fs.FileExternalAddonSourceConfigStore
import skillbill.infrastructure.fs.FileSystemExternalAddonOverlay
import skillbill.infrastructure.fs.FileSystemInstallApplyExecution
import skillbill.infrastructure.fs.FileSystemInstallPlanningFacts
import skillbill.infrastructure.fs.FileSystemInstallPlatformSkillMaterialization
import skillbill.infrastructure.fs.FileSystemInstallStagingIntent
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.telemetry.settings.DefaultTelemetrySettingsProvider

internal interface RuntimeComponentProvides1 {
  @Provides @JvmSynthetic
  fun telemetryConfigStore(store: FileTelemetryConfigStore) = RuntimeComponentBindingsA1.telemetryConfigStore(store)

  @Provides @JvmSynthetic
  fun externalAddonSourceConfigPort(store: FileExternalAddonSourceConfigStore) =
    RuntimeComponentBindingsA1.externalAddonSourceConfigPort(store)

  @Provides @JvmSynthetic
  fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay) =
    RuntimeComponentBindingsA2.externalAddonOverlayPort(adapter)

  @Provides @JvmSynthetic
  fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider) =
    RuntimeComponentBindingsA2.telemetrySettingsProvider(provider)

  @Provides @JvmSynthetic
  fun telemetryClient(client: HttpTelemetryClient) = RuntimeComponentBindingsA2.telemetryClient(client)

  @Provides @JvmSynthetic
  fun telemetryLevelMutator(service: TelemetryLevelMutationService) =
    RuntimeComponentBindingsA2.telemetryLevelMutator(service)

  @Provides @JvmSynthetic
  fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts) =
    RuntimeComponentBindingsA2.installPlanningFactsPort(adapter)

  @Provides @JvmSynthetic
  fun installPlatformSkillMaterializationPort(adapter: FileSystemInstallPlatformSkillMaterialization) =
    RuntimeComponentBindingsA2.installPlatformSkillMaterializationPort(adapter)

  @Provides @JvmSynthetic
  fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent) =
    RuntimeComponentBindingsA2.installStagingIntentPort(adapter)

  @Provides @JvmSynthetic
  fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution) =
    RuntimeComponentBindingsA2.installApplyExecutionPort(adapter)
}
