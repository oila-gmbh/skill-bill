package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.contracts.diagnostics.RecordingNullObjectDiagnostics
import skillbill.infrastructure.fs.JdkBoundedWorkFanOutPort
import skillbill.infrastructure.fs.JdkDaemonThreadPort
import skillbill.infrastructure.fs.JdkHostPlatformPort
import skillbill.infrastructure.fs.JdkIdentifierGeneratorPort
import skillbill.infrastructure.fs.JdkRuntimeDiagnostics
import skillbill.infrastructure.fs.JdkRuntimeTimingPort
import skillbill.infrastructure.fs.JdkShutdownHookPort
import skillbill.model.OptionalCallbacks
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.DaemonThreadPort
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.process.ShutdownHookPort
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.time.JvmSystemClock
import skillbill.ports.time.RuntimeTimingPort
import java.time.Clock

internal interface RuntimeDiagnosticsProvides {
  @Provides @JvmSynthetic
  fun runtimeDiagnostics(adapter: JdkRuntimeDiagnostics): RuntimeDiagnostics {
    RecordingNullObjectDiagnostics.bind { message, error -> adapter.warning(message, error) }
    return adapter
  }

  @Provides @JvmSynthetic
  fun runtimeTimingPort(callbacks: OptionalCallbacks, adapter: JdkRuntimeTimingPort): RuntimeTimingPort =
    callbacks.runtimeTimingPort ?: adapter

  @Provides @JvmSynthetic
  fun shutdownHookPort(adapter: JdkShutdownHookPort): ShutdownHookPort = adapter

  @Provides @JvmSynthetic
  fun daemonThreadPort(adapter: JdkDaemonThreadPort): DaemonThreadPort = adapter

  @Provides @JvmSynthetic
  fun identifierGeneratorPort(adapter: JdkIdentifierGeneratorPort): IdentifierGeneratorPort = adapter

  @Provides @JvmSynthetic
  fun boundedWorkFanOutPort(adapter: JdkBoundedWorkFanOutPort): BoundedWorkFanOutPort = adapter

  @Provides @JvmSynthetic
  fun hostPlatformPort(callbacks: OptionalCallbacks): HostPlatformPort =
    callbacks.hostPlatformPort ?: JdkHostPlatformPort

  @Provides @JvmSynthetic
  fun runtimeClock(): Clock = JvmSystemClock
}
