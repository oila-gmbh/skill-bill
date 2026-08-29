package skillbill.di

import skillbill.application.goalrunner.GoalLifecycleTelemetryEmitter
import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.infrastructure.fs.JdkRuntimeDiagnostics
import skillbill.infrastructure.fs.JdkRuntimeTimingPort
import skillbill.model.OptionalCallbacks
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.time.RuntimeTimingPort
import java.time.Clock

internal object RuntimeComponentBindingsA5 {
  internal fun goalPlanningRejectionRecorder(
    recorder: DurableGoalPlanningRejectionRecorder,
  ): GoalPlanningRejectionRecorder = recorder

  internal fun goalPlanningContextDiscovery(
    adapter: FileSystemGoalPlanningContextDiscovery,
  ): GoalPlanningContextDiscovery = adapter

  internal fun goalPlanningBoundaryBodyResolver(
    adapter: FileSystemGoalPlanningBoundaryBodyResolver,
  ): GoalPlanningBoundaryBodyResolver {
    // SKILL-174: the exclusion contract is read through a lazy classpath singleton rather than an
    // injected port. Forcing it here turns "the contract is missing from a packaged artifact" into a
    // typed wiring failure instead of a durable planning block discovered halfway through a goal.
    GoalPlanningDiscoveryExclusions.excludedRoots
    return adapter
  }

  // SKILL-66 Subtask 3: GoalRunner reaches lifecycle-telemetry emission only
  // through the application-owned GoalLifecycleTelemetryEmitter seam (backed by
  // LifecycleTelemetryService) and times every payload off this injected clock.

  internal fun goalLifecycleTelemetryEmitter(service: LifecycleTelemetryService): GoalLifecycleTelemetryEmitter =
    service

  internal fun runtimeClock(): Clock = Clock.systemUTC()

  internal fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort): RuntimeTimingPort =
    callbacks.runtimeTimingPort ?: adapter

  internal fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics): RuntimeDiagnostics = adapter

  internal fun reviewNativeAgentPreflightPort(
    callbacks: OptionalCallbacks,
    adapter: FileSystemReviewNativeAgentPreflight,
  ): ReviewNativeAgentPreflightPort = callbacks.reviewNativeAgentPreflight ?: adapter
}
