package skillbill.di

import skillbill.application.goalrunner.planning.DurableGoalPlanningRejectionRecorder
import skillbill.application.goalrunner.planning.GoalPlanningRejectionRecorder
import skillbill.application.telemetry.GoalLifecycleTelemetryEmitter
import skillbill.application.telemetry.LifecycleTelemetryService
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.contracts.goalplanning.GoalPlanningDiscoveryExclusions
import skillbill.goalplanning.FileSystemGoalPlanningBoundaryBodyResolver
import skillbill.goalplanning.FileSystemGoalPlanningContextDiscovery
import skillbill.infrastructure.fs.FileSystemReviewNativeAgentPreflight
import skillbill.infrastructure.fs.JdkDaemonThreadPort
import skillbill.infrastructure.fs.JdkIdentifierGeneratorPort
import skillbill.infrastructure.fs.JdkRuntimeDiagnostics
import skillbill.infrastructure.fs.JdkRuntimeTimingPort
import skillbill.infrastructure.fs.JdkShutdownHookPort
import skillbill.model.OptionalCallbacks
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.planning.GoalPlanningBoundaryBodyResolver
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.review.ReviewNativeAgentPreflightPort
import skillbill.ports.time.RuntimeTimingPort

internal object RuntimeGoalRunnerDiagnosticsBindings {
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

  internal fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort): RuntimeTimingPort =
    callbacks.runtimeTimingPort ?: adapter

  internal fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics): RuntimeDiagnostics {
    RecordingNullObjectDiagnostics.bind { message, error -> adapter.warning(message, error) }
    return adapter
  }

  internal fun shutdownHookPort(adapter: JdkShutdownHookPort): ShutdownHookPort = adapter

  internal fun daemonThreadPort(adapter: JdkDaemonThreadPort): DaemonThreadPort = adapter

  internal fun identifierGeneratorPort(adapter: JdkIdentifierGeneratorPort): IdentifierGeneratorPort = adapter

  internal fun reviewNativeAgentPreflightPort(
    callbacks: OptionalCallbacks,
    adapter: FileSystemReviewNativeAgentPreflight,
  ): ReviewNativeAgentPreflightPort = callbacks.reviewNativeAgentPreflight ?: adapter
}
