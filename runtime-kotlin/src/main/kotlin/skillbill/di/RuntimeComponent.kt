package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.RuntimeContext
import skillbill.app.LearningService
import skillbill.app.ReviewService
import skillbill.app.SystemService
import skillbill.app.TelemetryService

@Component
abstract class RuntimeComponent(
  @get:Provides val runtimeContext: RuntimeContext,
) {
  abstract val learningService: LearningService
  abstract val reviewService: ReviewService
  abstract val systemService: SystemService
  abstract val telemetryService: TelemetryService
}
