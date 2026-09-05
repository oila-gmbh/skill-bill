package skillbill.di

import me.tatarka.inject.annotations.Provides
import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.infrastructure.fs.FileSystemRepoValidationGateway
import skillbill.infrastructure.fs.validation.FileSystemValidationGateRunner
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.scaffold.repo.ScaffoldRepoValidationPort
import skillbill.ports.validation.RepoValidationGateway
import skillbill.ports.validation.ValidationGateRunner
import skillbill.scaffold.adapters.FileSystemScaffoldRepoValidation

internal interface RuntimeScaffoldValidationProvides {
  @Provides @JvmSynthetic
  fun scaffoldRepoValidationPort(adapter: FileSystemScaffoldRepoValidation): ScaffoldRepoValidationPort = adapter

  @Provides @JvmSynthetic
  fun repoLocalConfigPort(adapter: FileSystemRepoLocalConfig): RepoLocalConfigPort = adapter

  @Provides @JvmSynthetic
  fun repoValidationGateway(gateway: FileSystemRepoValidationGateway): RepoValidationGateway = gateway

  @Provides @JvmSynthetic
  fun validationGateRunner(runner: FileSystemValidationGateRunner): ValidationGateRunner = runner
}
