package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.SkillBillVersion
import skillbill.application.telemetry.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.application.telemetry.TelemetryLevelMutationService
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.settings.DefaultTelemetrySettingsProvider

internal interface RuntimeTelemetryProvides {
  @Provides @JvmSynthetic
  fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  @Provides @JvmSynthetic
  fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider = provider

  @Provides @JvmSynthetic
  fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  @Provides @JvmSynthetic
  fun telemetryLevelMutator(service: TelemetryLevelMutationService): TelemetryLevelMutator = service

  @Provides @JvmSynthetic
  fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService): GoalLifecycleTelemetryEmitter = service

  @Provides @JvmSynthetic
  fun skillBillVersion(): String = SkillBillVersion.VALUE
}
