package skillbill.di

import skillbill.infrastructure.fs.FileSystemInstallSelectionPersistence
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.infrastructure.fs.FileSystemScaffoldGateway
import skillbill.infrastructure.fs.JdkBoundedWorkFanOutPort
import skillbill.ports.concurrency.BoundedWorkFanOutPort
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.install.selection.InstallSelectionPersistencePort
import skillbill.ports.scaffold.ScaffoldGateway

internal object RuntimeComponentBindingsA7 {
  internal fun installSelectionPersistencePort(
    adapter: FileSystemInstallSelectionPersistence,
  ): InstallSelectionPersistencePort = adapter

  internal fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig): RepoLocalConfigPort = adapter

  internal fun scaffoldGateway(gateway: FileSystemScaffoldGateway): ScaffoldGateway = gateway

  internal fun boundedWorkFanOutPort(adapter: JdkBoundedWorkFanOutPort): BoundedWorkFanOutPort = adapter

  // SKILL-52.1 subtask 2: typed capability ports for the scaffold pipeline. These are wired
  // alongside the legacy `ScaffoldGateway` raw-map adapter so subtask 3 can migrate the
  // application-layer scaffold service over without further DI churn. The legacy
  // `ScaffoldGateway` binding above intentionally stays.
}
