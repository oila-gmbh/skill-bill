package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.RuntimeContext
import skillbill.application.LearningService
import skillbill.application.ReviewService
import skillbill.application.SystemService
import skillbill.application.TelemetryService
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.http.HttpTelemetryClient
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.ports.persistence.DatabaseSessionFactory
import skillbill.ports.telemetry.TelemetryClient
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.TelemetrySettingsProvider
import skillbill.telemetry.DefaultTelemetrySettingsProvider

@Component
abstract class RuntimeComponent(
  @get:Provides val runtimeContext: RuntimeContext,
) {
  @Provides
  fun databaseSessionFactory(factory: SQLiteDatabaseSessionFactory): DatabaseSessionFactory = factory

  @Provides
  fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  @Provides
  fun telemetrySettingsProvider(provider: DefaultTelemetrySettingsProvider): TelemetrySettingsProvider = provider

  @Provides
  fun telemetryClient(client: HttpTelemetryClient): TelemetryClient = client

  abstract val learningService: LearningService
  abstract val reviewService: ReviewService
  abstract val systemService: SystemService
  abstract val telemetryService: TelemetryService
}
