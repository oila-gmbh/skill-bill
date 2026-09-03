package skillbill.di

import skillbill.infrastructure.fs.CanonicalRepositoryRoot
import skillbill.infrastructure.fs.FileExternalAddonSourceConfigStore
import skillbill.infrastructure.fs.FileTelemetryConfigStore
import skillbill.infrastructure.http.JdkHttpRequester
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.model.EnvironmentContext
import skillbill.model.OptionalCallbacks
import skillbill.model.RepositoryRoot
import skillbill.model.RuntimeContext
import skillbill.model.TransportContext
import skillbill.model.WorkflowOpsContext
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.install.addon.ExternalAddonSourceConfigPort
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.ports.telemetry.TelemetryConfigStore
import skillbill.ports.telemetry.UnconfiguredRemoteTransportPort
import java.nio.file.Path

internal object RuntimeComponentBindingsA1 {
  fun repositoryEnclosingRootPort(): RepositoryEnclosingRootPort = CanonicalRepositoryRoot

  fun runtimeContext(inputRuntimeContext: RuntimeContext): RuntimeContext {
    val repositoryEnclosingRootPort = repositoryEnclosingRootPort()
    val inputEnvironment = inputRuntimeContext.environment
    val resolvedEnvironment =
      if (inputEnvironment.userHome == EnvironmentContext.UnspecifiedUserHome) {
        inputEnvironment.copy(userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())
      } else {
        inputEnvironment
      }
    val environmentWithEnv =
      if (resolvedEnvironment.environment === EnvironmentContext.UnspecifiedEnvironment) {
        resolvedEnvironment.copy(environment = System.getenv())
      } else {
        resolvedEnvironment
      }
    val inputTransport = inputRuntimeContext.transport
    val resolvedTransport =
      if (inputTransport.requester === UnconfiguredRemoteTransportPort) {
        inputTransport.copy(requester = JdkHttpRequester)
      } else {
        inputTransport
      }
    val resolvedRepositoryRoot =
      if (environmentWithEnv.repositoryRoot == EnvironmentContext.UnspecifiedRepositoryRoot) {
        environmentWithEnv.copy(repositoryRoot = repositoryEnclosingRootPort.enclosingRepositoryRoot(Path.of("")))
      } else {
        environmentWithEnv.copy(
          repositoryRoot = repositoryEnclosingRootPort.enclosingRepositoryRoot(environmentWithEnv.repositoryRoot),
        )
      }
    return inputRuntimeContext.copy(
      environment = resolvedRepositoryRoot,
      transport = resolvedTransport,
    )
  }

  fun repositoryRoot(context: EnvironmentContext): RepositoryRoot = RepositoryRoot(context.repositoryRoot)

  fun environmentContext(ctx: RuntimeContext): EnvironmentContext = ctx.environment

  fun transportContext(ctx: RuntimeContext): TransportContext = ctx.transport

  fun workflowOpsContext(ctx: RuntimeContext): WorkflowOpsContext = ctx.workflowOps

  fun optionalCallbacks(ctx: RuntimeContext): OptionalCallbacks = ctx.callbacks

  fun databaseSessionFactory(context: EnvironmentContext): DatabaseSessionFactory =
    SQLiteDatabaseSessionFactory(context)

  internal fun telemetryConfigStore(store: FileTelemetryConfigStore): TelemetryConfigStore = store

  internal fun externalAddonSourceConfigPort(
    store: FileExternalAddonSourceConfigStore,
  ): ExternalAddonSourceConfigPort = store
}
