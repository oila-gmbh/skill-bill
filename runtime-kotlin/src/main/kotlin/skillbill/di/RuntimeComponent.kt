package skillbill.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import skillbill.RuntimeContext
import skillbill.application.LearningService
import skillbill.application.ReviewService
import skillbill.application.SystemService
import skillbill.application.TelemetryService

@Component
abstract class RuntimeComponent(
  @get:Provides val runtimeContext: RuntimeContext,
) {
  abstract val learningService: LearningService
  abstract val reviewService: ReviewService
  abstract val systemService: SystemService
  abstract val telemetryService: TelemetryService
}
