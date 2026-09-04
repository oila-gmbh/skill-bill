package skillbill.di

import skillbill.application.telemetry.TelemetryLevelMutationService
import skillbill.infrastructure.fs.FileSystemExternalAddonOverlay
import skillbill.infrastructure.fs.FileSystemInstallApplyExecution
import skillbill.infrastructure.fs.FileSystemInstallPlanningFacts
import skillbill.infrastructure.fs.FileSystemInstallPlatformSkillMaterialization
import skillbill.infrastructure.fs.FileSystemInstallStagingIntent
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.ports.install.addon.ExternalAddonOverlayPort
import skillbill.ports.install.apply.InstallApplyExecutionPort
import skillbill.ports.install.plan.InstallPlanningFactsPort
import skillbill.ports.install.plan.InstallPlatformSkillMaterializationPort
import skillbill.ports.install.plan.InstallStagingIntentPort
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.settings.DefaultTelemetrySettingsProvider

internal object RuntimeTelemetryInstallBindings {
  internal fun externalAddonOverlayPort(adapter: FileSystemExternalAddonOverlay): ExternalAddonOverlayPort = adapter

  internal fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider =
    provider

  internal fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  internal fun telemetryLevelMutator(service: TelemetryLevelMutationService): TelemetryLevelMutator = service

  internal fun installPlanningFactsPort(adapter: FileSystemInstallPlanningFacts): InstallPlanningFactsPort = adapter

  internal fun installPlatformSkillMaterializationPort(
    adapter: FileSystemInstallPlatformSkillMaterialization,
  ): InstallPlatformSkillMaterializationPort = adapter

  internal fun installStagingIntentPort(adapter: FileSystemInstallStagingIntent): InstallStagingIntentPort = adapter

  internal fun installApplyExecutionPort(adapter: FileSystemInstallApplyExecution): InstallApplyExecutionPort = adapter
}
