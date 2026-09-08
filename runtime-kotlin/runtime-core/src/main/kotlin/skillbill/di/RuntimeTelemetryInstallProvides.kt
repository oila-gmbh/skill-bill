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

internal interface RuntimeTelemetryInstallProvides {
  @Provides @JvmSynthetic
  fun telemetryConfigStore(store: FileTelemetryConfigStore) = RuntimeBootstrapBindings.telemetryConfigStore(store)

  @Provides @JvmSynthetic
  fun externalAddonSourceConfigPort(store: FileExternalAddonSourceConfigStore) =
    RuntimeBootstrapBindings.externalAddonSourceConfigPort(store)

  @Provides @JvmSynthetic
  fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay) =
    RuntimeTelemetryInstallBindings.externalAddonOverlayPort(adapter)

  @Provides @JvmSynthetic
  fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider) =
    RuntimeTelemetryInstallBindings.telemetrySettingsProvider(provider)

  @Provides @JvmSynthetic
  fun telemetryClient(client: HttpTelemetryClient) = RuntimeTelemetryInstallBindings.telemetryClient(client)

  @Provides @JvmSynthetic
  fun telemetryLevelMutator(service: TelemetryLevelMutationService) =
    RuntimeTelemetryInstallBindings.telemetryLevelMutator(service)

  @Provides @JvmSynthetic
  fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts) =
    RuntimeTelemetryInstallBindings.installPlanningFactsPort(adapter)

  @Provides @JvmSynthetic
  fun installPlatformSkillMaterializationPort(adapter: FileSystemInstallPlatformSkillMaterialization) =
    RuntimeTelemetryInstallBindings.installPlatformSkillMaterializationPort(adapter)

  @Provides @JvmSynthetic
  fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent) =
    RuntimeTelemetryInstallBindings.installStagingIntentPort(adapter)

  @Provides @JvmSynthetic
  fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution) =
    RuntimeTelemetryInstallBindings.installApplyExecutionPort(adapter)
}
